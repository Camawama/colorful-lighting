package net.camacraft.colorfullighting.mixin.compat.nvidium;

import me.cortex.nvidium.sodiumCompat.SodiumResultCompatibility;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

@Mixin(value = SodiumResultCompatibility.class, remap = false)
public class SodiumResultCompatibilityMixin {
	@ModifyConstant(method = "repackage", constant = @Constant(intValue = 16, ordinal = 0))
	private static int swapSize(int constant) {
		return 20;
	}
	
	/**
	 * @author GiantLuigi4
	 * @reason vertex format is 20 bytes, original function is hardcoded to a 16 byte format
	 *         this is apparently the one thing in nvidium/acedium's code that is hardcoded outside of being a random floating 16, probably using the static format constant
	 *         I'm working with decompiled code however so I don't know for sure
	 */
	@Overwrite
	private static void copyQuad(long from, long too) {
		for (long i = 0L; i < 80L; i += 8L) {
			MemoryUtil.memPutLong(too + i, MemoryUtil.memGetLong(from + i));
		}
	}
}
