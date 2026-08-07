package net.camacraft.colorfullighting.mixin;

import net.camacraft.colorfullighting.common.ColoredLightEngine;
import net.camacraft.colorfullighting.common.accessors.mixin.LevelAttachments;
import net.camacraft.colorfullighting.common.accessors.mixin.LightEngineAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.lighting.BlockLightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BlockLightEngine.class)
public abstract class BlockLightEngineMixin {
    @Inject(method = "checkNode", at = @At("TAIL"))
    private void colorfullighting$checkNode(long packedPos, CallbackInfo ci) {
        if (!ColoredLightEngine.isEnabled()) {
            return;
        }
        if(!Minecraft.getInstance().isSameThread()) return; // only client side
        // virtual levels (e.g. Create's VirtualRenderWorld) have no colored light engine
        if (!(((LightEngineAccessor) this).colorfullighting$getChunkGetter().getLevel() instanceof LevelAttachments attachments)) return;
        ColoredLightEngine engine = attachments.colorfullighting$getEngine();
        if (engine == null) return;
        engine.onBlockLightPropertiesChanged(BlockPos.of(packedPos));
    }
}
