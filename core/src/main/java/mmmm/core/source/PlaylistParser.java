package mmmm.core.source;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Parses the playlist formats that station links actually point at.
 *
 * <p>A "station URL" is very often a playlist rather than audio — copying a link from a station's
 * website usually yields a {@code .pls} or {@code .m3u}. Pure functions, no IO, so the parsing is
 * testable directly.
 */
public final class PlaylistParser {

    /** How a playlist should be consumed. */
    public enum Kind {
        /** Shoutcast {@code .pls}: an INI file with {@code FileN=} entries. */
        PLS,
        /** Plain {@code .m3u}: one URL per line. */
        M3U,
        /** HLS: an {@code .m3u8} with {@code #EXT-X-} tags. A different transport, not a redirect. */
        HLS,
        /** Not a playlist. */
        NONE
    }

    private PlaylistParser() {
    }

    /**
     * Classifies playlist text by content.
     *
     * <p>Content, not extension: plenty of stations serve a {@code .m3u} that is really HLS, or an
     * extensionless URL that returns a {@code .pls}.
     */
    public static Kind classify(String text) {
        if (text == null || text.isBlank()) {
            return Kind.NONE;
        }
        String head = text.length() > 4096 ? text.substring(0, 4096) : text;

        // HLS is distinguished by its EXT-X tags, not by #EXTM3U, which plain m3u also uses.
        if (head.contains("#EXT-X-")) {
            return Kind.HLS;
        }
        String upper = head.toUpperCase(Locale.ROOT);
        if (upper.contains("[PLAYLIST]") || upper.contains("FILE1=")) {
            return Kind.PLS;
        }
        if (head.contains("#EXTM3U") || head.contains("#EXTINF")) {
            return Kind.M3U;
        }
        // An extensionless body that is only URLs is still a usable m3u.
        for (String line : head.split("\\R")) {
            String t = line.trim();
            if (!t.isEmpty() && !t.startsWith("#")) {
                return looksLikeUrl(t) ? Kind.M3U : Kind.NONE;
            }
        }
        return Kind.NONE;
    }

    /**
     * Extracts stream URLs in preference order.
     *
     * <p>{@code .pls} entries are ordered by their {@code FileN} index, because stations list
     * fallback mirrors after the primary and the numbering is the only thing carrying that intent —
     * text order does not reliably reflect it.
     *
     * @return possibly empty, never null
     */
    public static List<String> extractUrls(String text, Kind kind) {
        return switch (kind) {
            case PLS -> parsePls(text);
            case M3U, HLS -> parseM3u(text);
            case NONE -> List.of();
        };
    }

    private static List<String> parsePls(String text) {
        record Entry(int index, String url) {
        }
        List<Entry> entries = new ArrayList<>();

        for (String line : text.split("\\R")) {
            String trimmed = line.trim();
            int eq = trimmed.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = trimmed.substring(0, eq).trim().toLowerCase(Locale.ROOT);
            String value = trimmed.substring(eq + 1).trim();
            if (!key.startsWith("file") || value.isEmpty()) {
                continue;
            }
            int index;
            try {
                index = Integer.parseInt(key.substring(4));
            } catch (NumberFormatException e) {
                index = Integer.MAX_VALUE;
            }
            if (looksLikeUrl(value)) {
                entries.add(new Entry(index, value));
            }
        }
        entries.sort((a, b) -> Integer.compare(a.index(), b.index()));
        return entries.stream().map(Entry::url).toList();
    }

    private static List<String> parseM3u(String text) {
        List<String> urls = new ArrayList<>();
        for (String line : text.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            urls.add(trimmed);
        }
        return urls;
    }

    private static boolean looksLikeUrl(String s) {
        String lower = s.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    /** Whether a URL's extension suggests a playlist, used to decide whether to probe at all. */
    public static boolean hasPlaylistExtension(String path) {
        if (path == null) {
            return false;
        }
        String lower = path.toLowerCase(Locale.ROOT);
        int query = lower.indexOf('?');
        if (query >= 0) {
            lower = lower.substring(0, query);
        }
        return lower.endsWith(".pls") || lower.endsWith(".m3u") || lower.endsWith(".m3u8");
    }
}
