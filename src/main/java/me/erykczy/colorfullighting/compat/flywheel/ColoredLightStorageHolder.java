package me.erykczy.colorfullighting.compat.flywheel;

import org.jetbrains.annotations.Nullable;

/**
 * Implemented on flywheel's LightStorage by LightStorageMixin. Each LightStorage owns the
 * ColoredLightFlywheelStorage for its own level; anything handed a LightStorage (the flush
 * methods of InstancedLight and LightBuffers) reaches the matching colored-light storage
 * through this instead of a global.
 */
public interface ColoredLightStorageHolder {
    @Nullable
    ColoredLightFlywheelStorage colorfullighting$getColoredLightStorage();
}
