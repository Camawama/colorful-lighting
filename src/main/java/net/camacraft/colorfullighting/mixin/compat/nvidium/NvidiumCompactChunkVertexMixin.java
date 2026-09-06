package net.camacraft.colorfullighting.mixin.compat.nvidium;

import me.jellysquid.mods.sodium.client.gl.attribute.GlVertexFormat;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.material.Material;
import me.jellysquid.mods.sodium.client.render.chunk.vertex.format.ChunkMeshAttribute;
import me.jellysquid.mods.sodium.client.render.chunk.vertex.format.impl.CompactChunkVertex;
import net.caffeinemc.mods.sodium.api.util.ColorABGR;
import net.caffeinemc.mods.sodium.api.util.ColorU8;
import me.jellysquid.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Overrides Nvidium/Acedium's vertex format to be the same as the embeddium/sodium one
 */
@Pseudo
@Mixin(value = me.cortex.nvidium.sodiumCompat.NvidiumCompactChunkVertex.class, remap = false)
public class NvidiumCompactChunkVertexMixin {
	@Shadow
	@Final
	public static GlVertexFormat<ChunkMeshAttribute> VERTEX_FORMAT;
	
//	@Inject(method = "getEncoder", at = @At("RETURN"), cancellable = true, require = 0)
//    private void colorfullighting$wrapEncoder(CallbackInfoReturnable<ChunkVertexEncoder> cir) {
//        cir.setReturnValue(NvidiumCompat.wrapEncoder(cir.getReturnValue()));
//    }
	
	@Unique
	private static final CompactChunkVertex vertex = new CompactChunkVertex();
	
	/**
	 * @author GiantLuigi4
	 * @reason force embeddium/sodium format
	 */
	@Overwrite
	public float getTextureScale() {
		return vertex.getTextureScale();
	}
	
	/**
	 * @author GiantLuigi4
	 * @reason force embeddium/sodium format
	 */
	@Overwrite
	public float getPositionScale() {
		return vertex.getPositionScale();
	}
	
	/**
	 * @author GiantLuigi4
	 * @reason force embeddium/sodium format
	 */
	@Overwrite
	public float getPositionOffset() {
		return vertex.getPositionOffset();
	}
	
	/**
	 * @author GiantLuigi4
	 * @reason force embeddium/sodium format
	 */
	@Overwrite
	public GlVertexFormat<ChunkMeshAttribute> getVertexFormat() {
		return vertex.getVertexFormat();
	}
	
	/**
	 * @author GiantLuigi4
	 * @reason force embeddium/sodium format
	 */
	@Overwrite
	public ChunkVertexEncoder getEncoder() {
		return vertex.getEncoder();
	}
}
