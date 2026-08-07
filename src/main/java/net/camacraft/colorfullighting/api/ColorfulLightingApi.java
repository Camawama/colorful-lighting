package net.camacraft.colorfullighting.api;

import net.camacraft.colorfullighting.common.ColoredLightEngine;
import net.camacraft.colorfullighting.common.api.ApiProviderRegistry;
import net.camacraft.colorfullighting.common.api.LightViewImpl;
import net.camacraft.colorfullighting.common.accessors.mixin.LevelAttachments;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;

/**
 * Entry point of the Colorful Lighting API.
 *
 * <p>Depend on Colorful Lighting as a soft (optional) dependency and guard every call site with
 * {@code ModList.get().isLoaded("colorful_lighting")} behind a class boundary, the same way you
 * would integrate with any optional mod. Nothing here is required for basic coexistence: mods
 * that simply pass packed light values through untouched work without any integration (see
 * {@link CLPackedLight} for the three things to avoid).
 *
 * <p>What the API offers, from cheapest to most involved:
 * <ul>
 *   <li><b>Nothing:</b> ship {@code emitters.json} / {@code entities.json} entries in your
 *       assets to give your blocks/entities static light colors. No code, no dependency.</li>
 *   <li>{@link CLPackedLight}: pure-int helpers for code that touches packed light values.</li>
 *   <li>{@link #getLightView(Level)}: sample colored light for your own rendering.</li>
 *   <li>{@link #registerBlockLightColorProvider} / {@link #registerEntityLightColorProvider}:
 *       dynamic light colors that JSON cannot express.</li>
 *   <li>{@link CLSupportingLevel} / {@link CLClientLevel}: opt custom {@code Level}
 *       implementations into colored lighting.</li>
 * </ul>
 */
public final class ColorfulLightingApi {
    /**
     * Bumped when the API surface changes incompatibly. Additive changes (new methods, new
     * interfaces) do not bump it.
     */
    public static final int API_VERSION = 1;

    private ColorfulLightingApi() {}

    /**
     * Whether colored lighting is globally active (the mod is on; users can toggle it with
     * {@code /cl}). When false, every packed light value in the game is vanilla-format.
     */
    public static boolean isActive() {
        return ColoredLightEngine.isEnabled();
    }

    /**
     * A colored-light view of {@code level}, or null when that level cannot carry colored
     * lighting (server levels, and custom levels that do not implement
     * {@link CLSupportingLevel}). Cache the returned instance per level; it stays valid for the
     * level's lifetime and every method on it is cheap.
     */
    @Nullable
    public static CLLightView getLightView(Level level) {
        if (!(level instanceof LevelAttachments attachments)) return null;
        if (attachments.colorfullighting$getEngine() == null && !(level instanceof CLSupportingLevel)) return null;
        return new LightViewImpl(level, attachments);
    }

    /**
     * Registers a dynamic block light-color provider. Providers are consulted in registration
     * order for every light-emitting block without an explicit {@code emitters.json} entry;
     * the first non-negative answer wins. Registration is thread-safe and can happen at any
     * time (mod construction or setup is typical). It cannot be undone.
     */
    public static void registerBlockLightColorProvider(CLBlockLightColorProvider provider) {
        ApiProviderRegistry.registerBlock(provider);
    }

    /**
     * Registers a dynamic entity light-color provider. Providers run after explicit
     * {@code entities.json} configuration and before the built-in heuristics; the first
     * non-negative answer wins. Registration is thread-safe and cannot be undone.
     */
    public static void registerEntityLightColorProvider(CLEntityLightColorProvider provider) {
        ApiProviderRegistry.registerEntity(provider);
    }
}
