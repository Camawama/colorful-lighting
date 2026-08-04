package me.erykczy.colorfullighting.compat.flerovium;

import me.erykczy.colorfullighting.common.util.PackedLightData;
import net.minecraft.client.renderer.LightTexture;

public class FleroviumCompat {
    /**
     * Replacement for Flerovium's whole-int {@code Math.max(bakedLight, light)} merge in its
     * fast item/decal vertex writers. Our packed colored format sets the top nibble to 0xF,
     * which makes the packed int negative — a signed whole-int max then collapses the result
     * to the baked value (0 for normal quads), rendering everything black.
     */
    public static int mergeBakedLight(int bakedLight, int light) {
        if ((light >>> 28) != 0xF)
            return Math.max(bakedLight, light);
        if (bakedLight == 0)
            return light;
        // bakedLight is vanilla-packed (emissive model quads, e.g. FULL_BRIGHT);
        // lift it into the colored format as white light of the same level
        int block8 = Math.min(15, Math.max(0, LightTexture.block(bakedLight))) * 17;
        int sky4 = LightTexture.sky(bakedLight);
        return PackedLightData.max(light, PackedLightData.packData(sky4, block8, block8, block8));
    }
}
