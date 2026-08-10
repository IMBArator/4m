package mmmm.core.relay;

import mmmm.core.security.EgressDeniedException;
import mmmm.core.security.EgressGuard;
import mmmm.core.source.SourceConfig;
import mmmm.core.source.StreamTitleListener;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The supplier form of {@link SourceOpener#network}.
 *
 * <p>No network is touched. Every case here is refused by the guard before a socket could be opened,
 * which is the point: {@code .invalid} is reserved by RFC 2606 and can never resolve, so the two
 * refusal reasons are stable and distinguishable everywhere.
 */
class SourceOpenerTest {

    private static final URI STATION = URI.create("http://example.invalid/stream");

    /**
     * The reason the supplier overload exists. An operator authorising a host must not have to
     * restart the server, and a session reconnecting an hour later must see the allowlist as it
     * stands then — both of which fail if the guard is captured once at construction.
     */
    @Test
    void theGuardIsReReadOnEveryConnection() {
        AtomicInteger reads = new AtomicInteger();
        SourceOpener opener = SourceOpener.network(uri -> {
            reads.incrementAndGet();
            return EgressGuard.allowing(Set.of());
        }, SourceConfig.DEFAULT);

        assertThrows(EgressDeniedException.class, () -> opener.open(STATION, StreamTitleListener.NONE));
        assertEquals(1, reads.get());

        assertThrows(EgressDeniedException.class, () -> opener.open(STATION, StreamTitleListener.NONE));
        assertEquals(2, reads.get(), "a captured guard would have been consulted once, not twice");
    }

    /** Widening the set the supplier returns actually changes what the next connection may reach. */
    @Test
    void wideningTheAllowlistTakesEffectWithoutRebuildingTheOpener() {
        Set<String>[] allowed = uncheckedArray(Set.of());
        SourceOpener opener = SourceOpener.network(
                uri -> EgressGuard.allowing(allowed[0]), SourceConfig.DEFAULT);

        EgressDeniedException before = assertThrows(EgressDeniedException.class,
                () -> opener.open(STATION, StreamTitleListener.NONE));
        assertTrue(before.getMessage().contains("not on the station allowlist"),
                "expected an allowlist refusal, got: " + before.getMessage());

        allowed[0] = Set.of("example.invalid");

        // Past the allowlist now, so the refusal comes from the next check instead — resolution.
        // That change of reason is the observable proof the new guard was the one consulted.
        EgressDeniedException after = assertThrows(EgressDeniedException.class,
                () -> opener.open(STATION, StreamTitleListener.NONE));
        assertTrue(after.getMessage().contains("Cannot resolve host"),
                "expected the allowlist to have been passed, got: " + after.getMessage());
    }

    /**
     * The policy is per station, not global.
     *
     * <p>This is the case that failed on first real use. A station URL is usually a playlist naming
     * an endpoint on a different domain — {@code radiobob.de} resolves to {@code streamabc.net} — so
     * an authorisation that covers only the typed host refuses nearly every real station on the
     * second hop. Choosing the guard from the station is what lets the caller say how far its
     * authorisation reaches.
     */
    @Test
    void theGuardIsChosenPerStation() {
        SourceOpener opener = SourceOpener.network(
                uri -> "authorised.invalid".equals(uri.getHost())
                        ? EgressGuard.allowingAnyPublicHost()
                        : EgressGuard.allowing(Set.of()),
                SourceConfig.DEFAULT);

        EgressDeniedException unauthorised = assertThrows(EgressDeniedException.class,
                () -> opener.open(URI.create("http://other.invalid/stream"), StreamTitleListener.NONE));
        assertTrue(unauthorised.getMessage().contains("not on the station allowlist"),
                "expected an allowlist refusal, got: " + unauthorised.getMessage());

        EgressDeniedException authorised = assertThrows(EgressDeniedException.class,
                () -> opener.open(URI.create("http://authorised.invalid/stream"), StreamTitleListener.NONE));
        assertTrue(authorised.getMessage().contains("Cannot resolve host"),
                "the authorised station should have got past the allowlist, got: "
                        + authorised.getMessage());
    }

    /**
     * Authorising one host must not authorise the private ranges. ADR-0011 treats range blocking as
     * defence in depth precisely so that widening the allowlist cannot reach them.
     */
    @Test
    void awidenedAllowlistStillRefusesPrivateAndLinkLocalAddresses() {
        SourceOpener opener = SourceOpener.network(
                uri -> EgressGuard.allowing(Set.of("example.invalid", "127.0.0.1", "10.0.0.1",
                        "169.254.169.254", "[::1]")),
                SourceConfig.DEFAULT);

        for (String host : new String[]{"127.0.0.1", "10.0.0.1", "169.254.169.254"}) {
            URI uri = URI.create("http://" + host + "/stream");
            EgressDeniedException denied = assertThrows(EgressDeniedException.class,
                    () -> opener.open(uri, StreamTitleListener.NONE),
                    host + " was reachable after the allowlist was widened");
            assertTrue(denied.getMessage().contains("resolves to"),
                    "expected an address-range refusal for " + host + ", got: " + denied.getMessage());
        }
    }

    /** Keeps the generic-array creation warning in one place rather than on the test method. */
    @SafeVarargs
    @SuppressWarnings("varargs")
    private static Set<String>[] uncheckedArray(Set<String>... values) {
        return values;
    }
}
