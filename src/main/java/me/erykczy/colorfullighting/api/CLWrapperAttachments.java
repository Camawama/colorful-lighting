package me.erykczy.colorfullighting.api;

import me.erykczy.colorfullighting.common.BlockEntityNbtCache;
import me.erykczy.colorfullighting.common.ColoredLightEngine;
import me.erykczy.colorfullighting.common.accessors.LevelAccessor;
import me.erykczy.colorfullighting.common.accessors.mixin.LevelAttachments;
import me.erykczy.colorfullighting.compat.flywheel.FlywheelCompat;
import me.erykczy.colorfullighting.compat.valkyrienskies.VsCompat;
import net.minecraft.world.level.Level;

public interface CLWrapperAttachments extends LevelAttachments {
	Level colorfullighting$getWrappedLevel();
	
	@Override
	default ColoredLightEngine colorfullighting$getEngine() {
		return ((LevelAttachments) colorfullighting$getWrappedLevel()).colorfullighting$getEngine();
	}
	
	@Override
	default VsCompat colorfullighting$getVSCompat() {
		return ((LevelAttachments) colorfullighting$getWrappedLevel()).colorfullighting$getVSCompat();
	}
	
	@Override
	default LevelAccessor colorfullighting$getAccessor() {
		return ((LevelAttachments) colorfullighting$getWrappedLevel()).colorfullighting$getAccessor();
	}
	
	@Override
	default BlockEntityNbtCache colorfullighting$getNbtCache() {
		return ((LevelAttachments) colorfullighting$getWrappedLevel()).colorfullighting$getNbtCache();
	}
	
	@Override
	default FlywheelCompat colorfullighting$getFlywheelCompat() {
		return ((LevelAttachments) colorfullighting$getWrappedLevel()).colorfullighting$getFlywheelCompat();
	}
}
