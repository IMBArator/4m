# Architecture Decision Records

[MADR 4.0.0](https://adr.github.io/madr/). One file per decision, numbered, never renumbered.
Superseding a decision means a new record that references the old one; the old file stays.

| # | Decision | Status |
|---|---|---|
| [0001](0001-target-minecraft-1-20-1.md) | Target Minecraft 1.20.1 | Accepted |
| [0002](0002-shared-source-directory.md) | Ship Forge and NeoForge from a shared source directory | Accepted |
| [0003](0003-server-relay.md) | Tunnel media through the server rather than client-direct | Accepted |
| [0004](0004-relay-codec-frames.md) | Relay codec frames, not PCM or re-encoded audio | Accepted |
| [0005](0005-sync-clock-delay-rate-trim.md) | Sync via shared clock, fixed presentation delay, rate trim | Accepted |
| [0006](0006-media-over-minecraft-connection.md) | Carry media over the Minecraft connection, behind `MediaTransport` | Accepted |
| [0007](0007-vanilla-soundengine-mixin.md) | Integrate via vanilla `SoundEngine` using `SoundInstance` + Mixin | Accepted |
| [0008](0008-mono-downmix.md) | Downmix to mono for positional playback | Accepted |
| [0009](0009-hand-rolled-icy-http-client.md) | Hand-rolled HTTP/ICY client instead of a JDK HTTP client | Accepted |
| [0010](0010-decoder-libraries-and-packaging.md) | JLayer via Jar-in-Jar, JAADec shaded, STB Vorbis reused | Accepted |
| [0011](0011-egress-allowlist.md) | Default-deny egress allowlist on the server | Accepted |

## Reading order

0003 is the keystone — it decides that the server relays, and 0004, 0005, 0006 and 0011 all follow
from it. 0007, 0008 and 0009 are independent implementation decisions that would hold under any
transport choice.
