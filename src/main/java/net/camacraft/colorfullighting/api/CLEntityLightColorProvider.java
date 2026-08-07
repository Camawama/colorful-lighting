package net.camacraft.colorfullighting.api;

import net.minecraft.world.entity.Entity;

/**
 * Supplies dynamic light <b>colors</b> for entities acting as dynamic light sources (used when a
 * dynamic-lighting mod such as Dynamic Lights Reforged or Lively Lighting is installed, and for
 * Colorful Lighting's own entity light tracking). Register via
 * {@link ColorfulLightingApi#registerEntityLightColorProvider}.
 *
 * <p>Static per-entity-type colors do not need this interface: ship an {@code entities.json}
 * emitter entry in your assets instead. Providers run after explicit user/pack configuration and
 * before the built-in heuristics (held/equipped item color, burning entities glow fire-colored).
 *
 * <h2>Threading and performance</h2>
 * Called on the client tick and render paths for every tracked entity, potentially every tick.
 * Implementations must be thread-safe, fast and allocation-light.
 */
@FunctionalInterface
public interface CLEntityLightColorProvider {
    /**
     * @return the light color as {@code 0xRRGGBB}, or {@code -1} to defer to the next provider
     *         (and ultimately to the built-in heuristics)
     */
    int getLightColor(Entity entity);
}
