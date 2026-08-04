package me.erykczy.colorfullighting.common.accessors.mixin;

import com.mojang.blaze3d.shaders.AbstractUniform;
import com.mojang.blaze3d.shaders.Uniform;

public interface CLExtendedShader {
	Uniform colorfullighting$getNightVibrancy();
}
