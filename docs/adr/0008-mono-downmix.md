---
status: accepted
date: 2026-08-08
---

# Downmix to mono for positional playback

## Context and Problem Statement

Radio streams are stereo. The radio block is meant to be positional — audible in 3D, attenuating
with distance.

These two facts are in direct conflict, and the conflict is not obvious from either one.

## Decision Drivers

* Positional audio is the feature; a block you hear identically from 200 blocks away is not a radio
  block
* Some players will prefer fidelity

## Considered Options

* Play the stereo stream as-is
* Downmix to mono
* Downmix to mono, with an opt-in stereo mode at close range

## Decision Outcome

Chosen option: **downmix to mono, with an opt-in `stereoWhenClose`**.

**OpenAL applies 3D positioning only to mono sources.** A stereo buffer plays flat, at full volume,
regardless of where the listener stands. Playing the stream as-is does not degrade positioning; it
silently removes it. Vanilla music discs are mono for exactly this reason.

`stereoWhenClose` swaps a block to a non-attenuated stereo channel within a configured radius, for
players who would rather have the stereo image than the placement.

### Consequences

* Good, because positioning and attenuation work as designed.
* Good, because it halves the decoded PCM held in the ring buffer.
* Bad, because the stereo image is lost by default. Mitigated by the config option.
* Neutral: the downmix is an average of two channels, with no measurable cost.

### Confirmation

Walking away from a playing block attenuates smoothly.

**If it does not attenuate, the downmix has regressed.** That is the entire failure signature — the
audio still plays, nothing errors, and the only symptom is that distance stops mattering. Worth
knowing before spending an hour on it.
