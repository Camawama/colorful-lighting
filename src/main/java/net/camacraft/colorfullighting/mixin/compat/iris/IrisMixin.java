package net.camacraft.colorfullighting.mixin.compat.iris;

import net.camacraft.colorfullighting.common.ColoredLightEngine;
import net.camacraft.colorfullighting.common.accessors.mixin.iris.CustomShaderProperties;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.properties.ShaderProperties;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;
import java.util.OptionalInt;

@Mixin(value = Iris.class, remap = false)
public class IrisMixin {
	@Inject(at = @At(value = "INVOKE", target = "Lnet/irisshaders/iris/Iris;reload()V", shift = At.Shift.AFTER), method = "handleKeybinds")
	private static void postReload(Minecraft minecraft, CallbackInfo ci) {
		if (!ColoredLightEngine.isEnabled()) return;
		
		Optional< ShaderPack> pack = Iris.getCurrentPack();
		if (pack.isEmpty()) return;
		
		ShaderProperties properties = ((ShaderPackAccessor) pack.get()).getShaderProperties();
		OptionalInt value = ((CustomShaderProperties) properties).colorfullighting$getCompatStatus();
		
		if (value.isEmpty()) {
			if (Minecraft.getInstance().player == null) return;
			
			Minecraft.getInstance().player.sendSystemMessage(
					Component.translatable("colorfullighting.message.iris_warning")
			);
		}
	}
}
