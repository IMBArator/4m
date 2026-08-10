# 4M — implementation plan

Synchronised web radio for Minecraft 1.20.1. This is the working plan: what is built, what is next,
and the reasoning that is not recoverable from the diff.

The architecture decisions live in [`docs/adr/`](docs/adr/) and are the authority — §13 indexes them
and records the one amendment this plan makes. `README.md` is for people who want to *use* the mod.

> **Status — 2026-08-10.** `:core` is complete through the relay and green (154 tests). The Forge
> module loads, registers and renders a craftable radio block. The full client audio path is now
> written and **compiles against Minecraft**: relay → packets → client sessions → drift loop →
> streaming `AudioStream` via `SoundInstance.getStream`. **It has not yet been heard in game** —
> `runClient` and the §11 checks are the next thing. See §9 for the exact state.

---

## 1. Goal and shape

A placeable **radio block** plays live internet radio with positional 3D audio, **tunnelled through
the server so every client hears the same audio at the same instant**.

| Question | Choice |
|---|---|
| Minecraft version | 1.20.1 |
| Sync model | **Server relay** — one upstream connection, framed media on a shared clock |
| Loader | Forge now, NeoForge later from a shared source dir (§9 step 9) |
| Playback | Radio block, positional, mono |
| Formats | MP3 now; Vorbis and AAC/HLS planned |

**v1 is audio. Video is planned**, and that single fact changes several decisions that would be
expensive to reverse. Where it costs real effort now it is called out; where it is free it is simply
taken (§12).

### What the relay buys, and what it costs

True sync is achievable *only* because the relay gives every client **identical bytes** plus **a
common clock**. Client-direct streaming cannot be synced at all — each listener lands at a different
point in the station's encoder buffer, and a live stream carries no timestamp to align against.
Target: **< 50 ms** spread between clients.

Two costs, both real:

- **Bandwidth.** ~16 KB/s per listening player on the server uplink, sharing the Minecraft TCP
  connection. Mitigated by transmitting only to players in earshot (§6.3). **Video will not fit
  through this pipe** — see §12 and ADR-0006.
- **Re-streaming.** The server appears to the station as one listener while serving many, which may
  breach a station's terms and undercounts listeners for royalty reporting. A judgement call for
  whoever runs the server; the shipped station list should contain only stations that permit it.

---

## 2. Layout

```
mmmm/
├── docs/adr/       the decisions (§13)
├── core/           PURE JAVA — no Minecraft, no loader, media-neutral, unit-testable
│   └── mmmm/core/
│       ├── source/     origin transport      (SERVER)
│       ├── frame/      container parsing     (SERVER)
│       ├── relay/      session + fan-out     (SERVER)
│       ├── codec/      decoders              (CLIENT)
│       ├── audio/      PCM ring              (CLIENT)
│       ├── transport/  relay wire abstraction (BOTH)
│       └── sync/       clock + drift control  (BOTH)
├── common/         Minecraft code, no loader code — a SOURCE DIR, not a project (ADR-0002)
├── forge/          ForgeGradle 6, Forge 1.20.1-47.4.0
└── neoforge/       commented out of settings.gradle — see §9 step 9
```

`common/` is pulled into each loader's source set and compiles twice, so it must not reference
`DeferredRegister`, `RegistryObject` or any other loader API. It *may* reference `:core`, which is
loader-free by construction — that is what lets `RadioServer` and the whole client audio path live
in shared code with only registration and packets left per-loader.

Two seams carry the loader-specific results back into shared code, both installed by the entry class:

- `MmmmContent.bind(…)` — the `BlockEntityType` and `SoundEvent`, as suppliers, because registry
  contents do not exist when the entry class runs.
- `RadioBlock.setClientTicker(…)` — so nothing on a class the dedicated server loads leads to
  `net.minecraft.client`. A server that loads a client class dies with a stack trace pointing
  anywhere but at the cause.

---

## 3. The media model

Everything downstream depends on these. They are deliberately **not** audio-specific.

```java
record MediaFrame(int streamId, long ptsMicros, boolean keyframe, byte[] payload) {}
record StreamInfo(int streamId, Codec codec, int sampleRate, int channels,
                  int width, int height, byte[] codecInit) {}
```

Three choices that are free today and expensive to retrofit:

- **PTS in microseconds, not sample counts.** Video has no samples; µs is finer than one sample at
  44.1 kHz (22.68 µs), so nothing is lost. **But derive it from a cumulative exact counter, never by
  accumulating rounded per-frame deltas** — an MP3 frame at 44.1 kHz is 26122.448… µs, and summing a
  rounded version loses ~0.45 µs each time: 60 ms after an hour, one-directional, looking exactly
  like a clock bug and not being one. `Timeline` exists to make that mistake hard to write, and both
  the parser and the client's position arithmetic go through it.
- **`keyframe`**, meaningless for audio, load-bearing for video: the backlog window must start at a
  keyframe or the first second decodes to garbage. `FrameBacklog.snapshot()` already enforces this.
- **`streamId`**, so one session can carry audio and video later without a wire-format break.

---

## 4. Server side

> **The server never decodes and never touches audio hardware.** It parses frame *headers* only.
> `:core:checkServerSideHasNoCodecDeps` enforces this for `core/source`, `core/frame`,
> `core/transport` and `core/relay`.

### 4.1 `IcyHttpSource` — pulling from the origin

Hand-rolled HTTP/1.0 over a raw `Socket`/`SSLSocket` (ADR-0009). Shoutcast answers `ICY 200 OK`,
which both `HttpURLConnection` and `java.net.http.HttpClient` reject outright. Owning the socket also
gives ADR-0011 what it needs: connect to an address the guard already validated rather than
re-resolving a hostname, and re-validate after every redirect.

`StationResolver` follows playlist indirection first — a station URL copied from a website is usually
a `.pls` listing an `.m3u` listing the real endpoint.

### 4.2 `FrameParser` — the timeline, without decoding

Parsing container headers gives every frame an **exact** duration, so the server builds a
sample-accurate timeline at near-zero CPU and with no codec on its classpath.

| Codec | Boundary | Duration |
|---|---|---|
| MP3 | 4-byte header → bitrate/rate/padding | 1152 samples (MPEG-1 L3), 576 (MPEG-2/2.5) |
| AAC | ADTS header → `aac_frame_length` | 1024 samples |
| Ogg Vorbis | page structure; `granulepos` **is** a sample counter | read from granulepos |

`FormatSniffer` picks the parser from magic bytes and takes precedence over `Content-Type`, because
stations lie about it constantly.

### 4.3 `RelaySession` — one per station, and where the epoch comes from

A daemon thread `4m-relay-<n>` runs source → parse → fan-out. **This is the part of the design
that changed during implementation, and it matters.**

Clients render the frame stamped `pts` at server time `epoch + pts`. The obvious way to place that
epoch is "server time when the first frame arrived". **That is wrong**, because Icecast hands a new
listener its entire buffer the instant the socket opens — measured against real stations, from a few
seconds to over thirty (`StreamProbe` reports it; the MP3 verification run produced 30.8 s of audio
in 15 s of wall time) — and only then throttles to realtime. During that burst media time races
ahead of wall time, so an epoch taken at the first frame places every later frame that much too far
in the future, and every client sits seconds out of position with no visible cause.

What *is* invariant is `arrival − pts`: it falls steadily through the burst and stops falling once
the origin settles to realtime. So:

- The epoch is the **minimum** of `arrival − pts`, not the first sample and not the mean. Same
  reasoning as the min-RTT clock filter (§5.1): the quantity is a floor plus a one-sided delay.
- The session stays in `BUFFERING` until that minimum has stopped improving for `settleQuietMs`
  (2 s), then announces itself and moves to `PLAYING`.
- Frames received while settling are **held and published once the epoch is known**, not discarded.
  This is what makes the burst useful rather than merely awkward: it *is* the backlog window a
  joining client needs, delivered before anyone asked for it.
- A reconnect keeps the announced epoch — subscribers already hold it — so settling runs again and
  the result is applied as a `ptsOffset` that lands the new timeline back on the original epoch.

A byte cap bounds the settle buffer, because an origin that never stops bursting would otherwise
hold the session in `BUFFERING` while the heap fills.

**Backlog ring** (`FrameBacklog`): a rolling window of `presentationDelay + margin`, trimmed by
timestamp rather than frame count because frame durations differ between codecs and even between
frames. A byte cap sits on top, since timestamps come from the origin and are untrusted input.

**States:** `CONNECTING → BUFFERING → PLAYING`, `RECONNECTING` on fault, `FAILED`, `CLOSED`.
Reconnect backoff is exponential, 1 s → 30 s, and resets after a connection survives 30 s so an
hours-long healthy session is not punished at the ceiling for one hiccup. `FAILED` is reserved for
faults retrying cannot fix — a refused egress destination, or a stream nothing could decode. Both
would otherwise be a busy loop against someone else's server.

### 4.4 `RelayManager` — two counts, deliberately not one

- **Sessions follow blocks.** A radio switched on holds a claim; the upstream socket closes when the
  last block lets go. Tie it to nearby players instead and every player who wanders off and back pays
  a fresh connect plus the settling window.
- **Transmission follows players.** Only players in earshot receive frames (§6.3).

Proximity is decided **per session, not per block**. Two radios playing one station share a session;
deciding per block means the far one unsubscribes a player the near one just subscribed, and which
ticks last decides whether anyone hears anything — an intermittent fault that depends on block
placement order. `RadioServer` accumulates the union across blocks during the tick and applies it
once via `RelaySession.syncSubscribers`.

---

## 5. Sync

Standard multi-room audio design — the same shape Snapcast and AirPlay use: **shared clock + fixed
presentation delay + tiny rate trim.** It generalises to A/V sync unchanged (§12).

### 5.1 Shared clock

`C2SClockPing{clientNanos}` → `S2CClockPong{clientNanos, serverNanos}`; the client computes
`offset = serverNanos − (t0 + rtt/2)`. **Keep the minimum-RTT sample** over a sliding window rather
than averaging: round-trip delay is not symmetric noise around a true value, it is a floor plus a
one-sided, heavy-tailed queueing delay, and on a connection shared with game traffic that queueing is
substantial. Burst ~8 pings on join, then every 5 s. Realistic accuracy: **±10–30 ms**.

Playback waits for convergence. Starting before the estimate settles means starting at the wrong
position and then hard-resyncing, which is audible; a short silence at join is not.

### 5.2 Fixed presentation delay

The server declares `presentationDelayMs` (default **3000**). Every client renders `pts` at
`epoch + pts + D`. D must exceed worst-case client jitter plus decode time. The backlog ring removes
the startup cost for everyone who joins after the first listener.

### 5.3 Drift correction — `AL_PITCH` as a rate trim

| Drift | Action |
|---|---|
| < 10 ms | deadband — the integral keeps holding, the proportional term is gated off |
| 10–250 ms | PI control, trimmed to at most ±0.1 % |
| > 250 ms | hard resync: flush and jump |

The integral term is the part that matters. The dominant disturbance is not network jitter, it is the
listener's sound card: consumer audio clocks run tens to hundreds of ppm off nominal, and that error
is *constant*. A proportional-only controller cannot cancel a constant disturbance — it needs a
standing error to produce a standing output — so it parks around 12 ms off against a 50 ppm card and
stays there, inside the resync threshold, with every client at its own offset. This is the difference
between clients that converge and clients that merely stop diverging.

0.1 % is ~1.7 cents of pitch, inaudible, and `AL_PITCH` already resamples — so
`RadioSoundInstance.getPitch()` returning the trim is the **entire** control surface.

**Client position is derived, not counted.** `PcmRingBuffer` deliberately lies in both directions: it
pads underruns with silence and drops the oldest audio on overrun. A read counter sees neither, so
position is computed as "where the writer is, minus what is still buffered", which stays exact
however much the ring has padded or dropped.

---

## 6. Networking, block and state

### 6.1 Packets (`SimpleChannel`, behind `MediaTransport`)

**S2C:** `StreamOpen` (session, origin, `List<StreamInfo>`, epoch, delay, backlog) · `StreamData` ·
`StreamMeta` (timestamped, so "now playing" flips *with* the audio rather than on arrival) ·
`StreamClose` · `ClockPong`.
**C2S:** `ClockPing`.

No `ConfigureRadio` packet: right-click is handled by vanilla `use()`, which is already server-side.
It returns when the GUI does (§9 step 8).

All media sends route through `MediaTransport` rather than touching `SimpleChannel` directly
(ADR-0006). `:core` cannot see the loader's networking API, which makes the violation impossible
rather than merely discouraged.

### 6.2 Block state — reuse vanilla, write no packet

`RadioBlockEntity` (station, playing, volume, sessionId) syncs via `getUpdatePacket()` and
`getUpdateTag()`. That machinery already handles chunk tracking, players joining mid-session and
reconnects. `sessionId` rides along, which is what lets a client match a block to the audio arriving
for it without a further round trip — and it is deliberately **not** saved to disk, because a session
id is valid only for the server run that issued it.

`setRemoved()` drops the upstream claim, which covers breaking, chunk unload and world unload in one
override. Hooking only the break path is the classic version of this bug: the radio in a chunk nobody
has visited for an hour is still holding a socket open.

### 6.3 Only transmit to players who can hear it

Subscribe within `range + 8`, unsubscribe beyond `range + 16`, where `range` is the 16-block
attenuation distance. The gap is hysteresis — without it a player on the boundary re-subscribes every
proximity round, and each one costs a `StreamOpen` with a full backlog. Recomputed every 20 ticks;
players do not move 8 blocks in a second.

### 6.4 Singleplayer

The integrated server runs the identical path over the loopback connection. No special case, and
every sync bug is reproducible in `runClient`.

---

## 7. Client playback

### 7.1 Decode

MP3 via JLayer. The `Bitstream` and `Decoder` are **long-lived**: MP3 has a bit reservoir, so a frame
may spend bits carried over from earlier frames. Building a fresh pair per frame looks tidier and
quietly mangles most frames on a real station. A corrupt frame costs that frame, not the session —
`RuntimeException` is caught alongside the declared ones because JLayer throws
`ArrayIndexOutOfBounds` from its Huffman tables on some malformed input.

### 7.2 Downmix to mono

**OpenAL applies 3D positioning only to mono sources.** A stereo buffer plays flat at full volume
everywhere, silently removing the entire point of a positional block. Vanilla music discs are mono
for exactly this reason. Config `stereoWhenClose` swaps to a non-attenuated stereo channel within N
blocks later (§9 step 7).

> **If a radio does not attenuate as you walk away, the downmix has regressed.** That is the whole
> failure signature; it is silent otherwise.

### 7.3 Reaching the sound engine — **no Mixin needed on Forge**

This is the second design change found during implementation, and it removes what the original plan
called the riskiest piece of the project.

Forge patches `SoundInstance` with a hook vanilla does not have:

```java
default CompletableFuture<AudioStream> getStream(SoundBufferLibrary buffers, Sound sound, boolean looping)
```

and `SoundEngine.play` calls **that**, not `SoundBufferLibrary.getStream` directly. Verified against
`forge-1.20.1-47.4.0_mapped_parchment_2023.09.03-1.20.1.jar`: `SoundEngine.play` contains
`invokeinterface SoundInstance.getStream(…)`, and the default body simply delegates to
`buffers.getStream(sound.getPath(), looping)`.

So `RadioSoundInstance` overrides `getStream` and returns its own `AudioStream`. No mixin into a
vanilla method, no version fragility, and nothing to conflict with Sound Physics or other audio
overhauls. ADR-0007's *decision* — integrate via vanilla `SoundEngine` with a real `SoundInstance`,
so volume categories, attenuation, pause and device reload all work by construction — stands
unchanged; only the mechanism for supplying the stream is simpler than planned. See §13.

Still required, and unchanged: a registered `SoundEvent mmmm:radio_stream`, marked `"stream": true`
in `sounds.json`, with a tiny placeholder ogg so `SoundManager` resolves the event. The ogg is never
read.

> **The gotcha that decides whether this works at all:** `Channel.updateStream()` treats a short or
> empty `read()` as end-of-stream and stops the sound permanently. Underruns are routine on live
> radio — every network hiccup is one — so the adapter must return **exactly** the requested byte
> count, padding with silence. `PcmRingBuffer` guarantees that, which is why the guarantee lives
> there rather than in the adapter.

### 7.4 One decoder per block, for now

Each playing block gets its own `ClientMediaSession`: decoder, ring and cursor. Two radios on one
station therefore decode it twice. That is a deliberate simplification — the shared-decode design has
one decoder feeding many cursors, and is worth building when there is a reason to have several radios
on one station. One extra MP3 decode is a rounding error next to rendering.

`ClientMedia` closes a session whose block has stopped ticking, which covers break, chunk unload,
world unload and dimension change without a hook for any of them.

---

## 8. Security

The relay **removes** client-side request forgery and **concentrates** it on the server, which is
worse: a player-supplied URL makes the *server* open outbound connections from inside your hosting
environment.

- **Default-deny allowlist** (ADR-0011). The station list *is* the allowed set. Blocklisting alone
  fails open: a missed range, a new cloud metadata address, or an operator who never opens the config
  all become vulnerabilities.
- Block link-local `169.254.0.0/16` explicitly — the AWS/GCP/Azure metadata endpoint, and a game
  server on a cloud host is exactly where that yields credentials. Plus loopback, RFC1918, CGNAT,
  `.local` and all IPv6 equivalents.
- Check **after DNS resolution** and **re-check after every redirect**, or DNS rebinding walks
  straight through.
- Caps on redirects, timeouts and header size.

---

## 9. Build order and status

Numbered to match the original plan, so references elsewhere still resolve. Step 5 is gone because
it merged into 4b.

**1. ~~`:core` transport~~** — **DONE.** `IcyHttpSource`, `StationResolver`, `StreamProbe`.

**2. ~~`FrameParser` + `FormatSniffer`~~** — **DONE**, under JUnit against exact timelines.

**3. Decoders** — **MP3 DONE.** `Decoder`, `JLayerDecoder`, `DecodeProbe`. Verified live against
SomaFM Groove Salad: 1182 frames, 0 dropped, 44.1 kHz stereo, RMS −18 dBFS with genuine L/R
difference.

- **AAC blocked.** `net.sourceforge.jaadec:jaad:0.8.6` does not exist on Maven Central;
  `org.jaadec:jaad`, `net.sourceforge.jaad:jaad` and `com.github.dv8fromtheworld:jaad` all 404 as
  well. Commented out in `core/build.gradle` and `forge/build.gradle` with a TODO. Needs real
  coordinates or a different library before AAC works.
- **Vorbis not started.** Plan: reuse Minecraft's `OggAudioStream` (STB Vorbis via LWJGL) behind
  the `Decoder` interface, in `common/` — zero new dependency.

**4. `:forge` module.**

- **~~4a~~** — **DONE.** Toolchain, entry class, registries, `RadioBlock` ported from the 2022
  Forge 1.18.2 `webradiomod` prototype, assets, recipe, loot table, tag. Verified in game.
- **4b + 5, merged** — **COMPILES, NOT YET HEARD.** Scoped as *server opens and relays* (so 4b and
  5 are one milestone), **MP3 only**, **one ring per block**.
  - **Done:** `:core` relay — `RelaySession`, `RelayManager`, `FrameBacklog`, `SourceOpener`,
    `RelayConfig`, `SessionState`. 154 tests green.
  - **Done:** the whole Minecraft-side path. `MmmmContent`, `Stations`, `RadioBlockEntity`,
    `RadioBlock` behaviour, `PlayerSubscriber`, `RadioServer`, `ClientMediaSession`; the client
    orchestrator `ClientMedia` (sessions, shared `ClockFilter`, drift loop, stale sweep); the
    `AudioStream`/`SoundInstance` pair (`RadioAudioStream`, `RadioSoundInstance`) that supplies PCM
    through Forge's `getStream` hook with no Mixin; the loader-neutral `ClientMessages` records plus
    the `ClientNetwork` facade; the six packets, `MmmmNetwork` and `ForgeMediaTransport`; the block
    entity + sound event registration; `sounds.json` and the placeholder ogg; the lifecycle wiring
    in `MmmmForge` (server tick, server start/stop, player logout, client tick, client disconnect,
    client ticker install, ping sender install). `:forge:build` is green.
  - **Remaining:** `runClient` and the §11 in-game checks. The compile can no longer surface a
    design fault, only the runtime can — the §11 list is the next thing to walk through. After that:
    tuning (step 6), positional polish (step 7), config/commands/GUI (step 8).

**6. Drift control tuning** and the sync-health readout.

**7. Positional tuning**: attenuation curve, `stereoWhenClose`.

**8. Config, commands, GUI.** `/mmmm radio <play|stop|station>`, server station list and allowlist,
the right-click screen with a sync-health readout, and the `ConfigureRadio` packet it needs.

**9. `:neoforge`** — *not* mechanical, contrary to the original estimate. The entry class genuinely is
   a copy: NeoForge 47.1.106 is the Forge 47.1 fork, with the same `net.minecraftforge.*` packages and
   the same `modId="forge"`; the rename landed in NeoForge 20.2. But `neoforge/build.gradle` targets
   the wrong toolchain and cannot even configure — `net.neoforged.gradle.userdev` 7.x is 1.20.2+, the
   1.20.1 plugin is `net.neoforged.gradle` 6.0.x or ModDevGradle's `net.neoforged.moddev.legacyforge`;
   Parchment's Librarian is ForgeGradle-only; MixinGradle 0.7 needs ForgeGradle's `reobf`; and
   NeoGradle 7 has no reobf step, which 1.20.1's SRG runtime requires. Commented out of
   `settings.gradle` until repaired. **Also needs checking:** whether NeoForge 47.1.106 carries the
   `SoundInstance.getStream` patch §7.3 relies on. It is the Forge 47.1 fork, so it almost certainly
does — but "almost certainly" is what the mixin fallback exists for.

**10. Lifecycle hardening** (§10) and the rest of §8.

**11. HLS** last — the one transport deferrable without blocking anything, and the on-ramp to video.

### Known defect, unrelated to the milestone

**No produced jar is currently loadable.** `implementation project(':core')` puts `:core` on the
classpath but does not embed it; only `shadowJar` embeds, and it is never reobfed and does not inherit
the `jar` manifest. So the reobfed `jar` would throw `NoClassDefFoundError: mmmm/core/…` at runtime.
`runClient` runs from the source set, so development is unaffected. The fix is `reobf { shadowJar {} }`,
a manifest copy, and deciding which classifier ships — its own commit, not this milestone's.

---

## 10. Client lifecycle edges — each has bitten this class of mod before

- **Game paused** (singleplayer ESC): vanilla pauses the channel while frames keep arriving. On
  resume, re-derive the cursor from the clock. §5.3's hard-resync path handles this for free, which is
  a decent sign the design is the right shape.
- **`SoundEngine.reload()`** (resource pack change, audio device switch) destroys every channel.
  Detect the dead channel via `SoundManager.isActive` and re-issue `play()`.
- **Disconnect / world unload**: close sessions and stop threads. Leaked threads across world loads
  are the classic failure.
- **Block broken / chunk unloaded**: drop the cursor, decrement the refcount, close at zero.
- **Clock not yet converged on join**: hold playback rather than starting wrong and resyncing audibly.

---

## 11. Verification

**Unit (`:core`, no game)** — 154 tests today. Frame timelines against known-duration fixtures,
including a long fixture that would expose µs rounding accumulation; clock filter convergence under
injected jitter and asymmetry; drift controller settling without hunting and exactly one resync on a
500 ms step; ring buffer underrun returning full-length silence; relay epoch placement after a
simulated burst, backlog trimming, no duplicate delivery to a joining subscriber, permanent vs.
retryable failures.

**Headless integration:** `tools/build-core.sh decode <url> out.wav` — 30 s of a real station, then
listen. Length alone proves nothing: a byte-order slip, a swapped channel and a lost bit reservoir all
produce PCM of exactly the right length.

**In-game (`runClient`):**
1. Place a radio, right-click → audio within ~2 s (the backlog ring working).
2. Walk away → smooth attenuation. If it does not attenuate, the mono downmix regressed.
3. Two blocks, same station → one upstream socket (`ss -tp | grep java`), audio identical.
4. ESC-pause 30 s, resume → audio is **live**, not stale.
5. `F3+T` resource reload → audio recovers.
6. Break the block → last one closes the upstream; no `4m-relay-*` or `4m-decode-*` threads
   survive (`jstack`).
7. Kill upstream connectivity → `RECONNECTING`, recovers on restore.

**The sync test that actually matters** — dedicated server, 2+ clients:
- Two clients on one machine, both unmuted: any offset above ~20 ms is plainly audible as phasing.
  **Trust your ears before the readout.**
- For a number: a station with sharp transients, record both outputs, cross-correlate. Target < 50 ms.
- Add ~200 ms to one client with `tc netem` → re-converges via rate trim with no audible jump.
- Join a third client mid-song → in sync immediately, no D-second silence.
- Run 30+ minutes → **drift must not accumulate.** A steady one-directional creep is a `FrameParser`
  timeline bug, not a clock bug.

---

## 12. Forward compatibility: video

No video code gets written now. What v1 does is avoid the three decisions that would force a rewrite.

**Taken now, free:** media-neutral `MediaFrame`/`StreamInfo` with µs PTS, keyframe flag, `streamId`
and generic `codecInit`; `MediaTransport` between the relay and `SimpleChannel`; HLS transport, which
is how most video origins deliver.

**Reuses unchanged:** the whole sync mechanism. Shared clock + presentation delay + rate trim *is* the
A/V sync design — audio becomes the master clock and video frames are dropped or repeated against it.

**Deliberately unresolved:**

- **Transport.** Video is 30–100× audio's bitrate. The Minecraft connection has head-of-line blocking
  and shares a socket with gameplay, so pushing video through it will cause visible rubber-banding.
  The escape hatch is a second `MediaTransport` implementation — a dedicated port, or an embedded HTTP
  server the client pulls HLS from. That is a real decision with real ops cost, which is why it should
  be made when video is built rather than guessed at now. **The single most likely thing to be
  regretted if it is not kept behind an interface** — hence ADR-0006.
- **Decode.** Software H.264 in pure Java is not viable at useful resolutions; realistically native
  bindings, with a large payload and per-platform packaging. `Decoder` must not assume pure Java.
- **Render.** Decoded frames → `DynamicTexture` → block face. Upload cost on the render thread is the
  open performance question.

---

## 13. Decisions

Full records in [`docs/adr/`](docs/adr/), MADR 4.0.0.

| # | Decision | Status |
|---|---|---|
| 0001 | Target Minecraft 1.20.1 | Accepted |
| 0002 | Ship Forge and NeoForge from a shared source directory | Accepted |
| 0003 | Tunnel media through the server rather than client-direct | Accepted |
| 0004 | Relay codec frames, not PCM or re-encoded audio | Accepted |
| 0005 | Sync via shared clock, fixed presentation delay, rate trim | Accepted |
| 0006 | Carry media over the Minecraft connection, behind `MediaTransport` | Accepted |
| 0007 | Integrate via vanilla `SoundEngine` using `SoundInstance` **+ Mixin** | Accepted, **amended** |
| 0008 | Downmix to mono for positional playback | Accepted |
| 0009 | Hand-rolled HTTP/ICY client instead of a JDK HTTP client | Accepted |
| 0010 | JLayer via Jar-in-Jar, JAADec shaded, STB Vorbis reused | Accepted |
| 0011 | Default-deny egress allowlist on the server | Accepted |
| 0012 | Call the product 4M, keep `mmmm` as the mod id | Accepted |

**On naming (ADR-0012).** The product is **4M**; the mod id, resource namespace and Java package root
are all **`mmmm`**. Forge validates mod ids against `^[a-z][a-z0-9_]{1,63}$` and throws
`InvalidModFileException` on a leading digit, so `4m` cannot be one — and Java packages cannot start
with a digit either. Minecraft resource namespaces *do* accept `4m`, which makes it a trap: the assets
validate cleanly and only FML objects. `mmmm` is the four Ms of *Minecraft Multi Media Mod*, so the
identifier and the brand say the same thing. Rule: anything a registry, loader or compiler parses is
`mmmm`; anything a human reads — display name, thread names, `User-Agent`, pack description — is 4M.

**Amendment pending on ADR-0007** (§7.3). The decision stands; the mechanism is simpler than recorded.
Forge patches `SoundInstance.getStream(SoundBufferLibrary, Sound, boolean)` and `SoundEngine.play`
calls it, so overriding that method supplies our `AudioStream` with no Mixin at all. The ADR should
gain a *More Information* note recording this, keeping the Mixin and the access-transformer routes as
documented fallbacks — the Mixin config and build wiring stay in place, empty, for exactly that
reason, and because NeoForge still has to be confirmed (§9 step 9).
