package mmmm.core.relay;

import mmmm.core.source.SourceMetadata;
import mmmm.core.source.StreamSource;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class RelayManagerTest {

    private static final URI ONE = URI.create("http://example.invalid/one");
    private static final URI TWO = URI.create("http://example.invalid/two");

    /** Opens, immediately reports end of stream, and counts how often it was asked. */
    private static SourceOpener counting(AtomicInteger opens) {
        return (uri, titles) -> {
            opens.incrementAndGet();
            return new StreamSource() {
                @Override
                public int read(byte[] b, int off, int len) {
                    return -1;
                }

                @Override
                public SourceMetadata metadata() {
                    return SourceMetadata.EMPTY;
                }

                @Override
                public void close() {
                }
            };
        };
    }

    /**
     * The bandwidth property from ADR-0003: the origin sees one listener however many blocks are
     * playing it. Two blocks on one station opening two sockets is the failure this guards.
     */
    @Test
    void twoBlocksOnOneStationShareASession() {
        AtomicInteger opens = new AtomicInteger();
        RelayManager manager = new RelayManager(counting(opens), RelayConfig.DEFAULT, new FakeTransport());
        try {
            RelaySession first = manager.acquire(ONE);
            RelaySession second = manager.acquire(ONE);

            assertSame(first, second);
            assertEquals(1, manager.sessionCount());
            assertEquals(2, manager.refCount(ONE));
        } finally {
            manager.close();
        }
    }

    @Test
    void differentStationsGetDifferentSessions() {
        RelayManager manager = new RelayManager(counting(new AtomicInteger()),
                RelayConfig.DEFAULT, new FakeTransport());
        try {
            RelaySession first = manager.acquire(ONE);
            RelaySession second = manager.acquire(TWO);

            assertEquals(2, manager.sessionCount());
            assertEquals(first, manager.sessionById(first.sessionId()));
            assertEquals(second, manager.sessionById(second.sessionId()));
        } finally {
            manager.close();
        }
    }

    /** Don't sit as a phantom listener on someone else's station (master plan §2.3). */
    @Test
    void theUpstreamClosesWhenTheLastBlockLetsGo() {
        RelayManager manager = new RelayManager(counting(new AtomicInteger()),
                RelayConfig.DEFAULT, new FakeTransport());
        try {
            RelaySession session = manager.acquire(ONE);
            manager.acquire(ONE);

            manager.release(ONE);
            assertEquals(1, manager.sessionCount(), "one block still has it switched on");
            assertNotNull(manager.sessionById(session.sessionId()));

            manager.release(ONE);
            assertEquals(0, manager.sessionCount());
            assertEquals(SessionState.CLOSED, session.state());
            assertNull(manager.sessionById(session.sessionId()));
        } finally {
            manager.close();
        }
    }

    @Test
    void releasingAStationNobodyHoldsIsHarmless() {
        RelayManager manager = new RelayManager(counting(new AtomicInteger()),
                RelayConfig.DEFAULT, new FakeTransport());
        try {
            manager.release(ONE);
            assertEquals(0, manager.sessionCount());
        } finally {
            manager.close();
        }
    }

    /** The disconnect path: a leaving player must not stay referenced by any session. */
    @Test
    void aDepartingSubscriberIsDroppedFromEverySession() {
        RelayManager manager = new RelayManager(counting(new AtomicInteger()),
                RelayConfig.DEFAULT, new FakeTransport());
        try {
            FakeTransport.Subscriber alice = new FakeTransport.Subscriber("alice");
            RelaySession first = manager.acquire(ONE);
            RelaySession second = manager.acquire(TWO);
            first.addSubscriber(alice);
            second.addSubscriber(alice);

            manager.removeSubscriberEverywhere(alice);

            assertEquals(0, first.subscriberCount());
            assertEquals(0, second.subscriberCount());
        } finally {
            manager.close();
        }
    }

    @Test
    void closingTheManagerClosesEverySession() {
        RelayManager manager = new RelayManager(counting(new AtomicInteger()),
                RelayConfig.DEFAULT, new FakeTransport());
        RelaySession first = manager.acquire(ONE);
        RelaySession second = manager.acquire(TWO);

        manager.close();

        assertEquals(SessionState.CLOSED, first.state());
        assertEquals(SessionState.CLOSED, second.state());
        assertEquals(0, manager.sessionCount());
    }
}
