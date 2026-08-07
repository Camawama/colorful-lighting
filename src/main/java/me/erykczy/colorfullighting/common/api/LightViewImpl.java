package me.erykczy.colorfullighting.common.api;

import me.erykczy.colorfullighting.api.CLLightView;
import me.erykczy.colorfullighting.common.ColoredLightEngine;
import me.erykczy.colorfullighting.common.accessors.mixin.LevelAttachments;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * The API's per-level light view. A thin stateless facade over the level's attachments; callers
 * cache one instance per level (documented on {@link CLLightView}), so per-call allocation on
 * creation is fine and no LevelAttachments slot is needed. The engine is re-read from the
 * attachments on every call because custom levels may gain their engine after construction.
 */
public final class LightViewImpl implements CLLightView {
    private final Level level;
    private final LevelAttachments attachments;

    public LightViewImpl(Level level, LevelAttachments attachments) {
        this.level = level;
        this.attachments = attachments;
    }

    @Override
    public Level level() {
        return level;
    }

    @Override
    public boolean isActive() {
        return ColoredLightEngine.isEnabled() && attachments.colorfullighting$getEngine() != null;
    }

    @Override
    public int sampleBlockLightColor(BlockPos pos) {
        ColoredLightEngine engine = attachments.colorfullighting$getEngine();
        if (engine == null || !ColoredLightEngine.isEnabled()) return -1;
        // packed 12-bit r<<8|g<<4|b (light minus darkness); expand each nibble to 8 bits
        int rgb4 = engine.sampleLightColorPacked(pos.getX(), pos.getY(), pos.getZ());
        int r = ((rgb4 >>> 8) & 0xF) * 17;
        int g = ((rgb4 >>> 4) & 0xF) * 17;
        int b = (rgb4 & 0xF) * 17;
        return r << 16 | g << 8 | b;
    }

    @Override
    public int samplePackedLight(BlockPos pos) {
        // Routed through the vanilla entry point our LevelRendererMixin intercepts, so callers
        // get the canonical value including emissive-block handling and compat overrides.
        return LevelRenderer.getLightColor(level, pos);
    }

    @Override
    public void notifyBlockChanged(BlockPos pos) {
        ColoredLightEngine engine = attachments.colorfullighting$getEngine();
        if (engine != null) engine.onBlockLightPropertiesChanged(pos);
    }
}
