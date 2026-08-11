# 4M — M&N's Minecraft Multi Media Mod

Synchronised web radio for Minecraft **1.20.1**, on **Forge** and **NeoForge**.

A placeable radio block streams a live internet station with positional 3D audio. The stream is
**tunnelled through the server**, so every player hears the same audio at the same instant rather
than each client landing at a different point in the station's buffer.

Video streaming is planned. The core is built media-neutral for it — see
[ADR-0006](docs/adr/0006-media-over-minecraft-connection.md) and `docs/adr/`.

> **Status: working, unfinished.**
>
> **Playing.** A craftable, placeable radio block streams MP3 stations through the server and out
> of the vanilla sound engine as positional mono audio. Right-clicking opens a panel with
> play/stop, a station picker, and a volume slider whose value lives on the block and reaches every
> client. Operators can enter a custom station URL; everyone else sees the field disabled.
> `/mmmm stop [radius]` switches off every radio in range.
>
> **Verified.** The `:core` unit suite, the server pipeline validated against live MP3, AAC and Ogg
> Vorbis stations, and the produced jar booted on a real dedicated server by
> `tools/check-server-jar.sh`. A 30-minute single-client soak held drift inside ±6 ms with no
> underruns.
>
> **Not done.** The measurement the project exists for — two clients, two machines, one server,
> both audible — has not been run; a single machine cannot exercise the rate trim, because the two
> clients share one sound card and therefore one clock. Client-side **AAC and Ogg Vorbis decode**
> are not wired up, so only MP3 stations play. Video has not started.
>
> **NeoForge is temporarily out of the build.** `neoforge/build.gradle` targets NeoGradle 7, which
> is 1.20.2+; 1.20.1 needs NeoGradle 6 or ModDevGradle's legacy plugin, and the project could not
> even configure. It is commented out of `settings.gradle`, which explains the details. The mod
> source needs no change for it — NeoForge 47.1.106 is still the Forge 47.1 fork.

## Verified so far

The server-side pipeline (transport → sniffing → framing → timeline) runs end to end against real
stations. `StreamProbe` compares the media time the parser derives against wall-clock time; for a
live stream those must agree, and a persistent gap means the frame arithmetic is wrong — a bug that
would otherwise surface only as slow drift between players, hours later, looking like a clock fault.

| Station | Codec | Steady-state media/wall | Unframed bytes |
|---|---|---|---|
| SomaFM Groove Salad (via `.pls`) | MP3 44.1 kHz stereo | 0.995 | 0.0 % |
| SomaFM Groove Salad | HE-AAC 22.05 kHz stereo | 0.994 | 0.0 % |
| Radio Paradise | Ogg Vorbis 44.1 kHz stereo | 0.986 | 0.6 % |

Two findings from that testing worth knowing before touching this code:

- **Icecast bursts on connect.** A new listener is handed the whole server buffer at once —
  measured between 12 s and 33 s of audio inside the first wall second — before throttling to
  realtime. Anything assuming bytes arrive at the stream bitrate has to expect it.
- **A stream's first granule is often not zero.** Icecast replays cached Ogg header pages *with the
  beginning-of-stream flag* and then splices in the live feed wherever the encoder is, so the BOS
  flag does not mean the audio starts at zero. Reading it as absolute put the second frame two
  minutes after the first. See `OggFrameParser.ANCHOR_THRESHOLD_SECONDS`.

Client-side playback is instrumented rather than eyeballed. With `debug.syncReadout` enabled, the
radio panel carries a line reading `drift ±… · buf … · trim …ppm · rtt … · resync …`, and
`debug.syncLog` writes the same figures to the client log once a second. Both are in the client
config. Every sync defect found so far was found by reading that line, not by listening.

---

## Building

Everything below needs **JDK 17** on `JAVA_HOME`. Minecraft 1.20.1 targets Java 17, and
ForgeGradle 6 is not reliable on 21+. See [Toolchain setup](#toolchain-setup) — a plain `apt
install` cannot supply it on Debian 13.

```bash
export JAVA_HOME=~/.jdks/$(ls ~/.jdks | grep jdk-17)
```

### The fast loop

`:core` is plain Java and needs none of the Minecraft toolchain, so the media pipeline and the sync
algorithm can be compiled and tested with a JDK alone — no Gradle, no Forge, seconds instead of
minutes:

```bash
./tools/build-core.sh                                              # compile + run the tests
./tools/build-core.sh probe https://somafm.com/groovesalad.pls 20 out.mp3
```

`probe` runs `StreamProbe` against a real station and prints the media/wall table above.

### The full build

```bash
./gradlew :core:test        # no Minecraft toolchain needed
./gradlew :forge:build      # includes the client/server split check
./gradlew :forge:runClient
```

`:neoforge` is commented out of `settings.gradle` until its build file moves to a toolchain that
supports 1.20.1 — until then `./gradlew build` covers Forge only. Once it is back, CI must run both
loaders, not just one ([ADR-0002](docs/adr/0002-shared-source-directory.md)).

### Before handing anyone a jar

```bash
./tools/check-server-jar.sh   # boots the PRODUCED jar on a real Forge dedicated server
```

**A green build does not mean a working jar.** `runClient` runs from the source set, so it never
loads the jar at all — and it is a *client*, so Forge's dist checks never refuse anything. Both
failure modes have actually shipped here: a jar missing all of `:core`, and a mod that crashed
every dedicated server on boot. A green `:forge:build` coexisted happily with each.

Run this script after any change to packaging, to `MmmmForge`, or to the client/server split. It
boots the artifact, places a radio, and waits for the relay to reach `PLAYING`.

Install **`mmmm-forge-1.20.1-<version>.jar`**. The `-slim` one has no nested libraries — JLayer is
missing and MP3 playback dies at the first decode — and is not for use.

### Two clients and a server

The point of the mod is that two people hear the same instant, which takes three processes:

```bash
./gradlew :forge:runServer     # dedicated server, forge/run-server/
./gradlew :forge:runClient     # player "Dev",  forge/run/
./gradlew :forge:runClient2    # player "Dev2", forge/run-client2/
```

Connect both clients to `127.0.0.1`. Separate working directories are required, not tidiness: two
clients sharing one directory fight over `options.txt`, the log and the session lock.

`forge/run-server/` ships its `eula.txt`, `server.properties` and `ops.json` in git on purpose.
The server runs `online-mode=false`, and an offline-mode UUID is derived from the username alone,
so the committed ops entries are stable — but they are also **name- and case-specific**. Rename a
client, or set `online-mode=true`, and both entries stop matching silently: you are simply not an
operator any more, and setting a custom station stops working for no visible reason.

On one machine this measures the protocol, not the sync. Both clients share a sound card and
therefore one clock, so the rate trim — which exists to cancel *per-machine* clock error — is never
exercised. Necessary, not sufficient.

---

## Toolchain setup

Debian 13 cannot supply this toolchain through `apt`, even with root:

- `/usr/lib/jvm/java-17-openjdk-amd64` and `java-21-openjdk-amd64` are **JRE-only** — no `javac`.
- There is **no `openjdk-17-jdk` package**, only 21. Minecraft 1.20.1 wants 17.
- The packaged `gradle` is **4.4.1** (2018). ForgeGradle 6 needs 8.x.

Install JDK 17 from Adoptium instead — no root required:

```bash
mkdir -p ~/.jdks && cd ~/.jdks
curl -L -o jdk17.tar.gz \
  'https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse'
tar xzf jdk17.tar.gz && rm jdk17.tar.gz
export JAVA_HOME=~/.jdks/$(ls ~/.jdks | grep jdk-17)
export PATH="$JAVA_HOME/bin:$PATH"
```

Gradle needs no install: the wrapper is committed, so `./gradlew` is self-sufficient once a JDK 17
is on `JAVA_HOME`. (Regenerating the wrapper from scratch would need a one-off Gradle 8.x, which
can be unzipped into `~/.jdks` the same way.)

Verify:

```bash
java -version   # 17.x
./gradlew :core:test
```

---

## Layout

```
core/      Pure Java. No Minecraft, no loader. The media pipeline and the sync
           algorithm live here, and they are unit-testable without the game.
  source/    transport from the origin station      (server side)
  frame/     container/frame parsing → timeline     (server side)
  codec/     decoders                               (client side)
  transport/ relay wire abstraction                 (both)
  sync/      clock filter + drift control           (both)
  security/  egress guard                           (server side)
  audio/     PCM ring buffer                        (client side)

common/    Minecraft code shared by both loaders. A SOURCE DIRECTORY pulled into
           each loader project, not a Gradle subproject.

forge/     Forge entry point, registries, networking, config.
neoforge/  Same, for NeoForge.

tools/     build-core.sh (fast :core loop), check-server-jar.sh (jar smoke test).
docs/adr/  Architecture decisions, MADR format.
```

---

## Dependencies

### Client only

Decoders never run on the server (see [ADR-0004](docs/adr/0004-relay-codec-frames.md)), so they are
declared `compileOnly` in `:core` and put on the client runtime classpath by the loader modules.

| Dependency | Coordinates | Purpose | Licence | How it ships |
|---|---|---|---|---|
| JLayer | `javazoom:jlayer:1.0.1` | MP3 decode | **LGPL-2.1** | **Jar-in-Jar, unmodified and unrelocated.** Relocating it would undermine the LGPL's separate-replaceability condition. |
| JAADec | `net.sourceforge.jaadec:jaad:0.8.6` | AAC / HE-AAC decode | Public Domain | Shaded and relocated to `mmmm.shaded.jaad` — **not yet wired**: the coordinates do not resolve |
| STB Vorbis | — | Ogg Vorbis decode | — | **No dependency** — would reuse Minecraft's own `OggAudioStream` via LWJGL. Not yet wired. |
| Mixin | provided by loader | Hooks `SoundBufferLibrary#getStream` | — | Already present on both loaders at 1.20.1 |

Only MP3 plays today. The two packaging paths are deliberate and not interchangeable — see
[ADR-0010](docs/adr/0010-decoder-libraries-and-packaging.md).

### Server

**None.** The server parses MP3/ADTS/Ogg frame *headers* by hand in `core/frame` to build the
timeline, and relays the encoded bytes untouched. It never decodes and never loads an audio library.
`:core:checkServerSideHasNoCodecDeps` fails the build if that ever stops being true.

### Build-time only

| Dependency | Version | Purpose |
|---|---|---|
| ForgeGradle | `[6.0.24, 6.2)` | `:forge` toolchain |
| NeoGradle (userdev) | `7.0.145` | `:neoforge` toolchain |
| Parchment | `2023.09.03-1.20.1` | Readable parameter names |
| Mixin Gradle | `0.7.+` | Refmap generation |
| Shadow | `8.1.1` | Relocating JAADec |
| JUnit Jupiter | `5.10.2` | `:core` tests |

### Platform

| | Version |
|---|---|
| Minecraft | 1.20.1 |
| Forge | 47.4.0 |
| NeoForge | 47.1.106 |

NeoForge 47.1.106 is the fork point from Forge 47.1, which is why one shared source directory
covers both ([ADR-0002](docs/adr/0002-shared-source-directory.md)).

---

## Architecture decisions

[`docs/adr/`](docs/adr/) carries its own index and reading order. The records are binding, not
historical. The load-bearing ones:

| # | Decision |
|---|---|
| [0003](docs/adr/0003-server-relay.md) | Tunnel media through the server rather than client-direct — the only design that can sync at all |
| [0005](docs/adr/0005-sync-clock-delay-rate-trim.md) | Shared clock + fixed presentation delay + inaudible rate trim |
| [0006](docs/adr/0006-media-over-minecraft-connection.md) | Minecraft connection for audio, behind an interface, because video will not fit |
| [0007](docs/adr/0007-vanilla-soundengine-mixin.md) | Let vanilla own the audio channel; supply the stream via one Mixin |
| [0011](docs/adr/0011-egress-allowlist.md) | Default-deny egress allowlist — the relay makes the *server* fetch player-supplied URLs |

## Commands

```
/mmmm stop [radius]      switch off every playing radio within radius (default 64) — operators only
```

One command on purpose. Right-clicking a radio opens a panel that covers everything about *that*
radio, so the only gap worth filling from chat is the one the panel cannot: several radios playing
at once, not all of them where you can reach them.

## Operator note on re-streaming

The server holds **one** connection to a station and serves many players from it. The station
therefore counts one listener regardless of how many people are actually listening, which affects
its royalty reporting and may breach its terms of service. This is not something the mod can
resolve in code. Ship only stations that permit it, and see
[ADR-0003](docs/adr/0003-server-relay.md).

## Licence

See `modLicense` in `gradle.properties`. Bundled third-party licences are listed under
[Dependencies](#dependencies); JLayer's LGPL-2.1 terms are the reason it ships nested and
unmodified rather than shaded.
