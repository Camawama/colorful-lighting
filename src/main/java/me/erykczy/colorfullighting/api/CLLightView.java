package me.erykczy.colorfullighting.api;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * Read access to one level's colored light, for mods that render things themselves (weather and
 * particle renderers, LOD renderers, entity overlays, ...). Obtain via
 * {@link ColorfulLightingApi#getLightView(Level)} and cache the instance per level; it stays
 * valid for the lifetime of that level.
 *
 * <p>All methods are safe to call from render and worker threads. Block-state reads inside
 * {@link #samplePackedLight} follow the same rules as vanilla
 * {@code LevelRenderer.getLightColor}.
 */
public interface CLLightView {
    /** The level this view reads from. */
    Level level();

    /**
     * Whether colored lighting is currently producing values for this level (the global toggle
     * is on and this level has a light engine). When false, {@link #samplePackedLight} still
     * works and returns vanilla-format values, and {@link #sampleBlockLightColor} returns -1.
     */
    boolean isActive();

    /**
     * The colored block-light at {@code pos} as {@code 0xRRGGBB} (black where no colored light
     * reaches), or {@code -1} when colored lighting is inactive here or the position is outside
     * the tracked area. Sky light is not included; query it from the level as usual.
     */
    int sampleBlockLightColor(BlockPos pos);

    /**
     * The full packed light value at {@code pos}, exactly as Colorful Lighting feeds it to
     * terrain and entity rendering: a colored value (see {@link CLPackedLight}) when colored
     * lighting is active, a plain vanilla one otherwise. Ready to pass to {@code uv2} /
     * {@code renderToBuffer}; the bundled core shaders decode either format.
     */
    int samplePackedLight(BlockPos pos);

    /**
     * Tells the light engine that the light-relevant properties of the block at {@code pos}
     * changed without a regular block update, scheduling a relight. Call this when a
     * {@link CLBlockLightColorProvider}'s answer for this position changes, or when your mod
     * mutates light emission in ways the level does not report.
     */
    void notifyBlockChanged(BlockPos pos);
}
