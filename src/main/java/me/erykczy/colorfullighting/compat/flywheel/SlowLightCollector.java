package me.erykczy.colorfullighting.compat.flywheel;

import me.erykczy.colorfullighting.common.ColoredLightEngine;
import me.erykczy.colorfullighting.common.accessors.mixin.LevelAttachments;
import me.erykczy.colorfullighting.common.util.ColorRGB4;
import me.erykczy.colorfullighting.common.util.ColorRGB8;
import me.erykczy.colorfullighting.common.util.PackedLightData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LevelAccessor;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

public class SlowLightCollector {
    /**
     * The engine of the level this collector's storage belongs to, or null when the level has
     * none. Every Level gets its engine in the Level constructor (LevelMixin.postInit), long
     * before flywheel can build a LightStorage on it, so resolving once here is safe; a
     * LevelAccessor that is not a Level (no LevelAttachments) has no engine and stays null.
     */
    @Nullable
    private final ColoredLightEngine engine;

    public SlowLightCollector(@Nullable LevelAccessor level) {
        this.engine = level instanceof LevelAttachments attachments ? attachments.colorfullighting$getEngine() : null;
    }

    protected void collectLightData(long ptr, long section) {
        // No engine for this level, or the engine is disabled: leave the section zeroed (alpha
        // nibble 0) — the flywheel shaders fall back to the vanilla per-instance lightmap for
        // entries without the colored magic bits. Writing packed black instead would override
        // vanilla block light with darkness on everything flywheel renders. Sampling only this
        // level's engine is what keeps a Nether portal rendered by Immersive Portals from
        // painting Overworld colors onto Nether-side flywheel objects and vice versa.
        if (engine == null || !ColoredLightEngine.isEnabled()) return;

        var blockPos = new BlockPos.MutableBlockPos();
        int xMin = SectionPos.sectionToBlockCoord(SectionPos.x(section));
        int yMin = SectionPos.sectionToBlockCoord(SectionPos.y(section));
        int zMin = SectionPos.sectionToBlockCoord(SectionPos.z(section));

        for (int y = -1; y < 17; y++) {
            for (int z = -1; z < 17; z++) {
                for (int x = -1; x < 17; x++) {
                    blockPos.set(xMin + x, yMin + y, zMin + z);
                    write(ptr, x, y, z, engine.sampleLightColor(blockPos));
                }
            }
        }
    }

    protected static void write(long ptr, int x, int y, int z, ColorRGB4 color) {
        int x1 = x + 1;
        int y1 = y + 1;
        int z1 = z + 1;

        int offset = (x1 + z1 * 18 + y1 * 18 * 18) * 4;

        MemoryUtil.memPutInt(ptr + offset, PackedLightData.packData(0, ColorRGB8.fromRGB4(color)));
    }
}
