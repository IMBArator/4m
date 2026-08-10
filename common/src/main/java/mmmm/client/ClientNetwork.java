package mmmm.client;

/**
 * Routes incoming media packets to {@link ClientMedia}.
 *
 * <p>The loader's network module translates its wire-format message into one of the plain
 * {@link ClientMessages} records and hands it here. That is the only seam, and it is what keeps
 * this class and everything it calls loader-neutral: shared code never sees a
 * {@code NetworkEvent.Context} or a {@code FriendlyByteBuf}.
 *
 * <p>Every method is invoked from {@code NetworkEvent.Context.enqueueWork}, i.e. on the client
 * thread — never on the network thread.
 */
public final class ClientNetwork {

    private ClientNetwork() {
    }

    public static void onStreamOpen(ClientMessages.StreamOpen msg) {
        ClientMedia.openSession(msg.sessionId(),
                msg.streams().isEmpty() ? null : msg.streams().get(0),
                msg.epochNanos(),
                msg.presentationDelayMs(),
                msg.backlog());
    }

    public static void onStreamData(ClientMessages.StreamData msg) {
        ClientMedia.acceptFrames(msg.sessionId(), msg.frames());
    }

    public static void onStreamMeta(ClientMessages.StreamMeta msg) {
        ClientMedia.setTitle(msg.sessionId(), msg.ptsMicros(), msg.title());
    }

    public static void onStreamClose(ClientMessages.StreamClose msg) {
        ClientMedia.closeSession(msg.sessionId());
    }

    public static void onClockPong(ClientMessages.ClockPong msg) {
        ClientMedia.onClockPong(msg.clientNanos(), msg.serverNanos());
    }
}
