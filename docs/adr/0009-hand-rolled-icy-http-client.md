---
status: accepted
date: 2026-08-08
---

# Hand-rolled HTTP/ICY client instead of a JDK HTTP client

## Context and Problem Statement

Shoutcast servers respond with the status line `ICY 200 OK` rather than `HTTP/1.1 200 OK`. Both
`HttpURLConnection` and `java.net.http.HttpClient` reject that outright — it is not a malformed
edge case they tolerate, it fails the parse.

Icecast additionally interleaves metadata into the audio body: every `icy-metaint` bytes it injects
a length-prefixed block carrying the current track title. A client that does not strip these plays
them as audio.

## Decision Drivers

* Shoutcast support is not optional; a large share of stations run it
* ADR-0011 requires re-validating the destination address after *every* redirect
* Metadata de-interleaving happens at the byte-stream level regardless

## Considered Options

* JDK `HttpClient` — excludes Shoutcast entirely
* A third-party HTTP client tolerant of the ICY status line
* Raw `Socket` / `SSLSocket` speaking HTTP/1.0 directly

## Decision Outcome

Chosen option: **raw socket**.

It is roughly 150 lines, and it removes a dependency rather than adding one. More importantly, the
metadata de-interleaving has to own the read loop anyway, so this is where that code naturally
lives — a library would be wrapped in exactly this logic and provide nothing underneath it.

It also happens to be the only option that satisfies ADR-0011: a client that follows redirects
internally gives no opportunity to re-check the resolved address at each hop.

### Consequences

* Good, because both Shoutcast and Icecast work.
* Good, because `StreamTitle` extraction comes free from code we needed anyway.
* Good, because we control timeouts, redirect policy, and connecting to an already-validated
  `InetAddress` rather than re-resolving a hostname (which would reopen the rebinding hole).
* Bad, because we own correctness for redirects, chunked encoding and TLS setup. Kept manageable by
  implementing only what stations actually use, and covered by recorded-response fixture tests.
* Neutral: HTTP/1.0 without keep-alive is the correct choice here — these connections are single and
  endless.

### Confirmation

Fixture tests for both `ICY 200 OK` and `HTTP/1.1 200 OK` responses, asserting that metadata blocks
are stripped byte-exactly at `metaint` boundaries and that `StreamTitle` changes are reported.
