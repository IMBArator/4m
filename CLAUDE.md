# 4M — M&N's Minecraft Multi Media Mod

Synchronised web radio for Minecraft 1.20.1. See `README.md` for what it is and
`docs/adr/` for why it is built the way it is.

The product is **4M**; the mod id, resource namespace and Java package root are all **`mmmm`** —
Forge rejects mod ids that start with a digit, so `4m` cannot be one. See
[ADR-0012](docs/adr/0012-naming-and-identifiers.md) before touching any identifier. Rule of thumb:
anything a registry, loader or compiler parses is `mmmm`; anything a human reads is 4M.

## Commits

**Always use [Conventional Commits](https://www.conventionalcommits.org/).**

```
<type>(<optional scope>): <summary in the imperative, lower case, no full stop>

<body: why, not what — the diff already says what>
```

Types in use here: `feat`, `fix`, `docs`, `build`, `refactor`, `test`, `perf`, `chore`.
Scopes are module or area names — `forge`, `neoforge`, `core`, `build`.

Split unrelated changes into separate commits. A build fix that merely unblocks a
feature belongs in its own commit, so it can be reverted or amended without dragging
the feature along.

Use the body for the reasoning that is not recoverable from the diff: why an approach
was chosen, which alternative was rejected, what breaks without it.

## Build

Needs JDK 17 — the system JDKs are JRE-only and Debian 13 has no `openjdk-17-jdk`.
See the toolchain section in `README.md`.

```bash
export JAVA_HOME=~/.jdks/$(ls ~/.jdks | grep jdk-17)
./gradlew :core:test        # 123 tests, no Minecraft toolchain needed
./gradlew :forge:build
./gradlew :forge:runClient
./tools/build-core.sh       # faster :core loop, JDK only, no Gradle
```

`:neoforge` is commented out of `settings.gradle` — its build file targets a NeoGradle
line that does not support 1.20.1. `settings.gradle` explains the details.

## Conventions

`core/` is pure Java: no Minecraft, no loader, unit-testable. `common/` is Minecraft
code shared by both loaders as a *source directory*, not a Gradle project (ADR-0002),
so it must not reference loader APIs such as `DeferredRegister` or `RegistryObject`.
Loader-specific code lives in `forge/` and `neoforge/`.

The server never decodes audio and must never gain a codec dependency (ADR-0004);
`:core:checkServerSideHasNoCodecDeps` enforces this for `core/source`, `core/frame`
and `core/transport`.

Comments explain *why*, and name the concrete failure they prevent. Match the
surrounding density — see `core/src/main/java/mmmm/core/media/Timeline.java` for the
house style.
