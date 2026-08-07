package net.camacraft.colorfullighting.compat.sodium;

public interface ChunkShaderInterfaceExtension {
    void setNightVibrancy(float vibrancy);
    void setColoredLightingEnabled(boolean enabled);
    void onShaderReload();
}
