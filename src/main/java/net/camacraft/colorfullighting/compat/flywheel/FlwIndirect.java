package net.camacraft.colorfullighting.compat.flywheel;

import dev.engine_room.flywheel.backend.gl.GlCompat;
import dev.engine_room.flywheel.backend.glsl.GlslVersion;

// prevent class loader jank
public class FlwIndirect {
	public static boolean checkVersion() {
		return GlCompat.MAX_GLSL_VERSION.compareTo(GlslVersion.V430) < 0;
	}
}
