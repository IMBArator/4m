package mmmm;

import net.minecraft.resources.ResourceLocation;

/**
 * Constants shared by every module, loader-agnostic.
 *
 * <p>Lives in {@code common/}, which is compiled once per loader (ADR-0002), so nothing here may
 * reference a loader API.
 */
public final class Mmmm {

    public static final String MOD_ID = "4m";

    /** Convenience for the many {@code mmmm:…} identifiers this mod builds. */
    public static ResourceLocation id(String path) {
        return new ResourceLocation(MOD_ID, path);
    }

    private Mmmm() {
    }
}
