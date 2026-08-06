package me.erykczy.colorfullighting.common.accessors.mixin;

import me.erykczy.colorfullighting.common.BlockEntityNbtCache;
import me.erykczy.colorfullighting.common.ColoredLightEngine;
import me.erykczy.colorfullighting.common.accessors.LevelAccessor;
import me.erykczy.colorfullighting.compat.distanthorizons.DhColorCache;
import me.erykczy.colorfullighting.compat.flywheel.FlywheelCompat;
import me.erykczy.colorfullighting.compat.valkyrienskies.VsCompat;

public interface LevelAttachments {
	ColoredLightEngine colorfullighting$getEngine();

	VsCompat colorfullighting$getVSCompat();

	LevelAccessor colorfullighting$getAccessor();

	BlockEntityNbtCache colorfullighting$getNbtCache();

	FlywheelCompat colorfullighting$getFlywheelCompat();

	/** Distant Horizons LOD colour memory; created lazily by DhCompat on the client thread. */
	DhColorCache colorfullighting$getDhColorCache();

	void colorfullighting$setDhColorCache(DhColorCache cache);
}
