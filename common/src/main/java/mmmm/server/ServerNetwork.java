package mmmm.server;

import mmmm.block.RadioBlockEntity;
import mmmm.client.ClientMessages;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

/**
 * What the server does when a player changes a radio's settings.
 *
 * <p>The server-side mirror of {@link mmmm.client.ClientNetwork}: loader-neutral logic, so the
 * loader modules keep only the wire codec and the hop onto the server thread (ADR-0002).
 *
 * <h2>Everything here is hostile input</h2>
 * The media channel registers its messages without direction enforcement, so any connected client
 * can send a {@link ClientMessages.ConfigureRadio} for any position with any string in it. The
 * screen disables the controls a player may not use, but that is decoration; this class is the
 * actual boundary. It re-derives every permission from the sender.
 *
 * <p>What this class checks is the <em>sender</em>: that they exist, that the position is loaded,
 * that they are close enough, and that there is a radio there. What a station URL is allowed to be
 * is {@link StationPolicy}'s question, not this one's.
 */
public final class ServerNetwork {

    /** Eight blocks. Comfortably past reach, far short of "any radio in the world". */
    private static final double MAX_REACH_SQ = 64.0;

    private ServerNetwork() {
    }

    /**
     * Applies a configuration change, if the sender is allowed to make it.
     *
     * <p>Must run on the server thread — it reads and writes block entities and world data.
     */
    public static void onConfigureRadio(ServerPlayer sender, ClientMessages.ConfigureRadio msg) {
        if (sender == null) {
            return;
        }
        Level level = sender.level();
        BlockPos pos = msg.pos();

        // isLoaded first: getBlockEntity on an unloaded position would drag the chunk in, which is a
        // free way for a client to make the server load arbitrary chunks.
        if (!level.isLoaded(pos)) {
            return;
        }
        if (sender.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > MAX_REACH_SQ) {
            return;
        }
        if (!(level.getBlockEntity(pos) instanceof RadioBlockEntity radio)) {
            return;
        }

        // Remembered so a failure that only surfaces later, on the relay thread, can still be
        // reported to the person who asked for it rather than only to the server log.
        radio.setLastConfiguredBy(sender.getUUID());

        // setVolume clamps; nothing a client can put in a float needs rejecting beyond that.
        radio.setVolume(msg.volume());

        String requested = msg.station() == null ? "" : msg.station().trim();
        boolean stationChanged = !requested.isEmpty() && !requested.equals(radio.getStation());
        if (stationChanged && !applyStation(sender, radio, requested)) {
            // Refused: leave the station alone, and do not honour a play request for it either.
            return;
        }

        radio.setPlaying(msg.playing());
    }

    /**
     * @return true if the station was accepted and applied
     * @see StationPolicy for what "accepted" means and why the rules live elsewhere
     */
    private static boolean applyStation(ServerPlayer sender, RadioBlockEntity radio, String url) {
        StationPolicy.Verdict verdict = StationPolicy.vetAndAuthorise(url, sender.hasPermissions(2));
        if (!verdict.accepted()) {
            sender.sendSystemMessage(Component.literal(verdict.message()).withStyle(ChatFormatting.RED));
            return false;
        }
        StationPolicy.apply(radio, url);
        if (verdict.message() != null) {
            sender.sendSystemMessage(Component.literal(verdict.message()).withStyle(ChatFormatting.GRAY));
        }
        return true;
    }
}
