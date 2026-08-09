---
status: accepted
date: 2026-08-08
---

# Relay codec frames, not PCM or re-encoded audio

## Context and Problem Statement

Given ADR-0003, what does the server actually put on the wire?

## Decision Drivers

* Bandwidth
* Server CPU — a dedicated Minecraft server has none to spare, and stalling the tick loop is
  immediately visible to players
* Audio quality
* Dependency footprint on the server

## Considered Options

* Decode to raw PCM and relay that
* Re-encode to a single uniform low-bitrate codec
* Relay the original codec frames unchanged

## Decision Outcome

Chosen option: **relay the original frames**.

PCM is about 1.4 Mbit/s per client, roughly 11× the source bitrate — disqualifying on its own.
Re-encoding costs CPU per session, adds an encoder dependency, and loses quality to buy uniformity
that nothing needs. Passing frames through costs exactly the origin bitrate and zero decode work.

The server still needs a timeline, but that comes from parsing frame *headers* (ADR-0005 depends on
it), which is header arithmetic rather than decoding.

### Consequences

* Good, because the server needs no codec library on its classpath at all.
* Good, because quality is bit-exact — no generation loss.
* Good, because the server stays headless: no LWJGL, no native audio, nothing that could fail on a
  container without sound hardware.
* Bad, because every client must carry decoders for every supported format, which grows the client
  jar.
* Bad, because a client meeting an unsupported codec fails, where a re-encoding server would have
  normalised it for everyone.
* Neutral: header parsing must be written by hand for each container. Small, and it is exactly the
  code the timeline needs anyway.

### Confirmation

Server-side profiling shows negligible CPU per session. Enforced structurally: decoders are
`compileOnly` in `:core`, and `:core:checkServerSideHasNoCodecDeps` fails the build if
`core/source`, `core/frame` or `core/transport` ever import a codec library.
