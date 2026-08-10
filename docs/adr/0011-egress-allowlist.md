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

## Amendment — 2026-08-10: how the opt-in actually works

This record left "opt-in per server and gated on permission level" as a shape rather than a
mechanism. The radio control screen needed the mechanism, so here it is.

**An operator setting a custom station URL is the opt-in.** There is no separate config toggle. A
player with permission level 2 who submits a URL authorises that host, for that world, from then on;
`ServerNetwork` enforces the permission check and `RadioAllowlist` — a `SavedData` — persists the
result. Anyone below level 2 can only choose from the shipped station list.

**An authorisation covers the station's whole resolution chain, not one hostname.** This was got
wrong first time and corrected the same day, because it fails on essentially every real station.

A station URL is normally a *playlist*. `https://streams.radiobob.de/…/play.pls` resolves to an
endpoint at `regiocast.streamabc.net` — a different registrable domain, on a CDN whose hostname can
vary between requests, so it cannot be enumerated in advance or pinned by suffix. Authorising only
the typed host meant the first hop passed and the second was refused:

```
Station set. streams.radiobob.de is now allowed on this server.
Radio … stopped: station …/play.pls failed permanently
  (refused by the egress allowlist: Host 'regiocast.streamabc.net' is not on the station allowlist.)
```

So `SourceOpener` takes a `Function<URI, EgressGuard>`, and an operator-authorised station gets
`allowingAnyPublicHost()` — the guard that blocks address ranges and nothing else. Everything not
authorised still gets the default-deny shipped list.

The security delta is worth being explicit about rather than buried: for an authorised station, the
server may follow that station's chain to any *public* host. The operator has already chosen to
stream content they do not control from a host they do not control, so a redirect to a second public
host adds little — and the protection that actually matters here, refusal of loopback, RFC1918, CGNAT
and link-local including `169.254.169.254`, is untouched and still applies to **every hop**. That is
precisely why range blocking is kept as its own layer rather than folded into the allowlist.

Two further consequences worth stating, because each could otherwise look like an oversight:

* **The policy is resolved per connection**, which is what lets an authorisation take effect without
  a restart and lets a session reconnecting an hour later see the current one. Each guard handed back
  is still immutable, and is still consulted after DNS and on every redirect hop — neither of the two
  load-bearing requirements above is weakened.
* **Persistence is deliberate and is not merely convenience.** Without it, a radio keeps its URL
  across a restart while the allowlist forgets it, so the station dies on next boot with no visible
  cause. It is stored per world rather than in a config file because it is state produced by play,
  not by an operator editing a file.
* **Authorising a station never authorises an address.** Authorising `example.com` cannot reach
  `169.254.169.254`, whether by resolving there or by redirecting there.

One deliberate limitation: the submit-time check is **syntactic plus address literals only**. Scheme,
host presence, and — for a host that is an IP literal — the full range check, which costs nothing
because `getAllByName` does not resolve a literal. A *hostname* is not resolved at submit time,
because DNS on the server thread would stall every player on the server for as long as the resolver
took. Such a URL is accepted, then refused by the guard on the relay thread at connect time; the
session reaches `FAILED`, `RadioServer` stops the block, logs the reason, and sends it to whoever
configured that radio. So the protection is identical, and only the feedback is a second later.

That last part — telling the player, not just the log — was added after the playlist failure above
was diagnosed by reading the server log, which is not a thing a player can do.
