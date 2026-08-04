package me.erykczy.colorfullighting.mixin.compat.flywheel;

import dev.engine_room.flywheel.backend.engine.LightStorage;
import dev.engine_room.flywheel.backend.engine.indirect.StagingBuffer;
import me.erykczy.colorfullighting.common.accessors.mixin.LevelAttachments;
import me.erykczy.colorfullighting.compat.flywheel.ColoredLightFlywheelStorage;
import me.erykczy.colorfullighting.compat.flywheel.ColoredLightStorageHolder;
import me.erykczy.colorfullighting.compat.flywheel.FlywheelCompat;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mirrors each LightStorage's lifecycle into a ColoredLightFlywheelStorage of its own. One
 * storage PER LightStorage, keyed to that LightStorage's level (flywheel builds an engine — and
 * thus a LightStorage — per level, and LightStorage#level() hands it to us): with Immersive
 * Portals or Ponder several levels are live at once, and a shared storage would mix sections of
 * different dimensions in one arena and break the index parity the shaders rely on. The paired
 * hooks (collectSection/endTrackingSection) also keep our arena's alloc/free sequence identical
 * to flywheel's, which is what makes the indices line up with flywheel's LightLut on the GPU.
 */
@Mixin(value = LightStorage.class, remap = false)
public class LightStorageMixin implements ColoredLightStorageHolder {
    @Unique
    @Nullable
    private ColoredLightFlywheelStorage colorfullighting$storage;

    @Override
    @Nullable
    public ColoredLightFlywheelStorage colorfullighting$getColoredLightStorage() {
        return colorfullighting$storage;
    }

    @Inject(method = "<init>", at = @At("TAIL"))
    private void colorfullighting$init(CallbackInfo ci) {
        if (FlywheelCompat.isAvailable()) {
	        LevelAccessor lvl = ((LightStorage) (Object) this).level();
			FlywheelCompat compat = ((LevelAttachments) lvl).colorfullighting$getFlywheelCompat();
			if (compat == null) return;
			ColoredLightFlywheelStorage strg = new ColoredLightFlywheelStorage(lvl);
	        compat.setStorage(strg);
            colorfullighting$storage = strg;
        }
    }

    @Inject(method = "delete", at = @At("TAIL"))
    private void colorfullighting$delete(CallbackInfo ci) {
        if (colorfullighting$storage != null) {
            colorfullighting$storage.delete();
        }
    }

    @Inject(method = "collectSection", at = @At("TAIL"))
    private void colorfullighting$collectSection(long section, CallbackInfo ci) {
        if (colorfullighting$storage != null) {
            colorfullighting$storage.collectSection(section);
        }
    }

    @Inject(method = "uploadChangedSections", at = @At("TAIL"))
    private void colorfullighting$uploadChangedSections(StagingBuffer staging, int dstVbo, CallbackInfo ci) {
        if (colorfullighting$storage != null) {
            colorfullighting$storage.uploadChangedSections(staging);
        }
    }

    @Inject(method = "endTrackingSection", at = @At("TAIL"))
    private void colorfullighting$endTrackingSection(long section, CallbackInfo ci) {
        if (colorfullighting$storage != null) {
            colorfullighting$storage.removeSection(section);
        }
    }
}
