package net.camacraft.colorfullighting.common;

import com.mojang.blaze3d.platform.NativeImage;
import net.camacraft.colorfullighting.common.util.ColorRGB4;
import net.camacraft.colorfullighting.mixin.render.SpriteContentsAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.model.data.ModelData;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Derives a light emission color from a block's own appearance, for blocks whose color cannot be
 * written down in emitters.json: an emitter configured as {@code "auto"}, or (when the
 * {@code autoEmitterColors} client config is on) any light-emitting block with no configured
 * color at all — modded lamps, ores, crystals and the like then glow in a plausible color
 * automatically instead of plain white.
 *
 * <p>The base color is a luminance-weighted average of the model's particle sprite: bright pixels
 * dominate, so a torch-like texture yields its flame color rather than its handle. The result is
 * normalized to full brightness (emission strength comes from the light level, the sample only
 * supplies the hue) and cached per block state. On top of that, the position-aware lookup applies
 * the block color provider's tint at the actual position, which is what makes coordinate-dependent
 * blocks such as Better End's aurora crystals emit the color they visibly have at that spot.
 *
 * <p>Runs on the light propagator thread. Model and tint lookups there are as safe as Sodium's
 * worker-thread meshing (which calls the same code), and every step is defensively caught: any
 * failure falls back to white without caching, so a sample attempted mid-resource-reload heals
 * itself on the next light update.
 */
public final class AutoEmitterColors {
    private AutoEmitterColors() {}

    /** Base (untinted) color per block state; identity keys, cleared on resource reload. */
    private static final ConcurrentHashMap<BlockState, ColorRGB4> BASE_COLORS = new ConcurrentHashMap<>();

    /** Sampling stride cap so large (e.g. animated 512-tall) sprites stay cheap to average. */
    private static final int MAX_SAMPLES_PER_AXIS = 64;

    public static void clearCache() {
        BASE_COLORS.clear();
    }

    /**
     * Emission color for {@code state}, tinted by the block color provider at {@code pos} when a
     * level and position are available (pass null for the position-less render paths).
     */
    public static ColorRGB4 get(BlockState state, @Nullable BlockAndTintGetter level, @Nullable BlockPos pos) {
        ColorRGB4 base = BASE_COLORS.get(state);
        if (base == null) {
            base = sampleTexture(state);
            if (base == null) return Config.defaultColor; // failed: retry on a later light update
            BASE_COLORS.put(state, base);
        }
        if (level != null && pos != null) {
            try {
                int tint = Minecraft.getInstance().getBlockColors().getColor(state, level, pos, 0);
                if (tint != -1) return applyTint(base, tint);
            } catch (Throwable ignored) {
                // a non-thread-safe modded color provider: emit untinted rather than crash the propagator
            }
        }
        return base;
    }

    @Nullable
    private static ColorRGB4 sampleTexture(BlockState state) {
        try {
            BakedModel model = Minecraft.getInstance().getBlockRenderer().getBlockModel(state);
            TextureAtlasSprite sprite = model.getParticleIcon(ModelData.EMPTY);
            NativeImage image = ((SpriteContentsAccessor) sprite.contents()).colorfullighting$getOriginalImage();
            if (image == null) return null;

            int width = image.getWidth();
            int height = image.getHeight();
            if (width <= 0 || height <= 0) return null;
            int strideX = Math.max(1, width / MAX_SAMPLES_PER_AXIS);
            int strideY = Math.max(1, height / MAX_SAMPLES_PER_AXIS);

            double sumR = 0, sumG = 0, sumB = 0, sumWeight = 0;
            for (int y = 0; y < height; y += strideY) {
                for (int x = 0; x < width; x += strideX) {
                    int abgr = image.getPixelRGBA(x, y);
                    int alpha = (abgr >>> 24) & 0xFF;
                    if (alpha < 16) continue;
                    int r = abgr & 0xFF;
                    int g = (abgr >>> 8) & 0xFF;
                    int b = (abgr >>> 16) & 0xFF;
                    // luminance-squared weighting: the glowing part of the texture defines the hue
                    double lum = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0;
                    double weight = (alpha / 255.0) * (lum * lum + 0.001);
                    sumR += r * weight;
                    sumG += g * weight;
                    sumB += b * weight;
                    sumWeight += weight;
                }
            }
            if (sumWeight <= 0) return Config.defaultColor;

            double r = sumR / sumWeight, g = sumG / sumWeight, b = sumB / sumWeight;
            double max = Math.max(r, Math.max(g, b));
            if (max <= 0) return Config.defaultColor;
            // hue only: scale the brightest channel to full, the light level supplies intensity
            return ColorRGB4.fromRGB8(
                    (int) Math.round(r * 255.0 / max),
                    (int) Math.round(g * 255.0 / max),
                    (int) Math.round(b * 255.0 / max));
        } catch (Throwable t) {
            return null;
        }
    }

    private static ColorRGB4 applyTint(ColorRGB4 base, int tintRGB8) {
        int tr = (tintRGB8 >> 16) & 0xFF;
        int tg = (tintRGB8 >> 8) & 0xFF;
        int tb = tintRGB8 & 0xFF;
        int max = Math.max(tr, Math.max(tg, tb));
        if (max <= 0) return base;
        // normalize the tint too, then combine multiplicatively in 4-bit space
        int r = Math.round(base.red4 * (tr * 255f / max) / 255f);
        int g = Math.round(base.green4 * (tg * 255f / max) / 255f);
        int b = Math.round(base.blue4 * (tb * 255f / max) / 255f);
        return ColorRGB4.fromRGB4(r, g, b);
    }
}
