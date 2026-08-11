# 4M — M&N's Minecraft Multi Media Mod

Synchronised web radio for Minecraft 1.20.1. `README.md` says what it is and how to build,
run and test it. `docs/adr/` says why it is built this way.

**The records in `docs/adr/` are binding, and this file does not repeat them.** They are not
history. Several forbid things the compiler will happily let you do — what the server may depend
on, where client-only code may live, how a library may be packaged, which hosts may be contacted,
what the product and the mod id are each called — so code that violates one still builds, still
passes tests, and breaks somewhere expensive. `docs/adr/README.md` indexes them and gives a
reading order; read the records covering an area before changing it. If a decision no longer
holds, supersede it with a new record. Never work around one silently.

## Commits

**Always use [Conventional Commits](https://www.conventionalcommits.org/).**

```
<type>(<optional scope>): <summary in the imperative, lower case, no full stop>

<body: why, not what — the diff already says what>
```

Types in use here: `feat`, `fix`, `docs`, `build`, `refactor`, `test`, `perf`, `chore`.
Scopes are module or area names — `forge`, `neoforge`, `core`, `build`.

**Always split work into logical commit groups.** Unrelated changes go in separate commits. A
build fix that merely unblocks a feature belongs in its own commit, so it can be reverted or
amended without dragging the feature along.

Use the body for the reasoning that is not recoverable from the diff: why an approach was chosen,
which alternative was rejected, what breaks without it.

## Where things get written down

| What | Where |
|---|---|
| A technical decision, and why the alternatives lost | A record in `docs/adr/` — **ask first**; not every decision earns one |
| A working agreement, like this file's rules | Here, as a short block |
| How to build, test, run or release anything | `README.md` |

This file names no ADR by number and keeps no list of them. A second index goes stale the day a
record is superseded, and then contradicts `docs/adr/` with equal authority.

**Never write a count into prose** — of tests, ADRs, files, packets, stations, anything. It is
wrong at the next commit that adds one, and a stale count is worse than none: it reads as
authoritative, so nobody checks it. Name the thing instead of counting it — "the `:core` unit
suite", not "190 tests"; "the records in `docs/adr/`", not "twelve records". Same for any list
that would have to stay exhaustive. A number that genuinely matters belongs in a test assertion
or a build check, where drifting from reality fails something.

Keep each block here under about 30 lines. Anything longer is an ADR, or a README section pasted
into the wrong file.

## Comments

Comments explain **why**, and name the concrete failure they prevent. The diff already says what
changed and the code says what it does; neither can say what breaks when someone simplifies it.

Three shapes carry most of the weight here:

* **A class doc says why the class exists**, not what it contains. `Timeline`'s opens with "this
  class exists to make the accumulating-rounding-error bug impossible to write" — which is the
  only argument against inlining the arithmetic and deleting it.
* **A non-obvious expression names the alternative and the number that kills it.** Not "split the
  division for precision", but: the naive form is exact too, and overflows `long` after about 6.6
  years of 44.1 kHz audio. That is checkable by a reviewer and actionable by whoever wants to
  undo it.
* **A constant says what goes wrong at other values.** An unexplained magic number gets retuned by
  the first person who finds it inconvenient.

Match the surrounding density rather than a fixed ratio. `core/` holds the framing and sync
arithmetic and is heavily commented; registry and wiring code is nearly bare, because there is
nothing there to warn anyone about. Ceremony on the bare parts is as wrong as silence on the
dense ones.

Two things not to write: a comment restating the signature (`// returns the sample rate`), and a
comment that no longer matches the code — a wrong comment costs more than no comment, because it
gets believed. House style reference: `core/src/main/java/mmmm/core/media/Timeline.java`.

## Build

See `README.md`.
