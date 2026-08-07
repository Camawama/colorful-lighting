package me.erykczy.colorfullighting.common.api;

import me.erykczy.colorfullighting.api.CLBlockLightColorProvider;
import me.erykczy.colorfullighting.api.CLEntityLightColorProvider;
import me.erykczy.colorfullighting.common.util.ColorRGB4;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Backing store for the API's dynamic color providers. Genuinely global (registrations are
 * config-like, not per-level state), and read on the propagator/meshing hot paths, hence the
 * copy-on-write lists and the cheap empty checks before any iteration.
 */
public final class ApiProviderRegistry {
    private static final List<CLBlockLightColorProvider> BLOCK_PROVIDERS = new CopyOnWriteArrayList<>();
    private static final List<CLEntityLightColorProvider> ENTITY_PROVIDERS = new CopyOnWriteArrayList<>();

    private ApiProviderRegistry() {}

    public static void registerBlock(CLBlockLightColorProvider provider) {
        if (provider != null) BLOCK_PROVIDERS.add(provider);
    }

    public static void registerEntity(CLEntityLightColorProvider provider) {
        if (provider != null) ENTITY_PROVIDERS.add(provider);
    }

    public static boolean hasBlockProviders() {
        return !BLOCK_PROVIDERS.isEmpty();
    }

    /** First provider answer for this block, converted to RGB4, or null when all defer. */
    @Nullable
    public static ColorRGB4 getBlockColor(Level level, BlockPos pos, BlockState state) {
        if (BLOCK_PROVIDERS.isEmpty()) return null;
        for (CLBlockLightColorProvider provider : BLOCK_PROVIDERS) {
            int rgb8 = provider.getLightColor(level, pos, state);
            if (rgb8 >= 0) return fromRGB8(rgb8);
        }
        return null;
    }

    /** First provider answer for this entity, converted to RGB4, or null when all defer. */
    @Nullable
    public static ColorRGB4 getEntityColor(Entity entity) {
        if (ENTITY_PROVIDERS.isEmpty()) return null;
        for (CLEntityLightColorProvider provider : ENTITY_PROVIDERS) {
            int rgb8 = provider.getLightColor(entity);
            if (rgb8 >= 0) return fromRGB8(rgb8);
        }
        return null;
    }

    private static ColorRGB4 fromRGB8(int rgb8) {
        return ColorRGB4.fromRGB8((rgb8 >>> 16) & 0xFF, (rgb8 >>> 8) & 0xFF, rgb8 & 0xFF);
    }
}
