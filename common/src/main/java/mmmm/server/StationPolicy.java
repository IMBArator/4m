package mmmm.server;

import mmmm.Stations;
import mmmm.block.RadioBlockEntity;
import mmmm.core.security.EgressDeniedException;
import mmmm.core.security.EgressGuard;

import java.net.URI;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Who may point a radio at what — ADR-0011's gate, in one place.
 *
 * <p>These were private methods on {@link ServerNetwork}, which made the server's egress policy a
 * detail of a packet handler. Separating them is worth doing on its own: the rules here are the
 * enforcement half of an ADR and are the thing to read, and to test, when asking what this server
 * will connect to — none of which is true of a method buried in a message decoder. It also means the
 * next entry point that can change a station cannot quietly reimplement half the checks.
 *
 * <p>{@link ServerNetwork} is currently the only caller. That is not an argument against the split;
 * a security boundary is easier to keep correct when it is named and separate than when it is
 * scattered through whichever class happened to need it first.
 *
 * <p>Reporting is deliberately not done here. A verdict carries a message and the caller delivers
 * it, so the policy has no opinion about whether the answer goes to a player, a console or a log.
 */
public final class StationPolicy {

    private static final Pattern IPV4_LITERAL = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");

    private StationPolicy() {
    }

    /**
     * The outcome of vetting a station.
     *
     * @param accepted whether the station may be used
     * @param message  why it was refused, or a note worth showing on acceptance; null if there is
     *                 nothing to say
     */
    public record Verdict(boolean accepted, String message) {

        static Verdict ok() {
            return new Verdict(true, null);
        }

        static Verdict ok(String note) {
            return new Verdict(true, note);
        }

        static Verdict refused(String why) {
            return new Verdict(false, why);
        }
    }

    /**
     * Vets a station URL and, if it is a custom one from someone entitled to set it, authorises its
     * host for this world.
     *
     * <p>The authorisation is a real side effect and a persistent one — hence the name. It is what
     * makes an operator's choice survive a restart, and ADR-0011 explains why that has to happen at
     * the moment of choosing rather than in a config file.
     *
     * <p>Server thread only: authorising writes world data.
     *
     * @param mayUseCustomStation whether this caller is entitled to reach beyond the shipped list.
     *                            The caller establishes this — permission level 2 for a player, the
     *                            command's own {@code requires} for a command — because "operator"
     *                            means something slightly different in each case.
     */
    public static Verdict vetAndAuthorise(String url, boolean mayUseCustomStation) {
        if (url == null || url.isBlank()) {
            return Verdict.refused("No station given.");
        }
        if (isShippedStation(url)) {
            return Verdict.ok();
        }

        // Anything off the shipped list is a free-form URL, which ADR-0011 gates on permission.
        // This is the gate. The screen hiding its button, and the command's requires(), are not.
        if (!mayUseCustomStation) {
            return Verdict.refused("Only server operators may set a custom station.");
        }

        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            return Verdict.refused("That is not a valid URL.");
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            return Verdict.refused("Station URLs must start with http:// or https://");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return Verdict.refused("That URL has no host.");
        }

        String literalRefusal = refuseBlockedLiteral(uri, host);
        if (literalRefusal != null) {
            return Verdict.refused(literalRefusal);
        }

        if (!RadioServer.authoriseHost(host)) {
            return Verdict.refused("Could not authorise that host — the server's allowlist is full.");
        }
        return Verdict.ok("Station set. " + host + " is now allowed on this server.");
    }

    /**
     * Points a radio at a station that has already been vetted.
     *
     * <p>Shared rather than inlined at both call sites for the second line: a stale {@code FAILED}
     * left over from the previous station would otherwise sit on the screen until the next tick,
     * claiming the station just chosen had already failed. That is precisely the sort of detail a
     * second entry point copies wrongly or not at all.
     */
    public static void apply(RadioBlockEntity radio, String url) {
        radio.setStation(url);
        radio.setSessionState(null);
    }

    public static boolean isShippedStation(String url) {
        for (Stations.Station station : Stations.DEFAULTS) {
            if (station.url().equals(url)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Refuses an address literal that points somewhere it should not, without touching DNS.
     *
     * <p>Deliberately only half a check. The full one — resolve the name, then refuse if <em>any</em>
     * resolved address is loopback, RFC1918, CGNAT or link-local — runs in {@link EgressGuard} on the
     * relay thread when the connection is actually made, and that is where it must run, because it
     * blocks on DNS and this runs on the server thread. A name lookup here would stall every player
     * on the server for as long as the resolver took.
     *
     * <p>What it buys is an immediate, explained refusal for the cases someone would type on
     * purpose — {@code 127.0.0.1}, {@code 10.x}, and the cloud metadata endpoint at
     * {@code 169.254.169.254}. Those are literals, and {@code getAllByName} does not resolve a
     * literal, so the real guard can be asked about them for free.
     *
     * <p>A hostname that resolves somewhere blocked is still refused — one round trip later, by the
     * relay, which stops the block and reports {@code FAILED}.
     *
     * @return the refusal reason, or null if there is nothing to refuse here
     */
    private static String refuseBlockedLiteral(URI uri, String host) {
        String bare = host.startsWith("[") && host.endsWith("]")
                ? host.substring(1, host.length() - 1)
                : host;
        boolean literal = IPV4_LITERAL.matcher(bare).matches() || bare.indexOf(':') >= 0;
        if (!literal) {
            return null;
        }
        try {
            EgressGuard.allowing(Set.of(host.toLowerCase(Locale.ROOT))).check(uri);
            return null;
        } catch (EgressDeniedException e) {
            return e.getMessage();
        }
    }
}
