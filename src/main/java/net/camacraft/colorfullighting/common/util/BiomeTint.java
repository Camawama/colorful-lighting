package net.camacraft.colorfullighting.common.util;

import net.camacraft.colorfullighting.common.accessors.LevelAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * Biome-derived filter colors, looked up on the light propagator thread. Biomes are constant per
 * 4x4x4 cell, and propagation walks contiguous blocks, so a one-slot per-thread memo keyed by the
 * quart position absorbs nearly all lookups while a light front crosses a body of water.
 */
public final class BiomeTint {
    private BiomeTint() {}

    private static final class Memo {
        long quartKey = Long.MIN_VALUE;
        ColorRGB4 color;
    }
    private static final ThreadLocal<Memo> MEMO = ThreadLocal.withInitial(Memo::new);

    /**
     * The biome water color at {@code pos}, normalized to full brightness (the hue is the filter;
     * absorption controls darkening). Falls back when the level or chunk is unavailable.
     */
    public static ColorRGB4 waterColor(LevelAccessor level, BlockPos pos, ColorRGB4 fallback) {
        try {
            Level lvl = level.getLevel();
            if (lvl == null) return fallback;

            long quartKey = BlockPos.asLong(pos.getX() >> 2, pos.getY() >> 2, pos.getZ() >> 2);
            Memo memo = MEMO.get();
            if (memo.quartKey == quartKey && memo.color != null) return memo.color;

            ColorRGB4 color = normalized(lvl.getBiome(pos).value().getWaterColor());
            memo.quartKey = quartKey;
            memo.color = color;
            return color;
        } catch (Throwable t) {
            return fallback;
        }
    }

    private static ColorRGB4 normalized(int rgb8) {
        int r = (rgb8 >> 16) & 0xFF;
        int g = (rgb8 >> 8) & 0xFF;
        int b = rgb8 & 0xFF;
        int max = Math.max(r, Math.max(g, b));
        if (max <= 0) return ColorRGB4.WHITE;
        return ColorRGB4.fromRGB8(r * 255 / max, g * 255 / max, b * 255 / max);
    }
}
