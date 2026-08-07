package me.erykczy.colorfullighting.api;

import me.erykczy.colorfullighting.common.BlockEntityNbtCache;
import me.erykczy.colorfullighting.common.ColoredLightEngine;
import me.erykczy.colorfullighting.common.accessors.LevelAccessor;
import me.erykczy.colorfullighting.common.accessors.mixin.LevelAttachments;
import me.erykczy.colorfullighting.compat.distanthorizons.DhColorCache;
import me.erykczy.colorfullighting.compat.dynamiclights.DynamicLightsCompat;
import me.erykczy.colorfullighting.compat.flywheel.FlywheelCompat;
import me.erykczy.colorfullighting.compat.valkyrienskies.VsCompat;
import net.minecraft.world.level.Level;

/**
 * Advanced integration for <b>wrapper levels</b>: level implementations that delegate to a real
 * level (portal mirrors, ship-world views, ...). Implementing this forwards all of Colorful
 * Lighting's per-level state to the wrapped level, so light sampled through the wrapper matches
 * the wrapped level exactly and no second engine is created. Implement
 * {@link #colorfullighting$getWrappedLevel()} and the defaults handle the rest.
 *
 * <p><b>Stability note:</b> unlike the rest of the {@code api} package, this interface extends
 * the internal {@link LevelAttachments} and re-exports internal types, because a wrapper must
 * mirror whatever per-level state the mod currently keeps. New methods may appear here between
 * versions (with delegating defaults, so implementors keep compiling). Only
 * {@code colorfullighting$getWrappedLevel()} is a stable contract. If you do not wrap an
 * existing level, use {@link CLSupportingLevel} instead.
 */
public interface CLWrapperAttachments extends LevelAttachments {
	Level colorfullighting$getWrappedLevel();

	private LevelAttachments wrapped() {
		return (LevelAttachments) colorfullighting$getWrappedLevel();
	}

	@Override
	default ColoredLightEngine colorfullighting$getEngine() {
		return wrapped().colorfullighting$getEngine();
	}

	@Override
	default VsCompat colorfullighting$getVSCompat() {
		return wrapped().colorfullighting$getVSCompat();
	}

	@Override
	default DynamicLightsCompat colorfullighting$getDynamicLights() {
		return wrapped().colorfullighting$getDynamicLights();
	}

	@Override
	default LevelAccessor colorfullighting$getAccessor() {
		return wrapped().colorfullighting$getAccessor();
	}

	@Override
	default BlockEntityNbtCache colorfullighting$getNbtCache() {
		return wrapped().colorfullighting$getNbtCache();
	}

	@Override
	default FlywheelCompat colorfullighting$getFlywheelCompat() {
		return wrapped().colorfullighting$getFlywheelCompat();
	}

	@Override
	default DhColorCache colorfullighting$getDhColorCache() {
		return wrapped().colorfullighting$getDhColorCache();
	}

	@Override
	default void colorfullighting$setDhColorCache(DhColorCache cache) {
		wrapped().colorfullighting$setDhColorCache(cache);
	}
}
