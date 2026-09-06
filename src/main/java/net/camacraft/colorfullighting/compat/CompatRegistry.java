package net.camacraft.colorfullighting.compat;

import net.minecraft.resources.ResourceLocation;

import java.util.function.Function;

public interface CompatRegistry<V> {
	<T> T colorfullighting$getCompatInstance(CompatKey<V, T> key);
	
	public record CompatKey<A, B>(ResourceLocation location, Function<A, B> generator) {
	}
}
