---
status: accepted
date: 2026-08-08
---

# Carry media over the Minecraft connection, behind `MediaTransport`

## Context and Problem Statement

Relayed media needs a path from server to client. The Minecraft connection is already there and
already authenticated; a side channel would be faster but needs its own port.

**Video streaming is planned**, at 30–100× the bitrate of audio. That fact belongs in this decision
even though no video code is being written, because it is what makes the difference between a cheap
future change and an expensive one.

## Decision Drivers

* Zero configuration for server operators
* Works through firewalls and proxies that already pass Minecraft
* Authentication and encryption for free
* Headroom for video
* Gameplay must not suffer

## Considered Options

* Minecraft connection via `SimpleChannel`
* Dedicated TCP side channel on its own port
* Embedded HTTP server, client pulls HLS
* UDP

## Decision Outcome

Chosen option: **the Minecraft connection for v1, with every media send routed through a
`MediaTransport` interface rather than calling `SimpleChannel` directly.**

At audio bitrates the game connection is entirely adequate and costs operators nothing — no port to
open, no firewall rule, no configuration. A side channel today would be pure friction for no
benefit.

But a side channel is very likely the right answer for video, and retrofitting an interface through
a codebase that assumed `SimpleChannel` everywhere is exactly the kind of change that spreads into
every file it touches. One interface, adopted now while there is nothing to migrate, keeps that
decision cheap.

### Consequences

* Good, because operators configure nothing.
* Good, because authentication and encryption are inherited from the game connection.
* Good, because it works wherever Minecraft already works.
* Good, because the video transport decision stays a localised change.
* Bad, because media shares a TCP socket with gameplay. Head-of-line blocking means a media stall
  can stutter gameplay. Bounded by pacing (send every 2 ticks, not every tick) and by transmitting
  only to players in earshot.
* Bad, because TCP retransmission is the wrong trade for realtime media — a late packet is worth
  less than no packet. Tolerable at 16 KB/s; not at 2 Mbit/s.
* Neutral: one interface indirection with a single implementation. This will look like
  over-engineering right up until video arrives.

### Confirmation

* No measurable tick-time impact with 20 simulated listeners.
* All media sends go through `MediaTransport`. Enforced by `:core` having no access to
  `SimpleChannel` at all — the dependency direction makes the violation impossible rather than
  merely discouraged.

## More Information

Open questions video will have to answer, deliberately not answered here:

* Side channel on its own port, or an embedded HTTP server the client pulls HLS from? Both carry
  real operator cost — firewall rules, port configuration, possibly TLS.
* Software H.264 in pure Java is not viable at useful resolutions, so video likely means native
  bindings (libvlc, JavaCV/FFmpeg) with a large per-platform payload. The `Decoder` interface must
  therefore not assume pure Java.

These are decisions to make with video actually in front of you, not to guess at now.
