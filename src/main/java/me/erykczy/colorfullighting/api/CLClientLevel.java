package me.erykczy.colorfullighting.api;

/**
 * Implement on a custom client level (see {@link CLSupportingLevel}) that is rendered by
 * something other than the vanilla {@code LevelRenderer}. When colored light changes, Colorful
 * Lighting needs to tell the renderer which sections to re-mesh; for vanilla levels it calls
 * {@code LevelRenderer.setSectionDirty}, and for levels implementing this interface it calls
 * {@link #colorfullighting$setSectionDirty(int, int, int)} instead, so your renderer can react.
 *
 * <p>Coordinates are section coordinates (block coordinates {@code >> 4}).
 */
public interface CLClientLevel {
	void colorfullighting$setSectionDirty(int x, int y, int z);
}
