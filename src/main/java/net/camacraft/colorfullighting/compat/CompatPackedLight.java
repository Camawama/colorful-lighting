package net.camacraft.colorfullighting.compat;

import net.camacraft.colorfullighting.api.CLPackedLight;

/**
 * Internal alias of {@link CLPackedLight} kept for the compat mixins; the format contract, the
 * three breakage modes and the canonical implementations live there (it is public API, so other
 * mods can apply the same fixes themselves).
 *
 * <p>Every compat mixin reduces to one of these helpers:
 * <ul>
 *   <li><b>Signed comparisons</b> ({@code Math.max(light, x)}): {@link #maxWithLightLevel} /
 *       {@link #preferColored} (Flerovium, Flopper, Subtle Effects).</li>
 *   <li><b>Lossy round-trips</b> ({@code LightTexture.block/sky} then {@code pack}):
 *       {@link #preferColored} (Epic Fight).</li>
 *   <li><b>Half-int arithmetic</b> (averaging the halves as scalars): {@link #toVanilla} at all
 *       pipeline entry points (HBM Modernized).</li>
 * </ul>
 */
public final class CompatPackedLight {
    private CompatPackedLight() {}

    /** See {@link CLPackedLight#isColored}. */
    public static boolean isColored(int packed) {
        return CLPackedLight.isColored(packed);
    }

    /** See {@link CLPackedLight#toVanilla}. */
    public static int toVanilla(int packed) {
        return CLPackedLight.toVanilla(packed);
    }

    /** See {@link CLPackedLight#preferColored}. */
    public static int preferColored(int possiblyColored, int fallback) {
        return CLPackedLight.preferColored(possiblyColored, fallback);
    }

    /** See {@link CLPackedLight#maxWithLightLevel}. */
    public static int maxWithLightLevel(int packedLight, int lightLevel) {
        return CLPackedLight.maxWithLightLevel(packedLight, lightLevel);
    }
}
