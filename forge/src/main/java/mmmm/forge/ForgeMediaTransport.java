package mmmm.forge;

import mmmm.client.ClientMessages;
import mmmm.core.media.MediaFrame;
import mmmm.core.media.StreamInfo;
import mmmm.core.transport.MediaTransport;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.ArrayList;
import java.util.List;

/**
 * Bridges {@link MediaTransport} onto the Minecraft connection via {@link MmmmNetwork}.
 *
 * <p>Each send resolves a player by UUID at the last moment. Holding the {@code ServerPlayer}
 * object in {@link mmmm.server.PlayerSubscriber} was rejected deliberately (see that class): it
 * would pin a disconnected player's entity for as long as the radio kept playing. A stale
 * subscriber is a silent no-op here, which is the correct behaviour for an absent recipient.
 *
 * <p>Called from a relay session thread; the network send itself is thread-safe in 1.20.1 Forge.
 */
public final class ForgeMediaTransport implements MediaTransport {

    /**
     * Forge hard-limits a custom payload to 1 MiB ({@code ClientboundCustomPayloadPacket}). The
     * Icecast connect-burst can hand the relay tens of seconds of audio before settling, which
     * lands in one {@code sendFrames} batch and would blow that limit without chunking. 512 KiB
     * leaves headroom for the wire overhead (varints, collection length, the sessionId) and is
     * still a large enough batch to keep packet count low at normal rate.
     */
    private static final int MAX_PACKET_BYTES = 512 * 1024;

    /** Conservative per-frame overhead: streamId(4) + pts(8) + keyframe(1) + length-varint(5). */
    private static final int FRAME_OVERHEAD_BYTES = 18;

    @Override
    public void openStream(SubscriberId subscriber, int sessionId, String originName,
                           List<StreamInfo> streams, long streamEpochServerNanos,
                           int presentationDelayMs, List<MediaFrame> backlog) {
        ServerPlayer player = player(subscriber);
        if (player != null) {
            MmmmNetwork.sendToPlayer(new ClientMessages.StreamOpen(
                    sessionId, originName, streams, streamEpochServerNanos,
                    presentationDelayMs, backlog), player);
        }
    }

    @Override
    public void sendFrames(SubscriberId subscriber, int sessionId, List<MediaFrame> frames) {
        ServerPlayer player = player(subscriber);
        if (player == null) {
            return;
        }
        for (List<MediaFrame> chunk : chunkByBytes(frames)) {
            MmmmNetwork.sendToPlayer(new ClientMessages.StreamData(sessionId, chunk), player);
        }
    }

    @Override
    public void sendTitle(SubscriberId subscriber, int sessionId, long ptsMicros, String title) {
        ServerPlayer player = player(subscriber);
        if (player != null) {
            MmmmNetwork.sendToPlayer(new ClientMessages.StreamMeta(sessionId, ptsMicros, title), player);
        }
    }

    @Override
    public void closeStream(SubscriberId subscriber, int sessionId) {
        ServerPlayer player = player(subscriber);
        if (player != null) {
            MmmmNetwork.sendToPlayer(new ClientMessages.StreamClose(sessionId), player);
        }
    }

    private static ServerPlayer player(SubscriberId subscriber) {
        java.util.UUID uuid = MmmmNetwork.uuidOf(subscriber);
        if (uuid == null) {
            return null;
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        return server == null ? null : server.getPlayerList().getPlayer(uuid);
    }

    /**
     * Splits a frame list into packets that each stay under the payload limit.
     *
     * <p>One frame can itself exceed the limit (a pathological origin or a non-MP3 codec with large
     * frames). Such a frame gets its own packet and will be rejected by the network layer — that is
     * the correct outcome, since a single oversized frame means something is wrong upstream.
     */
    private static List<List<MediaFrame>> chunkByBytes(List<MediaFrame> frames) {
        if (frames.isEmpty()) {
            return List.of();
        }
        List<List<MediaFrame>> chunks = new ArrayList<>(1);
        List<MediaFrame> current = new ArrayList<>();
        int currentBytes = 0;
        for (MediaFrame frame : frames) {
            int frameBytes = frame.size() + FRAME_OVERHEAD_BYTES;
            if (!current.isEmpty() && currentBytes + frameBytes > MAX_PACKET_BYTES) {
                chunks.add(current);
                current = new ArrayList<>();
                currentBytes = 0;
            }
            current.add(frame);
            currentBytes += frameBytes;
        }
        if (!current.isEmpty()) {
            chunks.add(current);
        }
        return chunks;
    }
}
