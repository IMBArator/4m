package mmmm.core.source;

import java.util.Optional;

/**
 * What an origin advertised about itself in its response headers.
 *
 * @param name        station name from {@code icy-name}, if given
 * @param genre       from {@code icy-genre}, if given
 * @param contentType from {@code Content-Type}; a hint only — many stations lie, so
 *                    {@code FormatSniffer} takes precedence over this
 * @param bitrateKbps from {@code icy-br}, or 0 if not advertised
 */
public record SourceMetadata(
        Optional<String> name,
        Optional<String> genre,
        Optional<String> contentType,
        int bitrateKbps) {

    public static final SourceMetadata EMPTY =
            new SourceMetadata(Optional.empty(), Optional.empty(), Optional.empty(), 0);
}
