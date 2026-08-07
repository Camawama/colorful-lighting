package net.camacraft.colorfullighting.api;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Supplies dynamic light <b>colors</b> for blocks whose hue cannot be expressed statically in
 * {@code emitters.json} (position-dependent, block-entity-dependent, animated, ...). Register
 * via {@link ColorfulLightingApi#registerBlockLightColorProvider}.
 *
 * <p>Static colors do not need this interface: ship an {@code emitters.json} entry in your
 * assets instead (no compile-time dependency required), or rely on the automatic
 * texture-sampled colors. Providers are consulted for every light-emitting block that has no
 * explicit {@code emitters.json} entry, before automatic texture sampling. Explicit user/pack
 * configuration always wins over providers.
 *
 * <p>Brightness is not part of this interface; it continues to come from the state's vanilla
 * light emission. Colorful Lighting multiplies the returned hue by that emission.
 *
 * <p>When a provider's answer for a position changes without a block update (e.g. an animated
 * hue), call {@link CLLightView#notifyBlockChanged} to schedule a relight.
 *
 * <h2>Threading and performance</h2>
 * Called from Colorful Lighting's light-propagation worker thread and from chunk-meshing
 * threads, potentially many times per relight. Implementations must be thread-safe, fast and
 * allocation-light, and must not touch the level beyond simple reads.
 */
@FunctionalInterface
public interface CLBlockLightColorProvider {
    /**
     * @return the light color as {@code 0xRRGGBB}, or {@code -1} to defer to the next provider
     *         (and ultimately to automatic texture-sampled colors)
     */
    int getLightColor(Level level, BlockPos pos, BlockState state);
}
