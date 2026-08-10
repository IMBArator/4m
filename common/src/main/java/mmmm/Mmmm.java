package mmmm;

/**
 * Constants shared by every module, loader-agnostic.
 *
 * <p>Lives in {@code common/}, which is compiled once per loader (ADR-0002), so nothing here may
 * reference a loader API.
 */
public final class Mmmm {

    public static final String MOD_ID = "mmmm";

    private Mmmm() {
    }
}
