---
status: accepted
date: 2026-08-08
---

# Sync via shared clock, fixed presentation delay, rate trim

## Context and Problem Statement

With identical frames reaching every client (ADR-0003, ADR-0004), clients must still *render* each
frame at the same instant despite differing latency, jitter, and audio hardware clock rates.

The last of those is the one that is easy to overlook: consumer sound cards drift by tens to
hundreds of parts per million. Two clients started perfectly in step will separate audibly over an
evening with no network fault involved at all.

## Decision Drivers

* Corrections must be inaudible
* Robustness to network jitter
* Must generalise to audio/video sync later

## Considered Options

1. Start everyone together and hope.
2. Shared clock + presentation delay, correcting drift by dropping and duplicating samples.
3. Shared clock + presentation delay, correcting drift by resampling — a rate trim.

## Decision Outcome

Chosen option: **3, rate trim**.

Option 1 ignores hardware clock drift and diverges over minutes. Option 2 corrects but clicks
audibly at every correction, and corrections are frequent.

A rate trim of ±0.1 % is about 1.7 cents of pitch — inaudible. `AL_PITCH` already resamples, and
`SoundEngine.tick()` re-reads `instance.getPitch()` every tick and pushes it to the channel, so
`RadioSoundInstance.getPitch()` returning `1.0f * rateTrim` **is** the entire control surface. No
extra hooks, no custom mixing.

The control law:

| Drift | Action |
|---|---|
| < 10 ms | nothing — deadband, prevents hunting |
| 10–250 ms | rate trim, up to ±0.1 %, slow feedback loop |
| > 250 ms | hard resync: flush the jitter buffer, jump to the correct PTS |

The clock is NTP-style over the existing connection. **Keep the minimum-RTT sample** over a sliding
window rather than averaging — low-RTT samples carry the least queuing noise, and that matters
doubly on a connection shared with game traffic.

### Consequences

* Good, because corrections are inaudible in normal operation.
* Good, because the hard-resync path above 250 ms also covers pause/resume and severe stalls, which
  would otherwise each need their own handling.
* Good, because this is the design Snapcast and AirPlay use, so it is known to work rather than
  merely plausible.
* Good, because it generalises to video unchanged: audio becomes the master clock and video frames
  are dropped or repeated against it.
* Bad, because it introduces a control loop with constants that need in-game tuning. A sync-health
  readout in the GUI is part of the work, not an extra.
* Bad, because it depends on OpenAL's resampler quality. If that disappoints, software resampling
  replaces it behind the same interface.
* Neutral: a fixed 3 s presentation delay means 3 s startup latency. Invisible for radio, and the
  backlog ring removes it for clients joining an already-playing session.

### Confirmation

* Simulated-clock unit tests: settles inside the deadband without hunting; a 500 ms step triggers
  exactly one hard resync.
* Clock filter tests with injected jitter and path asymmetry.
* 30 minutes of playback with no accumulating drift. A steady one-directional creep indicates a
  timeline bug in `FrameParser`, **not** a clock bug — the two present identically and this is the
  first thing to check.
* `tc netem` latency injection on one client: re-converges without an audible jump.
