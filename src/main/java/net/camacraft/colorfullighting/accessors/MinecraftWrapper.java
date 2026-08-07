package net.camacraft.colorfullighting.accessors;

import net.camacraft.colorfullighting.common.accessors.ClientAccessor;
import net.camacraft.colorfullighting.common.accessors.LevelAccessor;
import net.camacraft.colorfullighting.common.accessors.PlayerAccessor;
import net.camacraft.colorfullighting.common.accessors.mixin.LevelAttachments;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;

public class MinecraftWrapper implements ClientAccessor {
    private final Minecraft minecraft;

    public MinecraftWrapper(@NotNull Minecraft minecraft) {
        this.minecraft = minecraft;
    }

    @Override
    public @Nullable LevelAccessor getLevel() {
        if(minecraft.level == null) return null;
        return ((LevelAttachments) minecraft.level).colorfullighting$getAccessor();
    }

    @Override
    public @Nullable PlayerAccessor getPlayer() {
        if(minecraft.player == null) return null;
        return new PlayerWrapper(minecraft.player);
    }

    @Override
    public int getRenderDistance() {
        return minecraft.options.getEffectiveRenderDistance();
    }
}
