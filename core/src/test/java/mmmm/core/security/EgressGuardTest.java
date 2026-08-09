package mmmm.core.security;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The relay makes the server fetch player-supplied URLs, so these are the tests that stand between
 * a griefer and the host's cloud credentials. See ADR-0011.
 *
 * <p>Range checks run against literal addresses so they need no DNS and cannot flake.
 */
class EgressGuardTest {

    private static InetAddress addr(String literal) throws UnknownHostException {
        return InetAddress.getByName(literal);
    }

    /** The one that matters most: AWS/GCP/Azure instance metadata. */
    @Test
    void refusesTheCloudMetadataEndpoint() throws Exception {
        assertEquals("link-local", EgressGuard.refusalReason(addr("169.254.169.254")));
    }

    @Test
    void refusesLoopback() throws Exception {
        assertEquals("loopback", EgressGuard.refusalReason(addr("127.0.0.1")));
        assertEquals("loopback", EgressGuard.refusalReason(addr("127.1.2.3")));
        assertEquals("loopback", EgressGuard.refusalReason(addr("::1")));
    }

    @Test
    void refusesPrivateRanges() throws Exception {
        assertEquals("private", EgressGuard.refusalReason(addr("10.0.0.1")));
        assertEquals("private", EgressGuard.refusalReason(addr("172.16.5.4")));
        assertEquals("private", EgressGuard.refusalReason(addr("192.168.1.1")));
    }

    @Test
    void refusesCarrierGradeNat() throws Exception {
        // 100.64/10 is not covered by isSiteLocalAddress(), so it needs its own check.
        assertEquals("carrier-grade NAT", EgressGuard.refusalReason(addr("100.64.0.1")));
        assertEquals("carrier-grade NAT", EgressGuard.refusalReason(addr("100.127.255.255")));
        assertNull(EgressGuard.refusalReason(addr("100.63.255.255")), "just below the range is public");
        assertNull(EgressGuard.refusalReason(addr("100.128.0.0")), "just above the range is public");
    }

    @Test
    void refusesWildcardAndReservedRanges() throws Exception {
        assertEquals("wildcard address", EgressGuard.refusalReason(addr("0.0.0.0")));
        assertEquals("reserved", EgressGuard.refusalReason(addr("240.0.0.1")));
    }

    @Test
    void refusesIpv6PrivateAndLinkLocalRanges() throws Exception {
        assertEquals("link-local", EgressGuard.refusalReason(addr("fe80::1")));
        assertEquals("unique-local", EgressGuard.refusalReason(addr("fd00::1")));
        assertEquals("unique-local", EgressGuard.refusalReason(addr("fc00::1")));
    }

    /**
     * IPv4-mapped IPv6 is the obvious bypass to try, so it is worth a test even though the JDK
     * closes it for us: Java normalises {@code ::ffff:a.b.c.d} to an {@code Inet4Address} in both
     * {@code getByName} and {@code getByAddress}, so these arrive already unwrapped and hit the
     * IPv4 rules.
     *
     * <p>This asserts the property — refused — rather than which rule did the refusing, so it keeps
     * testing the thing that matters if that normalisation ever changes.
     */
    @Test
    void refusesIpv4MappedAddresses() throws Exception {
        for (String literal : new String[]{
                "::ffff:169.254.169.254", "::ffff:127.0.0.1", "::ffff:10.0.0.1"}) {
            assertNotNull(EgressGuard.refusalReason(addr(literal)),
                    literal + " must be refused however it is spelled");
        }
    }

    @Test
    void allowsOrdinaryPublicAddresses() throws Exception {
        assertNull(EgressGuard.refusalReason(addr("93.184.216.34")));
        assertNull(EgressGuard.refusalReason(addr("8.8.8.8")));
        assertNull(EgressGuard.refusalReason(addr("2606:2800:220:1:248:1893:25c8:1946")));
    }

    @Test
    void refusesSchemesOtherThanHttpAndHttps() {
        EgressGuard guard = EgressGuard.allowingAnyPublicHost();

        assertThrows(EgressDeniedException.class, () -> guard.check(URI.create("file:///etc/passwd")));
        assertThrows(EgressDeniedException.class, () -> guard.check(URI.create("ftp://example.com/x")));
        assertThrows(EgressDeniedException.class, () -> guard.check(URI.create("gopher://example.com")));
    }

    @Test
    void refusesUrlsWithNoHost() {
        EgressGuard guard = EgressGuard.allowingAnyPublicHost();

        assertThrows(EgressDeniedException.class, () -> guard.check(URI.create("http:///path")));
    }

    @Test
    void defaultDeniesHostsOutsideTheAllowlist() {
        EgressGuard guard = EgressGuard.allowing(Set.of("allowed.example"));

        EgressDeniedException denied = assertThrows(EgressDeniedException.class,
                () -> guard.check(URI.create("http://notallowed.example/stream")));
        assertTrue(denied.getMessage().contains("allowlist"),
                "the message should point the operator at the config, was: " + denied.getMessage());
    }

    @Test
    void allowlistCoversSubdomainsButNotSuffixCollisions() {
        EgressGuard guard = EgressGuard.allowing(Set.of("example.com"));

        // A bare suffix match would let notexample.com through, which is a real registrable domain
        // someone else can own.
        assertThrows(EgressDeniedException.class,
                () -> guard.check(URI.create("http://notexample.com/stream")));
    }

    @Test
    void allowlistIsCaseInsensitive() {
        EgressGuard guard = EgressGuard.allowing(Set.of("Example.COM"));

        // Resolution will fail or succeed depending on the environment; what matters is that the
        // rejection is not the allowlist. Any failure here must be about resolution instead.
        try {
            assertNotNull(guard.check(URI.create("http://EXAMPLE.com/stream")));
        } catch (EgressDeniedException e) {
            assertTrue(e.getMessage().contains("resolve") || e.getMessage().contains("resolves"),
                    "case handling must not be the reason for refusal, was: " + e.getMessage());
        }
    }

    @Test
    void refusesAHostnameThatResolvesToLoopback() {
        // localhost is the simplest case of a name pointing inside; the same code path is what
        // stops an attacker-controlled name resolving to 169.254.169.254.
        EgressGuard guard = EgressGuard.allowingAnyPublicHost();

        EgressDeniedException denied = assertThrows(EgressDeniedException.class,
                () -> guard.check(URI.create("http://localhost:8000/stream")));
        assertTrue(denied.getMessage().contains("loopback"), denied.getMessage());
    }

    @Test
    void refusesLiteralAddressesEvenWhenAllowlisted() {
        // An operator adding an IP literal to the allowlist must not thereby unlock the metadata
        // endpoint: the allowlist grants a name, it does not waive the range checks.
        EgressGuard guard = EgressGuard.allowing(Set.of("169.254.169.254"));

        assertThrows(EgressDeniedException.class,
                () -> guard.check(URI.create("http://169.254.169.254/latest/meta-data/")));
    }
}
