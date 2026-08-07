package net.camacraft.colorfullighting.mixin.compat.sodium;

import net.camacraft.colorfullighting.common.ColoredLightEngine;
import net.camacraft.colorfullighting.compat.sodium.SodiumPackedLightData;
import me.jellysquid.mods.sodium.client.model.light.data.LightDataAccess;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(LightDataAccess.class)
public abstract class SodiumLightDataAccessMixin {

    @Shadow protected BlockAndTintGetter world;

    // Sodium's own per-instance scratch pos. compute() runs for every cell of every meshed
    // block's 3x3x3 light neighborhood on all chunk-build workers; allocating a fresh BlockPos
    // there (as this overwrite originally did) was several percent of the game's total
    // allocation pressure in the 2026-08-06 JFR capture.
    @Shadow(remap = false) @org.spongepowered.asm.mixin.Final private BlockPos.MutableBlockPos pos;

    @Shadow public static int packBL(int blockLight) { return 0; }
    @Shadow public static int packSL(int skyLight) { return 0; }
    @Shadow public static int packLU(int luminance) { return 0; }
    @Shadow public static int packAO(float ao) { return 0; }
    @Shadow public static int packEM(boolean emissive) { return 0; }
    @Shadow public static int packOP(boolean opaque) { return 0; }
    @Shadow public static int packFO(boolean opaque) { return 0; }
    @Shadow public static int packFC(boolean fullCube) { return 0; }

    /**
     * @author Erykczy
     * @reason Inject colored lighting logic into Sodium's light data computation
     */
    @Overwrite(remap = false)
    protected int compute(int x, int y, int z) {
        BlockPos pos = this.pos.set(x, y, z);
        BlockAndTintGetter world = this.world;

        BlockState state = world.getBlockState(pos);

        boolean em = state.emissiveRendering(world, pos);
        boolean op = state.isViewBlocking(world, pos) && state.getLightBlock(world, pos) != 0;
        boolean fo = state.isSolidRender(world, pos);
        boolean fc = state.isCollisionShapeFullBlock(world, pos);

        int lu = state.getLightEmission(world, pos);

        int bl;
        int sl;
        
        if (fo && lu == 0) {
            bl = 0;
            sl = 0;
        } else {
            if (em) {
                bl = world.getBrightness(LightLayer.BLOCK, pos);
                sl = world.getBrightness(LightLayer.SKY, pos);
            } else {
                int packedCoords = LevelRenderer.getLightColor(world, state, pos);
                
                if (ColoredLightEngine.isEnabled()) {
                    // Check if it is our packed format (alpha bits set to 0xF)
                    if ((packedCoords >>> 28) == 0xF) {
                         sl = SodiumPackedLightData.unpackSkyLight(packedCoords);
                         bl = 0; // We don't use cached BL anymore as we fetch color separately
                    } else {
                         bl = LightTexture.block(packedCoords);
                         sl = LightTexture.sky(packedCoords);
                    }
                } else {
                    // Vanilla behavior
                    bl = LightTexture.block(packedCoords);
                    sl = LightTexture.sky(packedCoords);
                }
            }
        }

        float ao;
        if (lu == 0) {
            ao = state.getShadeBrightness(world, pos);
        } else {
            ao = 1.0f;
        }

        return packFC(fc) | packFO(fo) | packOP(op) | packEM(em) | packAO(ao) | packLU(lu) | packSL(sl) | packBL(bl);
    }
}
