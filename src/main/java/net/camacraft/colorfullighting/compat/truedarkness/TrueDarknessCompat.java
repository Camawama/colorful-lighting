package net.camacraft.colorfullighting.compat.truedarkness;

import net.camacraft.colorfullighting.ColorfulLighting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Makes True Darkness's lightmap darkening follow the dimension actually being rendered.
 *
 * <p>True Darkness computes one static darkness table per frame, at the head of
 * GameRenderer#renderLevel, from {@code Minecraft.level}, and then darkens EVERY lightmap upload
 * with that table. Immersive Portals renders other dimensions inside that same frame: it swaps
 * {@code Minecraft.level} and the game renderer's LightTexture to a per-dimension one and updates
 * it right there. That upload gets darkened with the table of the player's dimension, so with
 * {@code dark_nether=true} the Overworld seen from the Nether loses its sky light entirely, even
 * at noon.
 *
 * <p>The fix is to recompute the table immediately before any lightmap upload, for whichever
 * level is current at that moment (see the LightTexture mixin). Recomputing the same table twice
 * for the main lightmap is harmless: it is 256 iterations of float math, and nothing else reads
 * the table between uploads (verified against True Darkness 2.0.103: only its DynamicTexture
 * upload hook reads the table and the enabled flag).
 *
 * <p>True Darkness is an optional runtime dependency, so its method is reached by MethodHandle;
 * the mixin that calls this is only applied when the mod is present.
 */
public final class TrueDarknessCompat {
    private static final String DARKNESS_CLASS = "grondag.darkness.Darkness";
    private static final MethodHandle UPDATE_LUMINANCE = resolveUpdateLuminance();
    private static boolean failureLogged;

    private TrueDarknessCompat() {}

    private static MethodHandle resolveUpdateLuminance() {
        try {
            Class<?> darkness = Class.forName(DARKNESS_CLASS);
            return MethodHandles.publicLookup().findStatic(darkness, "updateLuminance",
                    MethodType.methodType(void.class, float.class, Minecraft.class, GameRenderer.class, float.class));
        } catch (Throwable t) {
            ColorfulLighting.LOGGER.warn("True Darkness is present but its Darkness.updateLuminance could not be resolved; "
                    + "its darkening will not follow dimensions rendered through portals", t);
            return null;
        }
    }

    public static boolean isAvailable() {
        return UPDATE_LUMINANCE != null;
    }

    /**
     * Recomputes True Darkness's darkness table and enabled flag for {@code minecraft.level} as it
     * is right now. Mirrors the call True Darkness itself makes at the head of renderLevel.
     *
     * @param prevFlicker the LightTexture's current blockLightRedFlicker, what True Darkness reads
     *                    through its own accessor before the update
     */
    public static void updateLuminance(float partialTick, Minecraft minecraft, GameRenderer renderer, float prevFlicker) {
        if (UPDATE_LUMINANCE == null) return;
        try {
            UPDATE_LUMINANCE.invokeExact(partialTick, minecraft, renderer, prevFlicker);
        } catch (Throwable t) {
            if (!failureLogged) {
                failureLogged = true;
                ColorfulLighting.LOGGER.warn("True Darkness table recompute failed; reporting once", t);
            }
        }
    }
}
