---
status: accepted
date: 2026-08-08
---

# JLayer via Jar-in-Jar, JAADec shaded, STB Vorbis reused

## Context and Problem Statement

Clients need MP3, Ogg Vorbis and AAC decoders. Their licences differ, and mod jars are redistributed
widely through CurseForge and Modrinth — so how each library is packaged is a licence-compliance
question, not only a build one.

## Decision Drivers

* Compliant redistribution
* Client jar size
* Avoiding classpath collisions with other mods bundling the same libraries

## Decision Outcome

**Ogg Vorbis — no dependency.** Reuse Minecraft's own `OggAudioStream`, which wraps STB Vorbis via
LWJGL and is already on the client classpath.

**MP3 — JLayer `javazoom:jlayer:1.0.1`, LGPL-2.1 — bundled via Jar-in-Jar, unmodified and
unrelocated.** The LGPL permits distribution alongside proprietary work provided the library remains
separately replaceable. Shading and relocating it would undermine exactly that condition, so the
usual instinct to relocate everything is wrong here.

**AAC / HE-AAC — JAADec `net.sourceforge.jaadec:jaad:0.8.6`, Public Domain — shaded and relocated**
to `mmmm.shaded.jaad`. No licence constraint, and relocation avoids colliding with any
other mod that bundles it.

### Consequences

* Good, because redistribution is compliant without the operator or the user having to do anything.
* Good, because the footprint is small — roughly 100 KB for JLayer — and Ogg costs nothing at all.
* Good, because relocating JAADec removes a class of hard-to-diagnose conflicts.
* Bad, because the build carries two different packaging paths for two dependencies. Unavoidable
  given the licences; the comments in `forge/build.gradle` say why, so nobody "simplifies" them into
  one.
* Neutral: JLayer is unmaintained. It is stable and format-complete, but no upstream fixes are
  coming.
* Neutral: if chained-Ogg handling in `OggAudioStream` disappoints, JOrbis (BSD) is the fallback and
  would be shaded like JAADec.

### Confirmation

Licence texts included in the jar. `jar tf` shows JLayer nested and unrelocated, and JAADec
relocated under `mmmm.shaded`.
