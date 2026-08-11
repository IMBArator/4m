import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Source of truth for {@code assets/mmmm/textures/item/depolarization_hammer_handle.png}.
 *
 * <p>The hammer is placeholder art: its model draws the vanilla iron pickaxe as {@code layer0} and
 * this overlay as {@code layer1}, so the tool keeps its familiar silhouette while the haft reads as
 * 4M's. Only our own pixels ship — the pickaxe itself is referenced by texture path and resolves
 * from the player's own game files, so no Mojang asset is redistributed.
 *
 * <p>{@link #RUNS} is the position of the wooden haft in {@code minecraft:item/iron_pickaxe},
 * recorded here so the overlay can be regenerated without going back to the vanilla texture to
 * measure it again. The shading is our own three-tone scheme, not sampled from vanilla.
 *
 * <p>Regenerate after changing a colour:
 * <pre>
 * java art/MakeHammerHandle.java \
 *   common/src/main/resources/assets/mmmm/textures/item/depolarization_hammer_handle.png
 * </pre>
 *
 * <p>Note this couples the hammer to whatever pickaxe texture is loaded: a resource pack that
 * redraws {@code iron_pickaxe.png} moves the haft, and the overlay stops lining up. That is
 * acceptable for a placeholder, and is the reason to eventually draw the hammer outright.
 */
public class MakeHammerHandle {

    /** Wooden-haft pixels of the vanilla iron pickaxe, as {@code {y, xFirst, xLast}} inclusive. */
    static final int[][] RUNS = {
            { 3, 12, 13 }, { 4, 12, 13 },
            { 5, 10, 10 }, { 6, 9, 11 }, { 7, 8, 10 }, { 8, 7, 9 }, { 9, 6, 8 },
            { 10, 5, 7 }, { 11, 4, 6 }, { 12, 3, 5 }, { 13, 2, 4 }, { 14, 2, 3 },
    };

    // Lit on the upper-left of each run, shadowed on the lower-right, which is the direction
    // vanilla's item art lights from.
    static final int HIGHLIGHT = 0xFFFF9ECF;
    static final int BASE = 0xFFEE5FA7;
    static final int SHADOW = 0xFFB23A75;

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("usage: MakeHammerHandle <output.png>");
            System.exit(2);
        }
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int[] run : RUNS) {
            int y = run[0];
            int first = run[1];
            int last = run[2];
            for (int x = first; x <= last; x++) {
                // A one-pixel run gets the base tone: it has no lit or shadowed side to show.
                int colour = first == last ? BASE : x == first ? HIGHLIGHT : x == last ? SHADOW : BASE;
                img.setRGB(x, y, colour);
            }
        }
        ImageIO.write(img, "PNG", new File(args[0]));
        System.out.println("wrote " + args[0]);
    }
}
