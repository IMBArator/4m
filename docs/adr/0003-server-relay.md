---
status: accepted
date: 2026-08-08
---

# Tunnel media through the server rather than client-direct

## Context and Problem Statement

The mod must play the same live stream on many clients such that all hear the same audio at the same
instant. A live HTTP stream carries no presentation timestamps, and every new listener connection
begins at a different point in the origin's encoder buffer.

## Decision Drivers

* Sync fidelity — this is the primary requirement, not a nice-to-have
* Server bandwidth
* Legal exposure of re-streaming
* Load placed on the origin station

## Considered Options

1. **Client-direct** — the server broadcasts control state only; each client opens its own
   connection to the station.
2. **Server relay** — the server holds one upstream connection, frames the bytes, and tunnels them
   to clients.
3. **Hybrid** — relay until a bandwidth cap is reached, then fall back to client-direct.

## Decision Outcome

Chosen option: **server relay**, because it is the only option that can satisfy the requirement at
all.

Sync requires two things: identical bytes and a common clock. Option 1 provides neither. Its 1–5 s
inter-client spread is not merely large, it is **uncorrectable**: for a live stream you can only
skip forward or insert silence, and there is no timestamp in the content to align against. Content
alignment would mean cross-correlating decoded audio between clients, which is far out of proportion
to the problem.

### Consequences

* Good, because sub-50 ms sync becomes achievable (ADR-0005).
* Good, because the origin serves one connection instead of N.
* Good, because clients make no outbound connections at all, which removes client-side request
  forgery as a category.
* Bad, because it costs roughly 16 KB/s per listening player at 128 kbps on the server's uplink,
  sharing the game's TCP connection. Mitigated by transmitting only to players in earshot, but it is
  a hard ceiling for video (ADR-0006).
* Bad, because the server re-streams. The station counts one listener while many people listen,
  which affects its royalty reporting and may breach its terms. **Not resolvable in code** —
  surfaced in the README and config docs, and the shipped station list should contain only stations
  that permit it.
* Bad, because it concentrates request-forgery risk on the server, in a cloud environment where it
  is considerably more dangerous than on a player's desktop (ADR-0011).

### Confirmation

Cross-correlation of two clients' recorded output under 50 ms, and a 30-minute run with no
accumulating drift.

## More Information

This reverses an initial client-direct design, changed during planning before any code was written.
The rejected option is recorded here rather than dropped because "why not just let each client
connect directly?" is the first question anyone asks of this architecture, and the answer — a live
stream has no timestamp to align against, so the spread cannot be corrected — is the entire reason
the relay exists.

Option 3 was considered and rejected for v1: it doubles the client code paths and produces a system
whose sync quality silently depends on server population. That is the worst available failure mode,
because it degrades exactly when the most people are listening. Revisit only if bandwidth proves
binding in practice.
