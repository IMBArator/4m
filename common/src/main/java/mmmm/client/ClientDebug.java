package mmmm.client;

import java.util.function.BooleanSupplier;

/**
 * Whether the client's diagnostics are switched on.
 *
 * <p>Loader-neutral by the usual seam (ADR-0002): the config file itself is {@code ForgeConfigSpec},
 * which is loader API this package may not touch, so the loader installs suppliers here.
 *
 * <p>Suppliers rather than copied booleans, because Forge config values change under you — editing
 * the file, or a config screen, fires a reload without restarting the game. A snapshot taken at
 * startup would silently ignore that, which is a poor property for the switch that turns diagnostics
 * on when something is going wrong.
 *
 * <p>Defaults are off. Nothing installed means nothing rendered and nothing logged, which is what a
 * dedicated server and any test harness should see.
 */
public final class ClientDebug {

    private static volatile BooleanSupplier syncReadout = () -> false;
    private static volatile BooleanSupplier syncLog = () -> false;

    private ClientDebug() {
    }

    public static void install(BooleanSupplier readout, BooleanSupplier log) {
        syncReadout = readout;
        syncLog = log;
    }

    /** Show the sync-health line on the radio's control panel. */
    public static boolean syncReadout() {
        return syncReadout.getAsBoolean();
    }

    /** Write the same line to the client log once a second, for sessions with audio. */
    public static boolean syncLog() {
        return syncLog.getAsBoolean();
    }
}
