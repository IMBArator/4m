---
status: accepted
date: 2026-08-08
---

# Target Minecraft 1.20.1

## Context and Problem Statement

Minecraft moved to year-based versioning in December 2025, and 26.1 shipped in March 2026. Which
version does 4M target first?

## Decision Drivers

* Reach of the installed modded base
* Stability of the modding API — effort should go into the hard audio problem, not into chasing a
  moving platform
* Availability of both target loaders

## Considered Options

* 1.20.1
* 26.1 (current)
* Both from the start

## Decision Outcome

Chosen option: **1.20.1**, because it remains by far the largest modded ecosystem (16 000+ mods) and
its API has been stable for years. The interesting risk in this project is the audio pipeline and
the sync algorithm; spending that risk budget on platform churn instead would be a poor trade.

Targeting both from the start was rejected as premature — there is no working mod to port yet.

### Consequences

* Good, because the API is mature and heavily documented, which matters for the unusual things this
  mod does to the sound engine.
* Good, because both Forge and NeoForge are available at this version (see ADR-0002).
* Bad, because 1.20.1 predates `CustomPacketPayload`; networking uses `SimpleChannel`, which is more
  boilerplate.
* Bad, because a future port to 26.x will need the networking layer rewritten. Contained: ADR-0002
  keeps networking inside the loader modules, so `:core` and `common/` are unaffected.

### Confirmation

`./gradlew :forge:runClient` and `:neoforge:runClient` both launch at 1.20.1.
