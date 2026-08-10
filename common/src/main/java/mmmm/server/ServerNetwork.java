package mmmm.server;

import mmmm.Stations;
import mmmm.block.RadioBlockEntity;
import mmmm.client.ClientMessages;
import mmmm.core.security.EgressDeniedException;
import mmmm.core.security.EgressGuard;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.net.URI;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

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
 */
public final class ServerNetwork {

    /** Eight blocks. Comfortably past reach, far short of "any radio in the world". */
    private static final double MAX_REACH_SQ = 64.0;

    private static final Pattern IPV4_LITERAL = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");

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

    /** @return true if the station was accepted and applied */
    private static boolean applyStation(ServerPlayer sender, RadioBlockEntity radio, String url) {
        if (isShippedStation(url)) {
            setStation(radio, url);
            return true;
        }

        // Anything not on the shipped list is a free-form URL, which ADR-0011 gates on permission
        // level. This is the gate; the screen hiding the button is not.
        if (!sender.hasPermissions(2)) {
            refuse(sender, "Only server operators may set a custom station.");
            return false;
        }

        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            refuse(sender, "That is not a valid URL.");
            return false;
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            refuse(sender, "Station URLs must start with http:// or https://");
            return false;
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            refuse(sender, "That URL has no host.");
            return false;
        }

        if (!checkLiteralAddress(sender, uri, host)) {
            return false;
        }

        if (!RadioServer.authoriseHost(host)) {
            refuse(sender, "Could not authorise that host — the server's allowlist is full.");
            return false;
        }
        setStation(radio, url);
        sender.sendSystemMessage(Component.literal("Station set. " + host + " is now allowed on this server.")
                .withStyle(ChatFormatting.GRAY));
        return true;
    }

    /**
     * Refuses an address literal that points somewhere it should not, without touching DNS.
     *
     * <p>This is deliberately only half a check. The full one — resolve the name, then refuse if
     * <em>any</em> resolved address is loopback, RFC1918, CGNAT or link-local — runs in
     * {@code EgressGuard} on the relay thread when the connection is actually made, and that is
     * where it must run, because it blocks on DNS and this method is on the server thread. A name
     * lookup here would stall every player on the server for as long as the resolver took.
     *
     * <p>What it does buy is an immediate, explained refusal for the cases someone would actually
     * type on purpose — {@code 127.0.0.1}, {@code 10.x}, and the cloud metadata endpoint at
     * {@code 169.254.169.254}. Those are literals, and {@code getAllByName} does not resolve a
     * literal, so the real guard can be asked about them for free.
     *
     * <p>A hostname that resolves somewhere blocked is still refused — one round trip later, by the
     * relay, which stops the block and reports {@code FAILED}.
     */
    private static boolean checkLiteralAddress(ServerPlayer sender, URI uri, String host) {
        String bare = host.startsWith("[") && host.endsWith("]")
                ? host.substring(1, host.length() - 1)
                : host;
        boolean literal = IPV4_LITERAL.matcher(bare).matches() || bare.indexOf(':') >= 0;
        if (!literal) {
            return true;
        }
        try {
            EgressGuard.allowing(Set.of(host.toLowerCase(Locale.ROOT))).check(uri);
            return true;
        } catch (EgressDeniedException e) {
            refuse(sender, e.getMessage());
            return false;
        }
    }

    private static void setStation(RadioBlockEntity radio, String url) {
        radio.setStation(url);
        // A stale FAILED from the previous station would otherwise sit there until the next tick,
        // claiming the newly chosen station had already failed.
        radio.setSessionState(null);
    }

    private static boolean isShippedStation(String url) {
        for (Stations.Station station : Stations.DEFAULTS) {
            if (station.url().equals(url)) {
                return true;
            }
        }
        return false;
    }

    private static void refuse(ServerPlayer sender, String reason) {
        sender.sendSystemMessage(Component.literal(reason).withStyle(ChatFormatting.RED));
    }
}
