# mmmm — Multimedia Minecraft Mod

Synchronised web radio for Minecraft **1.20.1**, on **Forge** and **NeoForge**.

A placeable radio block streams a live internet station with positional 3D audio. The stream is
**tunnelled through the server**, so every player hears the same audio at the same instant rather
than each client landing at a different point in the station's buffer.

Video streaming is planned. The core is built media-neutral for it — see
[ADR-0006](docs/adr/0006-media-over-minecraft-connection.md) and `docs/adr/`.

> **Status: early development.** The `:core` pipeline is built and verified headlessly — 123 unit
> tests, and the server-side path validated against live MP3, AAC and Ogg Vorbis stations.
>
> The Forge module now builds and loads in-game: there is a craftable, placeable **radio block**
> (model ported from a 2022 Forge 1.18.2 prototype). It is furniture — **no block entity and no
> audio yet**. That is the next milestone.
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

---

## Build prerequisites

The build needs a **JDK 17** and **Gradle 8.x**. Neither is currently installed on this machine —
see [Toolchain setup](#toolchain-setup) below, which also explains why `apt` alone will not do it.

| Tool | Version | Why |
|---|---|---|
| JDK | **17** | Minecraft 1.20.1 targets Java 17. ForgeGradle 6 is not reliable on 21+. |
| Gradle | **8.1.1+** | Required by ForgeGradle 6 / NeoGradle 7. Use the wrapper. |

## Runtime dependencies

### Client only

Decoders never run on the server (see [ADR-0004](docs/adr/0004-relay-codec-frames.md)), so they are
declared `compileOnly` in `:core` and put on the client runtime classpath by the loader modules.

| Dependency | Coordinates | Purpose | Licence | How it ships |
|---|---|---|---|---|
| JLayer | `javazoom:jlayer:1.0.1` | MP3 decode | **LGPL-2.1** | **Jar-in-Jar, unmodified and unrelocated.** Relocating it would undermine the LGPL's separate-replaceability condition. |
| JAADec | `net.sourceforge.jaadec:jaad:0.8.6` | AAC / HE-AAC decode | Public Domain | Shaded and relocated to `mmmm.shaded.jaad` |
| STB Vorbis | — | Ogg Vorbis decode | — | **No dependency.** Reuses Minecraft's own `OggAudioStream` via LWJGL. |
| Mixin | provided by loader | Hooks `SoundBufferLibrary#getStream` | — | Already present on both loaders at 1.20.1 |

The two packaging paths are deliberate and not interchangeable — see
[ADR-0010](docs/adr/0010-decoder-libraries-and-packaging.md).

### Server

**None.** The server parses MP3/ADTS/Ogg frame *headers* by hand in `core/frame` to build the
timeline, and relays the encoded bytes untouched. It never decodes and never loads an audio library.

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

## Toolchain setup

Neither a JDK nor a usable Gradle is installed here:

- `/usr/lib/jvm/java-17-openjdk-amd64` and `java-21-openjdk-amd64` are **JRE-only** — no `javac`.
- Debian 13 offers **no `openjdk-17-jdk` package**, only 21. Minecraft 1.20.1 wants 17.
- The `gradle` in apt is **4.4.1** (2018). ForgeGradle 6 needs 8.x.

So apt cannot supply this toolchain even with root. Install JDK 17 from Adoptium instead — no root
required:

```bash
mkdir -p ~/.jdks && cd ~/.jdks
curl -L -o jdk17.tar.gz \
  'https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse'
tar xzf jdk17.tar.gz && rm jdk17.tar.gz
export JAVA_HOME=~/.jdks/$(ls ~/.jdks | grep jdk-17)
export PATH="$JAVA_HOME/bin:$PATH"
```

Gradle needs no separate install once the wrapper is generated — but generating the wrapper needs
Gradle once. Bootstrap it without root:

```bash
cd ~/.jdks && curl -L -o gradle.zip https://services.gradle.org/distributions/gradle-8.8-bin.zip
unzip -q gradle.zip && rm gradle.zip
~/.jdks/gradle-8.8/bin/gradle wrapper --gradle-version 8.8   # run in the repo root
```

After that, `./gradlew` is self-sufficient and the one-off Gradle copy can be deleted.

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

docs/adr/  Architecture decisions, MADR format.
```

## Building

`:core` is plain Java and needs none of the Minecraft toolchain, so while the pipeline is the thing
under construction there is a fast loop that requires only a JDK 17:

```bash
export JAVA_HOME=~/.jdks/jdk-17...           # see Toolchain setup
./tools/build-core.sh                        # compile + 123 tests, no Gradle
./tools/build-core.sh probe https://somafm.com/groovesalad.pls 20 out.mp3
```

The full build, once the toolchain is in place:

```bash
./gradlew :core:test
./gradlew :forge:build
./gradlew :forge:runClient
```

`:neoforge` is commented out of `settings.gradle` until its build file is moved to a toolchain that
supports 1.20.1 — until then `./gradlew build` covers Forge only. Once it is back, CI must run both
loaders, not just one (ADR-0002).

## Architecture decisions

Eleven records in [`docs/adr/`](docs/adr/). The load-bearing ones:

| # | Decision |
|---|---|
| [0003](docs/adr/0003-server-relay.md) | Tunnel media through the server rather than client-direct — the only design that can sync at all |
| [0005](docs/adr/0005-sync-clock-delay-rate-trim.md) | Shared clock + fixed presentation delay + inaudible rate trim |
| [0006](docs/adr/0006-media-over-minecraft-connection.md) | Minecraft connection for audio, behind an interface, because video will not fit |
| [0007](docs/adr/0007-vanilla-soundengine-mixin.md) | Let vanilla own the audio channel; supply the stream via one Mixin |
| [0011](docs/adr/0011-egress-allowlist.md) | Default-deny egress allowlist — the relay makes the *server* fetch player-supplied URLs |

## Operator note on re-streaming

The server holds **one** connection to a station and serves many players from it. The station
therefore counts one listener regardless of how many people are actually listening, which affects
its royalty reporting and may breach its terms of service. This is not something the mod can
resolve in code. Ship only stations that permit it, and see
[ADR-0003](docs/adr/0003-server-relay.md).

## Licence

See `modLicense` in `gradle.properties`. Bundled third-party licences are listed under
[Runtime dependencies](#runtime-dependencies); JLayer's LGPL-2.1 terms are the reason it ships
nested and unmodified rather than shaded.
