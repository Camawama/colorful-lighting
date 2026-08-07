package net.camacraft.colorfullighting.mixin;

import net.camacraft.colorfullighting.api.CLSupportingLevel;
import net.camacraft.colorfullighting.common.accessors.mixin.ClientLevelAccessor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(ClientLevel.class)
public class ClientLevelMixin implements ClientLevelAccessor, CLSupportingLevel {
	@Shadow
	@Final
	private LevelRenderer levelRenderer;
	
	@Override
	public LevelRenderer colorfullighting$getLevelRenderer() {
		return levelRenderer;
	}
}
