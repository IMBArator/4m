package mmmm.core.security;

import java.io.IOException;

/**
 * Thrown when {@link EgressGuard} refuses a destination.
 *
 * <p>An {@link IOException} rather than a {@link RuntimeException}: every call site is already
 * handling network failure, and a refused destination is a failure to reach the origin. Making it
 * unchecked would invite callers to let it escape into a thread's uncaught handler, silently
 * killing a relay session instead of surfacing it as a stream error the player can see.
 */
public class EgressDeniedException extends IOException {

    private static final long serialVersionUID = 1L;

    public EgressDeniedException(String message) {
        super(message);
    }
}
