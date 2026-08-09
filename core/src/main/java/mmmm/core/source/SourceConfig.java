package mmmm.core.source;

/**
 * Limits applied to an origin connection.
 *
 * <p>Every field is a bound on what a hostile or broken origin can cost us. Defaults are chosen to
 * be generous for real stations and still finite.
 *
 * @param connectTimeoutMs TCP connect timeout
 * @param readTimeoutMs    socket read timeout; a live stream that goes this long without a byte is
 *                         treated as dead so the reconnect backoff can take over
 * @param maxRedirects     redirect hops before giving up
 * @param maxHeaderBytes   cap on the response head, so an origin cannot stream headers forever
 * @param userAgent        some stations reject the default Java agent with 403
 */
public record SourceConfig(
        int connectTimeoutMs,
        int readTimeoutMs,
        int maxRedirects,
        int maxHeaderBytes,
        String userAgent) {

    public static final SourceConfig DEFAULT = new SourceConfig(
            10_000,
            30_000,
            5,
            64 * 1024,
            "mmmm/0.1 (Minecraft mod)");

    public SourceConfig {
        if (connectTimeoutMs <= 0) throw new IllegalArgumentException("connectTimeoutMs must be positive");
        if (readTimeoutMs <= 0) throw new IllegalArgumentException("readTimeoutMs must be positive");
        if (maxRedirects < 0) throw new IllegalArgumentException("maxRedirects must be >= 0");
        if (maxHeaderBytes <= 0) throw new IllegalArgumentException("maxHeaderBytes must be positive");
        if (userAgent == null || userAgent.isBlank()) throw new IllegalArgumentException("userAgent must be set");
    }
}
