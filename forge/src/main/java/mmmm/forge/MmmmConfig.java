package mmmm.forge;

import net.minecraftforge.common.ForgeConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

/**
 * The client config file, {@code config/mmmm-client.toml}.
 *
 * <p>Here rather than in {@code common/} because {@code ForgeConfigSpec} is loader API (ADR-0002).
 * Shared code reads the values through {@link mmmm.client.ClientDebug}, which the loader installs.
 *
 * <p>Registered as {@code CLIENT} type: these settings describe what one player sees and logs, so
 * they are per-installation and are not sent to or from a server.
 */
public final class MmmmConfig {

    public static final ForgeConfigSpec CLIENT_SPEC;
    private static final Client CLIENT;

    static {
        Pair<Client, ForgeConfigSpec> pair = new ForgeConfigSpec.Builder().configure(Client::new);
        CLIENT = pair.getLeft();
        CLIENT_SPEC = pair.getRight();
    }

    private MmmmConfig() {
    }

    public static boolean syncReadout() {
        return CLIENT.syncReadout.get();
    }

    public static boolean syncLog() {
        return CLIENT.syncLog.get();
    }

    private static final class Client {

        private final ForgeConfigSpec.BooleanValue syncReadout;
        private final ForgeConfigSpec.BooleanValue syncLog;

        private Client(ForgeConfigSpec.Builder builder) {
            builder.comment("Diagnostics for the synchronised playback path.",
                            "Both are read live: changing them takes effect without restarting the game.")
                    .push("debug");

            // On by default. This is the only instrument the sync work has, and it has already been
            // the difference between "sounds fine" and a client hard-resyncing twenty times a second.
            // Turn it off once playback is trustworthy.
            syncReadout = builder
                    .comment("Show the sync-health line at the bottom of the radio's control panel.",
                            "drift, buffer depth, rate trim, round-trip time, and any resync rate.")
                    .define("syncReadout", true);

            // Off by default: one line per second per playing radio is a lot of log for someone who
            // is not currently diagnosing anything.
            syncLog = builder
                    .comment("Write the same line to the client log once a second while audio plays.",
                            "For diagnosing sync over time, or when the panel cannot be kept open.")
                    .define("syncLog", false);

            builder.pop();
        }
    }
}
