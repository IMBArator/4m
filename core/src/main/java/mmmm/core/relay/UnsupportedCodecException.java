package mmmm.core.relay;

import java.io.IOException;

/**
 * The origin works, but nothing downstream could ever play it.
 *
 * <p>Separate from a plain {@link IOException} because the reconnect loop treats the two very
 * differently: a network fault is retried indefinitely, whereas a stream we cannot decode will still
 * be undecodable on the fiftieth attempt. Retrying that is a busy loop against someone else's
 * server, so it moves the session to {@link SessionState#FAILED} instead.
 */
public class UnsupportedCodecException extends IOException {

    private static final long serialVersionUID = 1L;

    public UnsupportedCodecException(String message) {
        super(message);
    }
}
