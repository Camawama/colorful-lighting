package net.camacraft.colorfullighting.compat.sodium;

import net.camacraft.colorfullighting.common.ColoredLightEngine;
import net.camacraft.colorfullighting.common.Config;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

public class SodiumShaderCompat {
	public static void setupShader(ChunkShaderInterfaceExtension extension) {
		// TODO: original code made sure the lighting engine is not null
        //       unsure if having this be dependent on if the world instance has a colored lighting engine is important or not
//        ColoredLightEngine engine = ColoredLightEngine.getInstance();
//        extension.setColoredLightingEnabled(engine != null && ColoredLightEngine.isEnabled());
	    
	    extension.setColoredLightingEnabled(ColoredLightEngine.isEnabled());
	
	    ClientLevel level = Minecraft.getInstance().level;
	    if (level != null) {
	        // getStarBrightness is 1.0 at midnight, 0.0 at noon.
	        // This directly serves as our "night factor".
	        float nightFactor = level.getStarBrightness(Minecraft.getInstance().getFrameTime());
	
	        int phase = level.getMoonPhase();
	        float moonVibrancy = Config.getMoonVibrancy(phase);
	
	        float totalVibrancy = nightFactor * moonVibrancy;
	        
	        // Ensure it's clamped 0..1
	        totalVibrancy = Math.max(0.0f, Math.min(1.0f, totalVibrancy));
	
	        extension.setNightVibrancy(totalVibrancy);
	    }
	}
}
