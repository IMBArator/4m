---
status: accepted
date: 2026-08-08
---

# Default-deny egress allowlist on the server

## Context and Problem Statement

ADR-0003 makes the **server** open outbound connections to player-supplied URLs. Minecraft servers
commonly run on cloud hosts, where `http://169.254.169.254/` returns instance credentials.

That is server-side request forgery driven by untrusted input, in the environment where it pays off
best. The relay removed this risk from clients and concentrated it here, which is a net improvement
in blast radius but a considerable increase in severity.

## Decision Drivers

* Safety for operators who never open the config file
* Usability for servers that legitimately want open station choice
* Defence that survives redirects and DNS tricks, not just a naive URL check

## Considered Options

* Allow any URL
* Blocklist private ranges
* Default-deny allowlist, with private-range blocking on top

## Decision Outcome

Chosen option: **default-deny allowlist**, with the configured station list as the allowed set.
Free-form URLs are opt-in per server and gated on permission level.

Blocklisting alone fails open, and it fails open in three independent ways: a missed range, a cloud
provider introducing a new metadata address, or an operator who never reads the config. An allowlist
fails closed on all three.

Private-range blocking is kept as defence in depth, not as the primary control:

* `169.254.0.0/16` and `fe80::/10` — link-local, **including the cloud metadata endpoint**
* `127.0.0.0/8`, `::1` — loopback
* `10/8`, `172.16/12`, `192.168/16`, `fc00::/7` — private
* `100.64.0.0/10` — CGNAT
* `0.0.0.0/8`, multicast, and IPv4-mapped IPv6 forms of all the above

Two implementation requirements that are easy to miss and load-bearing:

1. Check **after DNS resolution**, and connect to the `InetAddress` that was checked — never
   re-resolve the hostname afterwards, or the check and the connection can disagree.
2. **Re-check after every redirect.** An allowlisted host redirecting to `169.254.169.254` is the
   obvious bypass.

### Consequences

* Good, because the default configuration is safe, including for operators who never touch it.
* Good, because DNS rebinding is closed by construction rather than by timing.
* Bad, because servers wanting open station choice must opt in. That is an informed, explicit
  decision, which is the point.
* Neutral: ADR-0009's hand-rolled client is what makes per-redirect re-checking possible at all. A
  library that follows redirects internally could not offer this hook.

### Confirmation

Tests asserting refusal of `169.254.169.254`, `127.0.0.1`, `10.0.0.1` and their IPv6 forms —
including when reached **via a redirect from an allowlisted host**, and when reached via a hostname
that resolves to a private address.
