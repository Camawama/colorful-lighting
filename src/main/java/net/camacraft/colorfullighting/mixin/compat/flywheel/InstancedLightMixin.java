package net.camacraft.colorfullighting.mixin.compat.flywheel;

import dev.engine_room.flywheel.backend.engine.LightStorage;
import dev.engine_room.flywheel.backend.engine.instancing.InstancedLight;
import net.camacraft.colorfullighting.compat.flywheel.ColoredLightFlywheelStorage;
import net.camacraft.colorfullighting.compat.flywheel.ColoredLightStorageHolder;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The instancing backend does not use LightBuffers/StagingBuffer (those are indirect-backend
 * classes hooked by LightBuffersMixin/LightStorageMixin); it flushes and binds light through
 * its own InstancedLight. Without these hooks the colored light SSBO (binding 8) is never
 * uploaded or bound under instancing, so flywheel-rendered objects get no colored light.
 *
 * <p>bind() has no LightStorage parameter, but flush(LightStorage) does, and an InstancedLight
 * is 1:1 with its draw manager's LightStorage — so flush resolves the per-level colored storage
 * through the ColoredLightStorageHolder duck and stashes it for bind(). Flush runs each frame
 * before any draw of that engine, so the stash is never stale.
 */
@Mixin(value = InstancedLight.class, remap = false)
public class InstancedLightMixin {
    @Unique
    @Nullable
    private ColoredLightFlywheelStorage colorfullighting$storage;

    @Inject(method = "flush", at = @At("TAIL"))
    private void colorfullighting$flush(LightStorage lightStorage, CallbackInfo ci) {
        colorfullighting$storage = ((ColoredLightStorageHolder) (Object) lightStorage).colorfullighting$getColoredLightStorage();
        if (colorfullighting$storage != null) {
            colorfullighting$storage.uploadChangedSectionsDirect();
        }
    }

    @Inject(method = "bind", at = @At("TAIL"))
    private void colorfullighting$bind(CallbackInfo ci) {
        if (colorfullighting$storage != null) {
            colorfullighting$storage.bindBuffers();
        }
    }
}
