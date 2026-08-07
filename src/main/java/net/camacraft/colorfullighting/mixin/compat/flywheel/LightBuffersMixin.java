package net.camacraft.colorfullighting.mixin.compat.flywheel;

import dev.engine_room.flywheel.backend.engine.LightStorage;
import dev.engine_room.flywheel.backend.engine.indirect.LightBuffers;
import dev.engine_room.flywheel.backend.engine.indirect.StagingBuffer;
import net.camacraft.colorfullighting.compat.flywheel.ColoredLightFlywheelStorage;
import net.camacraft.colorfullighting.compat.flywheel.ColoredLightStorageHolder;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Indirect-backend bind hook. bind() has no LightStorage parameter, but flush(StagingBuffer,
 * LightStorage) does (it is also what calls LightStorage.uploadChangedSections, where our
 * upload rides along — see LightStorageMixin), and a LightBuffers is 1:1 with its engine's
 * LightStorage — so flush stashes the per-level colored storage for bind(), same pattern as
 * InstancedLightMixin.
 */
@Mixin(value = LightBuffers.class, remap = false)
public class LightBuffersMixin {
    @Unique
    @Nullable
    private ColoredLightFlywheelStorage colorfullighting$storage;

    @Inject(method = "flush", at = @At("TAIL"))
    private void colorfullighting$flush(StagingBuffer staging, LightStorage light, CallbackInfo ci) {
        colorfullighting$storage = ((ColoredLightStorageHolder) (Object) light).colorfullighting$getColoredLightStorage();
    }

    @Inject(method = "bind", at = @At("TAIL"))
    private void colorfullighting$bind(CallbackInfo ci) {
        if (colorfullighting$storage != null) {
            colorfullighting$storage.bindBuffers();
        }
    }
}
