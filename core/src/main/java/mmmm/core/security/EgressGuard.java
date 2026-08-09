package mmmm.core.security;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Decides whether the server may connect to a destination. See ADR-0011.
 *
 * <p>The relay makes the <em>server</em> fetch player-supplied URLs, which is server-side request
 * forgery driven by untrusted input. Minecraft servers commonly run on cloud hosts where
 * {@code 169.254.169.254} returns instance credentials, so this is not a theoretical concern.
 *
 * <h2>Two properties that are easy to lose</h2>
 * <ol>
 *   <li>{@link #check} returns the {@link InetAddress} it validated, and callers must connect to
 *       <em>that address</em>. Connecting by hostname afterwards re-resolves, and the second
 *       resolution can differ from the checked one — that is DNS rebinding, and it defeats the
 *       whole check.
 *   <li>Redirects must be re-checked. An allowlisted host that redirects to {@code 169.254.169.254}
 *       is the obvious bypass, and it is why we follow redirects by hand (ADR-0009).
 * </ol>
 *
 * <p>Immutable and thread-safe.
 */
public final class EgressGuard {

    private final Set<String> allowedHosts;
    private final boolean allowAnyHost;

    private EgressGuard(Set<String> allowedHosts, boolean allowAnyHost) {
        this.allowedHosts = allowedHosts;
        this.allowAnyHost = allowAnyHost;
    }

    /**
     * Default-deny: only the given hosts may be contacted. Host matching is case-insensitive and
     * covers subdomains, so {@code "example.com"} also permits {@code "stream.example.com"}.
     */
    public static EgressGuard allowing(Set<String> hosts) {
        Set<String> normalised = new LinkedHashSet<>();
        for (String h : hosts) {
            if (h != null && !h.isBlank()) {
                normalised.add(h.trim().toLowerCase(Locale.ROOT));
            }
        }
        return new EgressGuard(normalised, false);
    }

    /**
     * Any public host may be contacted; private and link-local ranges are still refused.
     *
     * <p>This is the opt-in mode from ADR-0011 and should never be the default. It is a meaningful
     * reduction in safety, not a convenience toggle.
     */
    public static EgressGuard allowingAnyPublicHost() {
        return new EgressGuard(Set.of(), true);
    }

    /**
     * Validates a destination and returns the address to connect to.
     *
     * @return the resolved, checked address — connect to this, not to the hostname
     * @throws EgressDeniedException if the scheme, host or any resolved address is refused
     */
    public InetAddress check(URI uri) throws EgressDeniedException {
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new EgressDeniedException("Refused scheme '" + scheme + "'; only http and https are permitted");
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new EgressDeniedException("Refused URL with no host: " + uri);
        }
        String normalisedHost = host.toLowerCase(Locale.ROOT);

        if (!allowAnyHost && !isHostAllowed(normalisedHost)) {
            throw new EgressDeniedException(
                    "Host '" + host + "' is not on the station allowlist. "
                            + "Add it to the server config, or enable free-form URLs.");
        }

        InetAddress[] resolved;
        try {
            resolved = InetAddress.getAllByName(normalisedHost);
        } catch (UnknownHostException e) {
            throw new EgressDeniedException("Cannot resolve host '" + host + "'");
        }
        if (resolved.length == 0) {
            throw new EgressDeniedException("Host '" + host + "' resolved to no addresses");
        }

        // Every resolved address must pass, not merely the one we intend to use. A host resolving
        // to both a public and a private address is a rebinding attempt, and picking the public one
        // would let it through.
        for (InetAddress address : resolved) {
            String reason = refusalReason(address);
            if (reason != null) {
                throw new EgressDeniedException(
                        "Host '" + host + "' resolves to " + address.getHostAddress() + " (" + reason + ")");
            }
        }
        return resolved[0];
    }

    private boolean isHostAllowed(String host) {
        if (allowedHosts.contains(host)) {
            return true;
        }
        for (String allowed : allowedHosts) {
            if (host.endsWith("." + allowed)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Why this address must not be contacted, or {@code null} if it is acceptable.
     *
     * <p>Package-visible for testing so the range checks can be exercised without DNS.
     */
    static String refusalReason(InetAddress address) {
        if (address.isLoopbackAddress()) {
            return "loopback";
        }
        if (address.isAnyLocalAddress()) {
            return "wildcard address";
        }
        if (address.isLinkLocalAddress()) {
            // Covers 169.254/16 and fe80::/10 — this is the cloud metadata endpoint.
            return "link-local";
        }
        if (address.isSiteLocalAddress()) {
            // Covers 10/8, 172.16/12, 192.168/16.
            return "private";
        }
        if (address.isMulticastAddress()) {
            return "multicast";
        }

        byte[] raw = address.getAddress();

        // IPv4-mapped IPv6 (::ffff:a.b.c.d) needs no special handling here: Java normalises those
        // to Inet4Address in both getByName and getByAddress, so they arrive already unwrapped and
        // are caught by the IPv4 rules below. Code that re-checked the mapped form would never run
        // — and dead code in a security check is worse than none, because it implies coverage that
        // is happening somewhere else.
        if (address instanceof Inet6Address v6) {
            // fc00::/7 — unique local. Not covered by isSiteLocalAddress() for IPv6.
            if ((raw[0] & 0xFE) == 0xFC) {
                return "unique-local";
            }
            if (v6.isIPv4CompatibleAddress()) {
                return "IPv4-compatible IPv6";
            }
            return null;
        }

        if (address instanceof Inet4Address) {
            int first = raw[0] & 0xFF;
            int second = raw[1] & 0xFF;

            // 100.64.0.0/10 — carrier-grade NAT. Not covered by isSiteLocalAddress().
            if (first == 100 && second >= 64 && second <= 127) {
                return "carrier-grade NAT";
            }
            // 0.0.0.0/8 — "this network".
            if (first == 0) {
                return "reserved";
            }
            // 240.0.0.0/4 — reserved, and 255.255.255.255 broadcast.
            if (first >= 240) {
                return "reserved";
            }
            return null;
        }

        return null;
    }
}
