package mmmm;

import net.minecraft.resources.ResourceLocation;

/**
 * Constants shared by every module, loader-agnostic.
 *
 * <p>Lives in {@code common/}, which is compiled once per loader (ADR-0002), so nothing here may
 * reference a loader API.
 */
public final class Mmmm {

    /**
     * The mod id, and the namespace of every resource this mod ships.
     *
     * <p><b>Not {@code 4m}, and it cannot be.</b> The product is called 4M, but Forge validates mod
     * ids against {@code ^[a-z][a-z0-9_]{1,63}$} and throws {@code InvalidModFileException} on a
     * leading digit — the mod would not load at all. A Java package cannot start with a digit
     * either. Minecraft resource namespaces happily accept {@code 4m}, which makes this an easy
     * trap to walk into: the assets look fine right up until FML refuses the jar.
     *
     * <p>{@code mmmm} is a fair stand-in: it is the four Ms of <i>Minecraft Multi Media Mod</i>. The
     * 4M branding lives in {@code modName}, which has no such constraint.
     */
    public static final String MOD_ID = "mmmm";

    /** Convenience for the many {@code mmmm:…} identifiers this mod builds. */
    public static ResourceLocation id(String path) {
        return new ResourceLocation(MOD_ID, path);
    }

    private Mmmm() {
    }
}
