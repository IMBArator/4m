package mmmm.core.relay;

import mmmm.core.media.MediaFrame;
import mmmm.core.media.StreamInfo;
import mmmm.core.transport.MediaTransport;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Records what a session tried to send.
 *
 * <p>Synchronised throughout: the relay thread publishes titles while the test thread drains.
 */
final class FakeTransport implements MediaTransport {

    record Opened(SubscriberId subscriber, int sessionId, String originName, List<StreamInfo> streams,
                  long epochNanos, int presentationDelayMs, List<MediaFrame> backlog) {
    }

    record Titled(SubscriberId subscriber, int sessionId, long ptsMicros, String title) {
    }

    private final List<Opened> opens = Collections.synchronizedList(new ArrayList<>());
    private final List<MediaFrame> framesSent = Collections.synchronizedList(new ArrayList<>());
    private final List<Titled> titles = Collections.synchronizedList(new ArrayList<>());
    private final List<Integer> closes = Collections.synchronizedList(new ArrayList<>());

    private int sendCalls;

    @Override
    public void openStream(SubscriberId subscriber, int sessionId, String originName,
                           List<StreamInfo> streams, long streamEpochServerNanos,
                           int presentationDelayMs, List<MediaFrame> backlog) {
        opens.add(new Opened(subscriber, sessionId, originName, List.copyOf(streams),
                streamEpochServerNanos, presentationDelayMs, List.copyOf(backlog)));
    }

    @Override
    public synchronized void sendFrames(SubscriberId subscriber, int sessionId, List<MediaFrame> frames) {
        sendCalls++;
        framesSent.addAll(frames);
    }

    @Override
    public void sendTitle(SubscriberId subscriber, int sessionId, long ptsMicros, String title) {
        titles.add(new Titled(subscriber, sessionId, ptsMicros, title));
    }

    @Override
    public void closeStream(SubscriberId subscriber, int sessionId) {
        closes.add(sessionId);
    }

    List<Opened> opens() {
        return List.copyOf(opens);
    }

    List<MediaFrame> framesSent() {
        return List.copyOf(framesSent);
    }

    List<Titled> titles() {
        return List.copyOf(titles);
    }

    List<Integer> closes() {
        return List.copyOf(closes);
    }

    synchronized int sendCalls() {
        return sendCalls;
    }

    /** A stand-in for a player. */
    record Subscriber(String name) implements SubscriberId {
    }
}
