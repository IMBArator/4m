package mmmm;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The stations shipped by default, and the egress allowlist derived from them.
 *
 * <p>ADR-0011 makes the station list <em>the</em> allowed set rather than a suggestion: the server
 * opens outbound connections on behalf of players, and a blocklist fails open the moment somebody
 * finds a range nobody thought of. Default-deny means an operator who never opens the config is
 * still safe. Free-form URLs become a per-server opt-in when config arrives; until then this list is
 * the whole of what the server will connect to.
 *
 * <p>All three are 128 kbps MP3, which is what the client can decode today and what the master plan
 * recommends as a bandwidth ceiling. Note the re-streaming caveat in ADR-0003 — a relay appears to
 * the station as one listener while serving many, which is a judgement call for whoever runs the
 * server, not something code can settle.
 */
public final class Stations {

    /**
     * @param name what a player sees
     * @param url  what the server connects to
     */
    public record Station(String name, String url) {

        public URI uri() {
            return URI.create(url);
        }
    }

    public static final List<Station> DEFAULTS = List.of(
            new Station("SomaFM — Groove Salad", "https://ice1.somafm.com/groovesalad-128-mp3"),
            new Station("SomaFM — Drone Zone", "https://ice1.somafm.com/dronezone-128-mp3"),
            new Station("SomaFM — Secret Agent", "https://ice1.somafm.com/secretagent-128-mp3"));

    public static Station defaultStation() {
        return DEFAULTS.get(0);
    }

    /** The next station in the list, wrapping. Drives the sneak-right-click cycle. */
    public static Station next(String currentUrl) {
        for (int i = 0; i < DEFAULTS.size(); i++) {
            if (DEFAULTS.get(i).url().equals(currentUrl)) {
                return DEFAULTS.get((i + 1) % DEFAULTS.size());
            }
        }
        return defaultStation();
    }

    public static String displayName(String url) {
        for (Station station : DEFAULTS) {
            if (station.url().equals(url)) {
                return station.name();
            }
        }
        return url;
    }

    /** Hosts the egress guard will resolve and connect to. Nothing else is reachable. */
    public static Set<String> allowedHosts() {
        Set<String> hosts = new LinkedHashSet<>();
        for (Station station : DEFAULTS) {
            String host = station.uri().getHost();
            if (host != null) {
                hosts.add(host);
            }
        }
        return hosts;
    }

    private Stations() {
    }
}
