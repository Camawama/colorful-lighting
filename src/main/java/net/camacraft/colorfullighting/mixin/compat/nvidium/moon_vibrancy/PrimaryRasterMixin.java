package net.camacraft.colorfullighting.mixin.compat.nvidium.moon_vibrancy;

import me.cortex.nvidium.gl.shader.Shader;
import me.cortex.nvidium.renderers.PrimaryTerrainRasterizer;
import me.cortex.nvidium.renderers.TranslucentTerrainRasterizer;
import net.camacraft.colorfullighting.compat.sodium.ChunkShaderInterfaceExtension;
import net.camacraft.colorfullighting.compat.sodium.SodiumShaderCompat;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = PrimaryTerrainRasterizer.class, remap = false)
public class PrimaryRasterMixin {
	@Shadow
	@Final
	private Shader shader;
	
	@Inject(at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/NVMeshShader;glMultiDrawMeshTasksIndirectNV(JII)V"), method = "raster")
	public void preRaster(int regionCount, long commandAddr, CallbackInfo ci) {
		if (shader instanceof ChunkShaderInterfaceExtension extension) {
			SodiumShaderCompat.setupShader(extension);
		}
	}
}
