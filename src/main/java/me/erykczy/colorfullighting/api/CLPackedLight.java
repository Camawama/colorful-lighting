package me.erykczy.colorfullighting.api;

/**
 * The colored packed-light format: Colorful Lighting's stable wire contract, and the class to
 * reach for when your mod touches packed light values ("combined light", {@code uv2}) while
 * Colorful Lighting is installed.
 *
 * <h2>The format</h2>
 * Wherever vanilla passes packed lightmap coordinates ({@code LightTexture.pack}), Colorful
 * Lighting may instead pass:
 * <pre>
 * bits  0..7   red    (0..255)
 * bits  8..15  green  (0..255)
 * bits 16..19  sky    (0..15, vanilla sky light)
 * bits 20..27  blue   (0..255)
 * bits 28..31  marker (always 0xF)
 * </pre>
 * The 0xF top nibble makes every colored value a <b>negative</b> int and is how consumers (and
 * the bundled core shaders) distinguish colored values from vanilla ones. Vanilla-format values
 * still occur (colored lighting disabled, unsupported level, full-bright constants like
 * {@code 0xF000F0}), so code must handle both; every helper here does.
 *
 * <h2>The three rules</h2>
 * Mods break on this format in exactly three ways. If you avoid these, you need no integration
 * at all; the packed int passes through vanilla rendering untouched and the shaders decode it:
 * <ol>
 *   <li><b>No signed comparisons.</b> {@code Math.max(light, x)} always discards colored values
 *       (they are negative). Use {@link #maxWithLightLevel} or {@link #max}.</li>
 *   <li><b>No decompose/rebuild round trips.</b> {@code LightTexture.pack(LightTexture.block(l),
 *       LightTexture.sky(l))} produces garbage from a colored value. Keep the original value, or
 *       use {@link #preferColored} to restore it after a lossy path.</li>
 *   <li><b>No half-int arithmetic.</b> Averaging or interpolating {@code light & 0xFFFF} and
 *       {@code light >>> 16} as scalars bleeds color bits across boundaries. If your pipeline
 *       must do scalar math, convert with {@link #toVanilla} first (correct brightness, no hue).</li>
 * </ol>
 *
 * <p>This class is pure int math with no Minecraft or Colorful Lighting dependencies, so it is
 * safe to copy the constants/logic if you prefer not to depend on the mod at compile time.
 */
public final class CLPackedLight {
    private CLPackedLight() {}

    /** Whether {@code packed} is in the colored format (top nibble 0xF, negative int). */
    public static boolean isColored(int packed) {
        return (packed >>> 28) == 0xF;
    }

    /** Packs a colored value. Inputs are clamped ({@code sky4} 0..15, channels 0..255). */
    public static int pack(int sky4, int red8, int green8, int blue8) {
        sky4 = clamp(sky4, 15);
        red8 = clamp(red8, 255);
        green8 = clamp(green8, 255);
        blue8 = clamp(blue8, 255);
        return red8 | green8 << 8 | sky4 << 16 | blue8 << 20 | 0xF << 28;
    }

    /** Red channel 0..255 of a colored value. Meaningless for vanilla-format values. */
    public static int red8(int packed) { return packed & 0xFF; }

    /** Green channel 0..255 of a colored value. Meaningless for vanilla-format values. */
    public static int green8(int packed) { return (packed >>> 8) & 0xFF; }

    /** Blue channel 0..255 of a colored value. Meaningless for vanilla-format values. */
    public static int blue8(int packed) { return (packed >>> 20) & 0xFF; }

    /** Sky light 0..15 of a colored value. Meaningless for vanilla-format values. */
    public static int sky4(int packed) { return (packed >>> 16) & 0xF; }

    /** The block-light color as {@code 0xRRGGBB}, or {@code -1} for vanilla-format values. */
    public static int colorRGB8(int packed) {
        if (!isColored(packed)) return -1;
        return red8(packed) << 16 | green8(packed) << 8 | blue8(packed);
    }

    /**
     * Converts a colored value to well-formed vanilla lightmap coordinates: block light becomes
     * the brightest color channel, sky light carries over. Vanilla-format values pass through
     * unchanged, so applying this twice is safe. Use at the entry of any pipeline that does
     * scalar math on the halves (rule 3).
     */
    public static int toVanilla(int packed) {
        if (!isColored(packed)) return packed;
        int block4 = (Math.max(red8(packed), Math.max(green8(packed), blue8(packed))) + 8) / 17;
        return vanillaPack(block4, sky4(packed));
    }

    /**
     * The colored value when it is one, otherwise the fallback. Use to undo a lossy
     * decompose/rebuild round trip (rule 2): pass the original as {@code possiblyColored} and
     * your recomputed value as {@code fallback}.
     */
    public static int preferColored(int possiblyColored, int fallback) {
        return isColored(possiblyColored) ? possiblyColored : fallback;
    }

    /**
     * Format-aware replacement for {@code Math.max(packedLight, rawLightLevel)} where the second
     * operand is a plain 0..15 light level (a fluid's or particle's own luminosity), not a packed
     * value (rule 1).
     */
    public static int maxWithLightLevel(int packedLight, int lightLevel) {
        if (!isColored(packedLight)) return Math.max(packedLight, lightLevel);
        int lum8 = clamp(lightLevel, 15) * 17;
        if (lum8 == 0) return packedLight;
        return max(packedLight, pack(0, lum8, lum8, lum8));
    }

    /**
     * Format-aware replacement for {@code Math.max(a, b)} over two packed light values (rule 1).
     * Handles any mix of colored and vanilla operands: two vanilla values compare per nibble,
     * a colored value wins over a vanilla one, and two colored values max per channel.
     */
    public static int max(int a, int b) {
        boolean aColored = isColored(a);
        boolean bColored = isColored(b);
        if (!aColored && !bColored) {
            return vanillaPack(
                    Math.max(vanillaBlock(a), vanillaBlock(b)),
                    Math.max(vanillaSky(a), vanillaSky(b)));
        }
        if (aColored != bColored) {
            return aColored ? a : b;
        }
        return pack(
                Math.max(sky4(a), sky4(b)),
                Math.max(red8(a), red8(b)),
                Math.max(green8(a), green8(b)),
                Math.max(blue8(a), blue8(b)));
    }

    // Vanilla LightTexture equivalents, inlined so this class stays dependency-free.
    private static int vanillaBlock(int packed) { return (packed & 0xFFFF) >> 4; }
    private static int vanillaSky(int packed) { return (packed >> 20) & 0xFFFF; }
    private static int vanillaPack(int block4, int sky4) { return block4 << 4 | sky4 << 20; }

    private static int clamp(int value, int maxInclusive) {
        return Math.min(maxInclusive, Math.max(0, value));
    }
}
