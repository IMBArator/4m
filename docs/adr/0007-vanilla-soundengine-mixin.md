---
status: accepted
date: 2026-08-08
---

# Integrate via vanilla `SoundEngine` using `SoundInstance` + Mixin

## Context and Problem Statement

Decoded PCM has to reach OpenAL, positioned at a block, obeying the player's volume sliders and
pausing with the game. Vanilla only knows how to stream OGG files from resource packs.

## Decision Drivers

* Correct interaction with player audio settings
* Minimal surface area against a version we do not control
* Must expose a per-channel rate control for ADR-0005

## Considered Options

* Own an OpenAL context and sources directly via LWJGL
* Access-transform `SoundEngine.channelAccess` and drive channels manually
* Register a real streaming `SoundEvent` and Mixin `SoundBufferLibrary#getStream` to supply our own
  `AudioStream`

## Decision Outcome

Chosen option: **the Mixin**.

Vanilla then owns the channel lifecycle, and volume categories, distance attenuation, game-pause,
audio device reload and the source cap all work by construction rather than by reimplementation.

A private OpenAL context risks fighting Minecraft's own context across threads — `alcMakeContextCurrent`
is global in the default case, and getting this wrong produces failures that depend on thread
scheduling. The access-transformer route avoids that but means reimplementing every one of the
behaviours listed above by hand.

Mechanically:

1. Register `SoundEvent mmmm:radio_stream`, marked `"stream": true` in `sounds.json` with a tiny
   placeholder ogg so the registry resolves.
2. `RadioSoundInstance extends AbstractTickableSoundInstance`, positioned at the block,
   `SoundSource.RECORDS`, `LINEAR` attenuation. Override `resolve(SoundManager)` to build a `Sound`
   whose path is `mmmm:radio/<sessionId>/<blockId>`, making each playing block addressable.
3. `getSoundManager().play(instance)`; because the `Sound` streams, `SoundEngine` calls
   `SoundBufferLibrary.getStream(location, looping)`.
4. Mixin `@Inject(at = @At("HEAD"), cancellable = true)` on that method: for a `mmmm:radio/*`
   location, return a completed future wrapping our `AudioStream`.

### Consequences

* Good, because it is very little code for a great deal of correct behaviour.
* Good, because `getPitch()` gives ADR-0005's rate trim its control surface at zero cost — this
  approach is what makes that design practical.
* Bad, because a Mixin into a vanilla method is version-fragile and may conflict with other sound
  mods (Sound Physics, audio overhauls). Mitigated by narrowing the injection to `mmmm:radio/*`
  locations and cancelling nothing else.
* Neutral: requires a placeholder ogg and a `sounds.json` entry.

If the Mixin proves brittle in the field, the fallback is the access-transformer route:
`channelAccess.createHandle(Library.Pool.STREAMING)` and `channel.attachBufferStream(...)`, at the
cost of owning volume, pause and reload handling.

### Confirmation

Volume sliders, attenuation, ESC-pause and `F3+T` reload all behave; smoke test alongside Sound
Physics Remastered.

## More Information

`Channel.updateStream()` treats a **short or empty** `read()` as end-of-stream and stops the sound.
Our adapter must return exactly the requested number of bytes, filling with silence on underrun.
Underruns are routine on live radio, so this single detail is the difference between "the radio
works" and "the radio dies at the first network hiccup".
