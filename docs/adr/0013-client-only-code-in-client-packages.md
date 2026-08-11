---
status: accepted
date: 2026-08-11
---

# Confine client-only code to `client` packages

## Context and Problem Statement

One jar ships to both dists. A dedicated server therefore has every client class on disk, and the
question is not whether the classes are *present* but whether the server ever *loads* one. Forge's
`RuntimeDistCleaner` throws when it does:

```
java.lang.RuntimeException: Attempted to load class net/minecraft/client/gui/screens/Screen
for invalid dist DEDICATED_SERVER
```

That is not a graceful degradation. It surfaces during mod construction, so the server dies on boot
with the mod named in the stack trace.

What makes this worth a record is **how little it takes to trigger, and how invisible the trigger
is in source.** A dedicated server *verifies* every class it loads, and verification resolves the
types it checks assignability against. So a method that passes a `RadioScreen` to
`setScreen(Screen)` forces both types to load — merely to prove the argument fits — even on a code
path that never runs.

Worse, the class this happens *in* is not the class it appears to be in:

```java
// In MmmmForge. The lambda body compiles into a synthetic method ON MmmmForge, so Screen and
// Minecraft land in MmmmForge's constant pool. The server loads MmmmForge. It dies.
RadioBlock.setScreenOpener(radio ->
        Minecraft.getInstance().setScreen(new RadioScreen(radio)));

// In MmmmForge. Compiles to an invokedynamic whose method handle names
// ForgeClientSetup.openRadioScreen(RadioBlockEntity) — no client type in MmmmForge at all.
RadioBlock.setScreenOpener(ForgeClientSetup::openRadioScreen);
```

Two lines that read as equivalent; one boots and one does not. This shipped.

And **no client run can catch it**, because the check is about the dist the class is loaded on, and
a client is the dist where every one of these classes is legal. `runClient` additionally runs from
the source set rather than the jar. A fully green `:forge:build` plus a working `runClient` was the
state of the world while every dedicated server crashed.

## Decision Drivers

* The failure is a boot crash, not a degraded feature — it has to be caught before shipping
* Whatever is chosen must work on both loaders from one source directory (ADR-0002)
* A rule nobody can check by eye is not a rule; it has to be mechanically enforceable
* The enforcement must be cheap enough to run on every build, or it will not run

## Considered Options

* **`@OnlyIn(Dist.CLIENT)` on client classes** — Forge's own marker.
* **`DistExecutor` at every crossing point.**
* **A separate client source set, or a `:client` Gradle project.**
* **A package convention, enforced by a build check and a real-server smoke test.**
* **Nothing structural; rely on testing.**

## Decision Outcome

Chosen option: **client-only code lives in a package named `client`, and nothing else may name
`net.minecraft.client`.** A build check enforces the import-level form of that; the boot crash's
subtler forms are caught by booting the produced jar on a real dedicated server.

`@OnlyIn` was rejected on ADR-0002 grounds before any technical argument: the annotation is loader
API — `net.minecraftforge.api.distmarker` against `net.neoforged.api.distmarker` — so it cannot
appear in `common/`, which is where most of this code lives. It also does not solve the problem
above. Annotating `RadioScreen` does nothing about the synthetic lambda method on `MmmmForge` that
mentions it.

`DistExecutor` was rejected for the same reason plus a second: it carries the identical trap. Its
"safe" variants exist precisely because passing a lambda to the plain ones puts the client type in
the caller, which is the bug being defended against. A mechanism whose correct use requires knowing
this failure mode is not a mechanism for preventing it.

A separate source set or project was rejected because it buys the wrong guarantee. One jar ships to
both dists regardless, so physical separation at compile time changes nothing about what the server
loads at runtime — while adding a project boundary, a second toolchain configuration and a
dependency direction to maintain.

### The convention, concretely

| Rule | |
|---|---|
| Where client code lives | A package named `client` — today `mmmm.client` and `mmmm.forge.client` |
| Who may import `net.minecraft.client` | Only files in such a package. No exceptions, including "just one type" |
| How shared code reaches client code | A static seam: shared code declares `setScreenOpener(Consumer<…>)`, the loader's **client setup** installs an implementation. See `RadioBlock`, `ClientMedia`, `ClientNetwork` |
| How the loader entry class reaches client setup | A **method reference**, never a lambda — `ForgeClientSetup::install`, not `() -> …` |
| What the entry class itself may name | Nothing client-side. All of it moved to `ForgeClientSetup` |

The enforced rule is deliberately blunter than the real invariant. The real invariant is "the
server must not load a class whose verification resolves a client type", which is a property of
bytecode. The enforced rule is "a file that names `net.minecraft.client` lives in a `client`
package", which is a property of text. The blunt version rejects some code that would in fact be
fine, and that is the correct trade: it is checkable in a `grep`, and it is explicable in one
sentence to whoever trips it.

### Consequences

* Good, because the rule is mechanically checkable and actually checked —
  `:forge:checkClientClassesAreClientOnly` runs as part of `check`.
* Good, because it is pure convention: no loader API, so it holds identically on Forge and NeoForge
  from one source directory.
* Good, because the package name states the constraint at the point of editing. A file's location
  is harder to overlook than an annotation.
* Bad, because the import check catches only the import-level form. A lambda body, a fully qualified
  name, or a type reached through inference slips past it. **This is why
  `tools/check-server-jar.sh` exists** — it boots the produced jar on a real dedicated server, which
  is the only thing that can catch the rest.
* Bad, because every crossing point costs an indirection — an interface or a `Consumer` seam plus
  the class that installs it.
* Bad, because forgetting to install a seam fails *silently*. Shared code calls a null-checked
  hook, nothing happens, and there is no crash to diagnose. This is a real cost paid to avoid a
  loud failure, and it has already produced one silent radio.
* Neutral: the rule says nothing about the server-only direction. Nothing enforces that a client
  never loads server relay internals, because that is harmless — the client has those classes and
  may load them freely.

### Confirmation

* `./gradlew :forge:check` runs `checkClientClassesAreClientOnly`, which fails the build listing
  every offending file and import.
* `grep -rln '^import net.minecraft.client' common/src/main/java forge/src/main/java` lists only
  files under a `client` package. Anything else in that output is a boot crash waiting to happen.
* `tools/check-server-jar.sh` boots the produced jar on a real Forge dedicated server and waits for
  a placed radio to reach `PLAYING`. A dist violation aborts mod construction long before that.

## More Information

The incident that produced this record: client wiring lived in the `@Mod` entry class as a set of
lambdas. `:forge:build` was green, `runClient` worked, and the first run of
`check-server-jar.sh` crashed on boot. The fix was to move every client-touching line into
`mmmm.forge.client.ForgeClientSetup` and call it by method reference.

Run `check-server-jar.sh` after any change to packaging, to the entry class, or to the client/server
split. Those are the three ways back into this failure, and none of them is visible from a client.
