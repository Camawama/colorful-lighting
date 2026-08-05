package me.erykczy.colorfullighting.compat;

import me.erykczy.colorfullighting.common.util.PackedLightData;
import net.minecraft.client.renderer.LightTexture;

/**
 * Shared conversions between the colored packed-light format (R8 G8 B8 + sky4 + alpha4, see
 * {@link PackedLightData}) and vanilla's packed lightmap coordinates, for compat with mods that
 * take the packed int apart.
 *
 * <p>Mods that pass the packed int through untouched need no compat: the core shaders detect the
 * 0xF top nibble and decode it, and fall back to vanilla lightmap sampling for anything else.
 * Mods break in exactly three ways, and every compat mixin reduces to one of these helpers:
 * <ul>
 *   <li><b>Signed comparisons</b> ({@code Math.max(light, x)}): the 0xF top nibble makes colored
 *       values negative, so the comparison always picks the other operand (Flerovium, Flopper).</li>
 *   <li><b>Lossy round-trips</b> ({@code LightTexture.block/sky} then {@code pack}): the vanilla
 *       nibble positions hold color channel bits, so the rebuilt value is garbage (Epic Fight).</li>
 *   <li><b>Half-int arithmetic</b> (averaging / interpolating {@code light & 0xFFFF} and
 *       {@code light >>> 16} as scalars): color channels bleed across bit boundaries, producing
 *       striping or banding (HBM Modernized). Such pipelines cannot carry three color channels,
 *       so the fix is {@link #toVanilla}: correct brightness, no hue.</li>
 * </ul>
 */
public final class CompatPackedLight {
    private CompatPackedLight() {}

    /** Whether {@code packed} is in the colored format (top nibble 0xF). */
    public static boolean isColored(int packed) {
        return (packed >>> 28) == 0xF;
    }

    /**
     * Converts a colored packed value to well-formed vanilla lightmap coordinates: block light
     * becomes the brightest color channel (a pure red 255 light is a block light of 15), sky
     * light carries over. Vanilla-format values pass through unchanged, so the conversion is
     * safe to apply twice.
     */
    public static int toVanilla(int packed) {
        if (!isColored(packed)) return packed;
        int red8 = packed & 0xFF;
        int green8 = (packed >>> 8) & 0xFF;
        int blue8 = (packed >>> 20) & 0xFF;
        int sky4 = (packed >>> 16) & 0xF;
        int block4 = (Math.max(red8, Math.max(green8, blue8)) + 8) / 17;
        return LightTexture.pack(block4, sky4);
    }

    /** The colored value when it is one, otherwise the fallback (used to undo lossy round-trips). */
    public static int preferColored(int possiblyColored, int fallback) {
        return isColored(possiblyColored) ? possiblyColored : fallback;
    }

    /**
     * Format-aware replacement for {@code Math.max(packedLight, rawLightLevel)} where the second
     * operand is a plain 0..15 light level (e.g. a fluid's own luminosity), not a packed value.
     */
    public static int maxWithLightLevel(int packedLight, int lightLevel) {
        if (!isColored(packedLight)) return Math.max(packedLight, lightLevel);
        int lum8 = Math.min(15, Math.max(0, lightLevel)) * 17;
        if (lum8 == 0) return packedLight;
        return PackedLightData.max(packedLight, PackedLightData.packData(0, lum8, lum8, lum8));
    }
}
