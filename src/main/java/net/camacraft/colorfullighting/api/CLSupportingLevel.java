package net.camacraft.colorfullighting.api;

/**
 * Marker interface that opts a {@link net.minecraft.world.level.Level} implementation into
 * colored lighting. When a constructed level implements this, Colorful Lighting creates its
 * per-level machinery (light engine, caches, compat state) for it; levels without it are left
 * completely untouched and see vanilla lighting.
 *
 * <p>Vanilla {@code ClientLevel} already gets this via mixin. Implement it (typically via mixin
 * or directly on your class) for custom client-side level types that should carry colored light,
 * e.g. remote-dimension mirrors or ship worlds. Pair it with {@link CLClientLevel} if your level
 * is rendered by something other than the vanilla {@code LevelRenderer}.
 */
public interface CLSupportingLevel {
}
