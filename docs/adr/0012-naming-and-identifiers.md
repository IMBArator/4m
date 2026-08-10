---
status: accepted
date: 2026-08-10
---

# Call the product 4M, keep `mmmm` as the mod id

## Context and Problem Statement

The project was renamed to **M&N's Minecraft Multi Media Mod — 4M**. The obvious follow-through is to
make the mod id `4m`, and the rename was begun that way: `modId=4m`, `assets/4m/`, `data/4m/`,
`4m.mixins.json`, every `ResourceLocation` rewritten to `4m:radio`.

**That mod does not load.** Forge validates mod ids against

```
^[a-z][a-z0-9_]{1,63}$
```

in `net.minecraftforge.fml.loading.moddiscovery.ModInfo`, and a failure is not a warning — it logs
`Invalid modId found in file {} - {} does not match the standard: {}` and throws
`InvalidModFileException`. A leading digit is rejected outright. The neighbouring `namespace`
override field uses `^[a-z][a-z0-9_.-]{1,63}$` and rejects it too.

What makes this worth a record rather than a code comment is that **the failure is invisible right up
until it isn't**. Minecraft's own `ResourceLocation` accepts `[a-z0-9_.-]`, so `4m:radio`, `assets/4m/`
and `data/4m/` are all perfectly legal and every asset, model, recipe and loot table validates
cleanly. Only FML objects, and only when it reads `mods.toml`. Java packages have the same
leading-digit restriction as mod ids, so `package 4m;` never compiles either — meaning the Java side
would have had to diverge from the resource side regardless.

## Decision Drivers

* The mod must actually load — this is not negotiable and everything else is
* One identity to reason about, rather than a mod id, a namespace and a package that differ
* The 4M branding should be what players and server operators see
* Renames are expensive and touch every resource path; do this once

## Considered Options

* **`4m` everywhere** — what was started. Impossible: FML rejects it.
* **`m4` or `fourm` everywhere** — legal, single identity, but renames every resource path and Java
  package for branding that `modName` already carries.
* **Split identity** — mod id and package `m4`, resource namespace `4m`, so in-game ids read
  `4m:radio`. Legal, and gives the wanted string where players see it.
* **Keep `mmmm`, rebrand only the display name.**

## Decision Outcome

Chosen option: **keep `mmmm` as the mod id, namespace and Java package root; the product name 4M
lives in `modName` and in free-form strings.**

`mmmm` is not a leftover — it is *literally the four Ms* of **M**inecraft **M**ulti **M**edia **M**od,
which is what "4M" abbreviates. So the technical identifier and the brand say the same thing in
different notations, and nothing has to be explained away.

The split-identity option was the runner-up and was rejected for a specific reason: two names that
are nearly the same and mean different things is exactly the shape of bug that survives review. A
constant pair of `MOD_ID = "m4"` and `NAMESPACE = "4m"` invites getting them the wrong way round in
some path that is only exercised by a datapack, or a config file, or a mixin refmap — places where
the mistake surfaces late and diagnoses badly.

### The convention, concretely

| Where | Value | Why |
|---|---|---|
| `mods.toml` `modId`, `@Mod(…)` | `mmmm` | FML regex; must be a valid mod id |
| Java package root, `modGroup` | `mmmm` | cannot start with a digit |
| Resource namespace — `assets/`, `data/`, every `ResourceLocation` | `mmmm` | must equal the mod id so registry entries and the mod agree |
| Mixin config file, refmap | `mmmm.mixins.json` | derived from `${modId}` in the build |
| Lang keys | `block.mmmm.radio` | derived from the namespace |
| `mods.toml` `displayName` | `M&N's Minecraft Multi Media Mod - 4M` | free text; the brand belongs here |
| Jar name | `mmmm-forge-<mcversion>-<version>.jar` | derived from `${modId}` |
| Thread names, HTTP `User-Agent`, `pack.mcmeta` description | `4M` | free-form; these identify the *product*, not the mod id, and are what a station operator or someone reading `jstack` actually sees |

The rule that generalises: **anything a registry, a loader or a compiler parses uses `mmmm`;
anything a human reads uses 4M.**

### Consequences

* Good, because the mod loads, which the started rename would not have.
* Good, because there is exactly one identifier, so no path can use the wrong one.
* Good, because it reverted cleanly — the rename had not reached the Java packages.
* Bad, because `mmmm` looks like a placeholder to anyone who has not read this record. Mitigated by
  the comment on `Mmmm.MOD_ID`, which states the constraint and points here.
* Bad, because in-game ids read `mmmm:radio` rather than `4m:radio`. Cosmetic, and visible mostly to
  people typing `/give`.
* Neutral: changing the mod id later is possible but expensive — it breaks every placed block in
  every existing world, since block ids are stored by namespace. Worth knowing before anyone
  reconsiders.

### Confirmation

* `modId` in `gradle.properties` matches `^[a-z][a-z0-9_]{1,63}$`.
* `runClient` reaches the main menu and the mod appears in the Mods list — a rejected mod id fails
  loudly during mod discovery, before any of our code runs.
* `/give @s mmmm:radio` yields the block, confirming registry namespace and resource namespace agree.
* No `4m` remains as an identifier: `grep -rn '4m' --exclude-dir=build` returns only free-form
  product strings and Gradle's own `-Xmx64m`.

## More Information

The regexes were read out of `fmlloader-1.20.1-47.4.0.jar` rather than from documentation, because
this rule is not stated in the Forge docs where anyone would look for it before choosing a name.

If the mod id ever does need to change, note that it is not only a rename: block and item ids are
persisted into save files by namespace, so every existing world would lose its radios unless a
datafixer maps the old namespace to the new one.
