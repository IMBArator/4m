---
status: accepted
date: 2026-08-08
---

# Ship Forge and NeoForge from a shared source directory

## Context and Problem Statement

1.20.1 servers run both Forge and NeoForge. Supporting only one halves the potential audience;
supporting both naively means maintaining two copies of a codebase whose hard parts — the media
pipeline and the sync algorithm — have nothing to do with either loader.

## Decision Drivers

* Audience reach
* One source of truth for the complex parts
* Build simplicity; no tooling that has to be learned before any code can be written

## Considered Options

* Forge only
* Architectury multi-loader
* A shared `common/` **source directory** pulled into both loader projects
* A real `:common` Gradle project compiled against vanilla Minecraft

## Decision Outcome

Chosen option: **shared source directory**, because NeoForge 47.1 forked from Forge 47.1 over the
same patched Minecraft, so the divergent surface is only about 15%:

* `net.minecraftforge.*` ↔ `net.neoforged.neoforge.*` / `net.neoforged.fml.*`
* `DeferredRegister`, the `@Mod` entry class, `FMLJavaModLoadingContext` ↔ `ModLoadingContext`
* `SimpleChannel` construction (the `registerMessage` API itself is identical)
* `ForgeConfigSpec` ↔ `ModConfigSpec`
* Build plugin: ForgeGradle 6 ↔ NeoGradle 7

Architectury's abstraction layer earns its cost when bridging genuinely different loaders such as
Fabric and Forge. Here it would be ceremony around a package rename. A real `:common` project was
rejected because compiling against vanilla-only needs another toolchain, and buys nothing when both
consumers are 47.x.

Each loader project pulls the directory in:

```gradle
sourceSets.main.java.srcDir rootProject.file('common/src/main/java')
```

### Consequences

* Good, because there is exactly one copy of every non-trivial class.
* Good, because no extra tooling is required.
* Good, because a port to a genuinely different loader still has a clean seam at the `Platform`
  service interface.
* Bad, because `common/` compiles twice, and a change there can break one loader's build only —
  **CI must build both**, not just the one being developed against.
* Neutral: `mods.toml` is duplicated rather than templated. Deliberate — the two are expected to
  diverge as the loaders do.

### Confirmation

`:forge:build` and `:neoforge:build` both green in CI on every commit, and identical in-game
behaviour across the manual checks.
