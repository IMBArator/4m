package mmmm.forge;

import mmmm.Mmmm;
import mmmm.MmmmContent;
import mmmm.client.ClientMedia;
import mmmm.core.relay.RelayConfig;
import mmmm.core.relay.RelayManager;
import mmmm.core.relay.SourceOpener;
import mmmm.core.source.SourceConfig;
import mmmm.forge.client.ForgeClientSetup;
import mmmm.server.RadioCommands;
import mmmm.server.RadioServer;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;

/**
 * Forge entry point.
 *
 * <p>One job: install the registries, the network channel and the event listeners that drive
 * server- and client-side state, then get out of the way. Everything else lives in shared code
 * under {@code common/} and {@code :core}.
 *
 * <p>Listeners are split across the two event buses by Forge's rule: {@code IModBusEvent}s go on
 * the mod bus (registries, setup); gameplay-tick and lifecycle events go on the Forge bus. Putting
 * them on the wrong bus is a silent no-op, which is the kind of bug that takes an evening to find.
 */
@Mod(Mmmm.MOD_ID)
public final class MmmmForge {

    public MmmmForge() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();

        // Registered on both dists. The spec itself names no client type, so a dedicated server
        // parses it harmlessly and simply never reads the values.
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, MmmmConfig.CLIENT_SPEC,
                Mmmm.MOD_ID + "-client.toml");

        Registration.BLOCKS.register(modBus);
        Registration.ITEMS.register(modBus);
        Registration.BLOCK_ENTITIES.register(modBus);
        Registration.SOUND_EVENTS.register(modBus);

        modBus.addListener(MmmmForge::commonSetup);
        modBus.addListener(MmmmForge::clientSetup);
        modBus.addListener(MmmmForge::addCreativeTabContents);

        IEventBus forgeBus = MinecraftForge.EVENT_BUS;
        forgeBus.addListener(MmmmForge::serverTick);
        forgeBus.addListener(MmmmForge::registerCommands);
        forgeBus.addListener(MmmmForge::serverStarting);
        forgeBus.addListener(MmmmForge::serverStopping);
        forgeBus.addListener(MmmmForge::playerLoggedOut);
        forgeBus.addListener(MmmmForge::clientTick);
        // NOTE: ClientPlayerNetworkEvent.LoggingOut is NOT hooked here. In singleplayer it fires
        // spuriously (on ESC pause, on auto-save, during dimension change), and calling shutdown()
        // on each fire wipes every active session. The stale sweep in ClientMedia.onClientTick
        // already handles real cleanup: when the player leaves the world, block entities stop
        // ticking, and sessions are reaped after the grace period.
    }

    // ------------------------------------------------------------------ setup

    private static void commonSetup(FMLCommonSetupEvent event) {
        // The channel must be registered before any packet could possibly be sent. Common setup is
        // well before play, so this is conservative.
        MmmmNetwork.register();

        // Hand the registry objects to the shared code that needs them. DeferredRegister entries
        // are populated by the time common setup fires.
        MmmmContent.bind(Registration.RADIO_BLOCK_ENTITY, Registration.RADIO_STREAM);
    }

    /**
     * Delegates, and must keep delegating — the body stays a single argument-less static call.
     *
     * <p>This class is loaded and verified on a dedicated server. Inlining any of the wiring back
     * here puts its bytecode in <em>this</em> class, and the verifier then loads the client types it
     * mentions to type-check them, which Forge's dist cleaner turns into a hard crash at mod
     * construction. {@link ForgeClientSetup} carries the full account.
     */
    private static void clientSetup(FMLClientSetupEvent event) {
        ForgeClientSetup.install();
    }

    // ------------------------------------------------------------------ server lifecycle

    private static void serverStarting(ServerStartingEvent event) {
        // Fresh manager per server run. RadioServer.shutdown() nulls the previous one, so without
        // this a singleplayer world loaded after another would have no relay at all.
        //
        // Per station, not one fixed guard: an operator's authorisation applies to that station's
        // whole resolution chain, because a playlist normally names a host on another domain
        // (ADR-0011 amendment).
        SourceOpener opener = SourceOpener.network(RadioServer::egressGuardFor, SourceConfig.DEFAULT);
        RadioServer.install(event.getServer(),
                new RelayManager(opener, RelayConfig.DEFAULT, new ForgeMediaTransport()));
    }

    private static void serverStopping(ServerStoppingEvent event) {
        RadioServer.shutdown();
    }

    /**
     * Fires on every world load, and on {@code /reload} — so the dispatcher is a fresh one each time
     * and the tree has to be rebuilt rather than cached.
     */
    private static void registerCommands(RegisterCommandsEvent event) {
        RadioCommands.register(event.getDispatcher());
    }

    private static void serverTick(TickEvent.ServerTickEvent event) {
        // END, not START: every block entity has ticked by then, so the proximity union this tick
        // assembled is complete and can be applied. Doing it at START would apply last tick's set.
        if (event.phase == TickEvent.Phase.END) {
            RadioServer.serverTick();
        }
    }

    private static void playerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        // PlayerEvent#getEntity is typed Player already, so no cast is needed — and a pattern
        // variable would not compile against it (Java rejects a pattern whose declared type is a
        // supertype of the expression).
        if (event.getEntity() != null) {
            RadioServer.playerLeft(event.getEntity().getUUID());
        }
    }

    // ------------------------------------------------------------------ client lifecycle

    private static void clientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            ClientMedia.onClientTick();
        }
    }

    // ------------------------------------------------------------------ creative tab

    /**
     * One block does not justify a creative tab of its own; it goes in a vanilla one. Revisit once
     * there is more than a single item to group.
     *
     * <p>{@code BuildCreativeModeTabContentsEvent} implements {@code IModBusEvent}, so this listener
     * belongs on the mod bus, not the Forge bus.
     */
    private static void addCreativeTabContents(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            event.accept(Registration.RADIO_ITEM);
        }
    }
}
