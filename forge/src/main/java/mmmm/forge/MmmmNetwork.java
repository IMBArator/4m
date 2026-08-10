package mmmm.forge;

import mmmm.Mmmm;
import mmmm.client.ClientMessages;
import mmmm.client.ClientNetwork;
import mmmm.core.media.Codec;
import mmmm.core.media.MediaFrame;
import mmmm.core.media.StreamInfo;
import mmmm.server.PlayerSubscriber;
import mmmm.server.ServerNetwork;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The Minecraft-connection side of {@link mmmm.core.transport.MediaTransport} (ADR-0006), plus the
 * clock ping/pong exchange that rides on the same channel.
 *
 * <p>Six messages: five S2C media + clock messages and one C2S clock ping (master plan §6.1). All
 * media sends route through here rather than touching {@code SimpleChannel} from {@code :core}; that
 * is what keeps {@code :core} loader-free by construction rather than by convention.
 *
 * <p>The message <em>types</em> are the loader-neutral records in {@link ClientMessages}; only the
 * codec (encode/decode over a {@link FriendlyByteBuf}) and the {@link NetworkEvent.Context}
 * handling are Forge-specific. Splitting them this way lets shared code receive packets without
 * depending on a loader package (ADR-0002).
 *
 * <p>The channel version is a fixed {@code "1"}. Both peers reject a mismatch, which is the right
 * behaviour: an old client receiving a {@link ClientMessages.StreamData} it cannot decode is worse
 * than a clean refusal.
 */
public final class MmmmNetwork {

    private static final String PROTOCOL_VERSION = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            Mmmm.id("main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals);

    private MmmmNetwork() {
    }

    public static void register() {
        int id = 0;
        // The 5-arg overload omits the optional NetworkDirection enforcement; the codec infers the
        // direction from which side sends each message, and the message classes themselves enforce
        // routing by existing only on one side's handler list.
        CHANNEL.registerMessage(id, ClientMessages.StreamOpen.class,
                MmmmNetwork::encodeStreamOpen, MmmmNetwork::decodeStreamOpen, MmmmNetwork::handleStreamOpen);
        CHANNEL.registerMessage(++id, ClientMessages.StreamData.class,
                MmmmNetwork::encodeStreamData, MmmmNetwork::decodeStreamData, MmmmNetwork::handleStreamData);
        CHANNEL.registerMessage(++id, ClientMessages.StreamMeta.class,
                MmmmNetwork::encodeStreamMeta, MmmmNetwork::decodeStreamMeta, MmmmNetwork::handleStreamMeta);
        CHANNEL.registerMessage(++id, ClientMessages.StreamClose.class,
                MmmmNetwork::encodeStreamClose, MmmmNetwork::decodeStreamClose, MmmmNetwork::handleStreamClose);
        CHANNEL.registerMessage(++id, ClientMessages.ClockPong.class,
                MmmmNetwork::encodeClockPong, MmmmNetwork::decodeClockPong, MmmmNetwork::handleClockPong);
        CHANNEL.registerMessage(++id, ClientMessages.ClockPing.class,
                MmmmNetwork::encodeClockPing, MmmmNetwork::decodeClockPing, MmmmNetwork::handleClockPing);
        CHANNEL.registerMessage(++id, ClientMessages.ConfigureRadio.class,
                MmmmNetwork::encodeConfigureRadio, MmmmNetwork::decodeConfigureRadio,
                MmmmNetwork::handleConfigureRadio);
    }

    // ------------------------------------------------------------------ sends

    public static void sendToPlayer(Object message, ServerPlayer player) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), message);
    }

    public static void sendToServer(Object message) {
        CHANNEL.sendToServer(message);
    }

    // ------------------------------------------------------------------ StreamOpen

    private static void encodeStreamOpen(ClientMessages.StreamOpen msg, FriendlyByteBuf buf) {
        buf.writeInt(msg.sessionId());
        buf.writeUtf(msg.originName());
        buf.writeCollection(msg.streams(), MmmmNetwork::writeStreamInfo);
        buf.writeLong(msg.epochNanos());
        buf.writeInt(msg.presentationDelayMs());
        buf.writeCollection(msg.backlog(), MmmmNetwork::writeMediaFrame);
    }

    private static ClientMessages.StreamOpen decodeStreamOpen(FriendlyByteBuf buf) {
        return new ClientMessages.StreamOpen(
                buf.readInt(),
                buf.readUtf(),
                buf.readCollection(ArrayList::new, MmmmNetwork::readStreamInfo),
                buf.readLong(),
                buf.readInt(),
                buf.readCollection(ArrayList::new, MmmmNetwork::readMediaFrame));
    }

    private static void handleStreamOpen(ClientMessages.StreamOpen msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> ClientNetwork.onStreamOpen(msg));
        ctx.get().setPacketHandled(true);
    }

    // ------------------------------------------------------------------ StreamData

    private static void encodeStreamData(ClientMessages.StreamData msg, FriendlyByteBuf buf) {
        buf.writeInt(msg.sessionId());
        buf.writeCollection(msg.frames(), MmmmNetwork::writeMediaFrame);
    }

    private static ClientMessages.StreamData decodeStreamData(FriendlyByteBuf buf) {
        return new ClientMessages.StreamData(
                buf.readInt(),
                buf.readCollection(ArrayList::new, MmmmNetwork::readMediaFrame));
    }

    private static void handleStreamData(ClientMessages.StreamData msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> ClientNetwork.onStreamData(msg));
        ctx.get().setPacketHandled(true);
    }

    // ------------------------------------------------------------------ StreamMeta

    private static void encodeStreamMeta(ClientMessages.StreamMeta msg, FriendlyByteBuf buf) {
        buf.writeInt(msg.sessionId());
        buf.writeLong(msg.ptsMicros());
        buf.writeUtf(msg.title());
    }

    private static ClientMessages.StreamMeta decodeStreamMeta(FriendlyByteBuf buf) {
        return new ClientMessages.StreamMeta(buf.readInt(), buf.readLong(), buf.readUtf());
    }

    private static void handleStreamMeta(ClientMessages.StreamMeta msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> ClientNetwork.onStreamMeta(msg));
        ctx.get().setPacketHandled(true);
    }

    // ------------------------------------------------------------------ StreamClose

    private static void encodeStreamClose(ClientMessages.StreamClose msg, FriendlyByteBuf buf) {
        buf.writeInt(msg.sessionId());
    }

    private static ClientMessages.StreamClose decodeStreamClose(FriendlyByteBuf buf) {
        return new ClientMessages.StreamClose(buf.readInt());
    }

    private static void handleStreamClose(ClientMessages.StreamClose msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> ClientNetwork.onStreamClose(msg));
        ctx.get().setPacketHandled(true);
    }

    // ------------------------------------------------------------------ ClockPong (S2C)

    private static void encodeClockPong(ClientMessages.ClockPong msg, FriendlyByteBuf buf) {
        buf.writeLong(msg.clientNanos());
        buf.writeLong(msg.serverNanos());
    }

    private static ClientMessages.ClockPong decodeClockPong(FriendlyByteBuf buf) {
        return new ClientMessages.ClockPong(buf.readLong(), buf.readLong());
    }

    private static void handleClockPong(ClientMessages.ClockPong msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> ClientNetwork.onClockPong(msg));
        ctx.get().setPacketHandled(true);
    }

    // ------------------------------------------------------------------ ClockPing (C2S)

    private static void encodeClockPing(ClientMessages.ClockPing msg, FriendlyByteBuf buf) {
        buf.writeLong(msg.clientNanos());
    }

    private static ClientMessages.ClockPing decodeClockPing(FriendlyByteBuf buf) {
        return new ClientMessages.ClockPing(buf.readLong());
    }

    private static void handleClockPing(ClientMessages.ClockPing msg, Supplier<NetworkEvent.Context> ctx) {
        // Same thread, same JVM, same nanoTime origin as RelaySession's epoch — that is what makes
        // the offset meaningful to the client's drift loop.
        ServerPlayer sender = ctx.get().getSender();
        if (sender != null) {
            sendToPlayer(new ClientMessages.ClockPong(msg.clientNanos(), System.nanoTime()), sender);
        }
        ctx.get().setPacketHandled(true);
    }

    // ------------------------------------------------------------------ ConfigureRadio (C2S)

    /**
     * Wire cap on a station URL. Well past any real one, well under {@code readUtf}'s 32767 default —
     * this is the only client-supplied string the server stores, so it gets an explicit bound.
     */
    private static final int MAX_STATION_LENGTH = 512;

    private static void encodeConfigureRadio(ClientMessages.ConfigureRadio msg, FriendlyByteBuf buf) {
        buf.writeBlockPos(msg.pos());
        buf.writeUtf(msg.station(), MAX_STATION_LENGTH);
        buf.writeBoolean(msg.playing());
        buf.writeFloat(msg.volume());
    }

    private static ClientMessages.ConfigureRadio decodeConfigureRadio(FriendlyByteBuf buf) {
        return new ClientMessages.ConfigureRadio(
                buf.readBlockPos(),
                buf.readUtf(MAX_STATION_LENGTH),
                buf.readBoolean(),
                buf.readFloat());
    }

    private static void handleConfigureRadio(ClientMessages.ConfigureRadio msg,
                                             Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        // enqueueWork, unlike handleClockPing above. That one stays on the network thread on purpose;
        // this one reads and writes block entities and world data, which is server-thread-only, and
        // doing it here would be a data race that shows up as corrupt saves rather than as an error.
        context.enqueueWork(() -> ServerNetwork.onConfigureRadio(context.getSender(), msg));
        context.setPacketHandled(true);
    }

    // ------------------------------------------------------------------ serialisation helpers

    private static void writeStreamInfo(FriendlyByteBuf buf, StreamInfo info) {
        buf.writeInt(info.streamId());
        buf.writeEnum(info.codec());
        buf.writeInt(info.sampleRate());
        buf.writeInt(info.channels());
        buf.writeInt(info.width());
        buf.writeInt(info.height());
        buf.writeByteArray(info.codecInit());
    }

    private static StreamInfo readStreamInfo(FriendlyByteBuf buf) {
        return new StreamInfo(
                buf.readInt(),
                buf.readEnum(Codec.class),
                buf.readInt(),
                buf.readInt(),
                buf.readInt(),
                buf.readInt(),
                buf.readByteArray());
    }

    private static void writeMediaFrame(FriendlyByteBuf buf, MediaFrame frame) {
        buf.writeInt(frame.streamId());
        buf.writeLong(frame.ptsMicros());
        buf.writeBoolean(frame.keyframe());
        buf.writeByteArray(frame.payload());
    }

    private static MediaFrame readMediaFrame(FriendlyByteBuf buf) {
        return new MediaFrame(
                buf.readInt(),
                buf.readLong(),
                buf.readBoolean(),
                buf.readByteArray());
    }

    /** Typed helper for the transport: unwrap a {@link PlayerSubscriber} to its UUID. */
    static UUID uuidOf(mmmm.core.transport.MediaTransport.SubscriberId subscriber) {
        if (subscriber instanceof PlayerSubscriber ps) {
            return ps.uuid();
        }
        return null;
    }
}
