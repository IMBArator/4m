package mmmm.forge.client;

import mmmm.block.RadioBlock;
import mmmm.client.ClientMedia;
import mmmm.client.ClientMessages;
import mmmm.client.ClientNetwork;
import mmmm.client.RadioScreen;
import mmmm.forge.MmmmNetwork;
import net.minecraft.client.Minecraft;

/**
 * Every wiring-up that touches {@code net.minecraft.client}, kept out of the entry class.
 *
 * <h2>Why this class exists at all</h2>
 * The obvious place for this code is {@code MmmmForge.clientSetup}, and that is where it was. It
 * crashed every dedicated server at mod construction:
 *
 * <pre>
 * java.lang.RuntimeException: Attempted to load class net/minecraft/client/gui/screens/Screen
 *                             for invalid dist DEDICATED_SERVER
 *     at RuntimeDistCleaner.processClassWithFlags
 *     at FMLModContainer.constructMod
 * </pre>
 *
 * <p>Not because the client setup <em>ran</em> — {@code FMLClientSetupEvent} never fires on a
 * server. Because of <b>bytecode verification</b>. Writing the screen opener as a lambda put its
 * body in a synthetic method <em>on {@code MmmmForge} itself</em>, and that body passes a
 * {@code RadioScreen} to {@code Minecraft.setScreen(Screen)}. Verifying an argument against a
 * declared parameter type is one of the few things that makes the verifier load another class, so
 * merely linking the entry class demanded {@code Screen}, and Forge's dist cleaner refused.
 *
 * <p>The sharp part: {@code RadioBlock.setClientTicker(ClientMedia::tickBlock)} sat two lines above
 * and was always fine. A <em>method reference</em> compiles to an {@code invokedynamic} whose target
 * lives in the other class, so no client bytecode lands here and nothing is verified against a
 * client type. A <em>lambda</em> compiles its body into the enclosing class. Same-looking code, and
 * only one of the two forms is safe — which is exactly why the rule is now "no client type is named
 * outside a {@code client} package" rather than "be careful with lambdas".
 * {@code :forge:checkClientClassesAreClientOnly} enforces the import half of that.
 *
 * <p>None of this is reachable from {@code runClient}, which is a client. It takes a dedicated
 * server to see it — see PLAN.md §11.
 */
public final class ForgeClientSetup {

    private ForgeClientSetup() {
    }

    /**
     * Called from {@code MmmmForge.clientSetup}.
     *
     * <p>The call there is a bare {@code invokestatic} with no arguments, which the verifier does not
     * need to resolve — so the dedicated server never loads this class, and through it never loads
     * anything under {@code net.minecraft.client}.
     */
    public static void install() {
        // How shared code reaches the client audio path without naming a client class.
        RadioBlock.setClientTicker(ClientMedia::tickBlock);
        RadioBlock.setScreenOpener(ForgeClientSetup::openRadioScreen);
        // How shared code sends a clock ping without naming a loader's networking package.
        ClientMedia.setPingSender(ForgeClientSetup::sendClockPing);
        // The screen's outbound direction, for the same reason.
        ClientNetwork.setConfigSender(MmmmNetwork::sendToServer);
    }

    private static void openRadioScreen(mmmm.block.RadioBlockEntity radio) {
        Minecraft.getInstance().setScreen(new RadioScreen(radio));
    }

    private static void sendClockPing(long clientNanos) {
        MmmmNetwork.sendToServer(new ClientMessages.ClockPing(clientNanos));
    }
}
