package me.erykczy.colorfullighting.compat.immersiveportals;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import me.erykczy.colorfullighting.common.ColoredLightEngine;
import me.erykczy.colorfullighting.common.ViewArea;
import me.erykczy.colorfullighting.common.accessors.mixin.LevelAttachments;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Keeps colored light alive in client levels the player is not currently in.
 *
 * <p>Immersive Portals (and mods like it) keep several ClientLevels loaded at once and render a
 * remote dimension through portals. The remote level never receives LevelTickEvent, so the
 * engine's view area is never established there and every sample takes the vanilla-white
 * out-of-area fallback until the player has visited that dimension once. Rather than binding to
 * any Immersive Portals API, this tracks which chunks the client actually has loaded in each
 * level — chunks only exist in a non-current level when such a mod put them there — and mirrors
 * them into that level's engine as extra light regions, grouped into cells of
 * {@code CELL_SIZE * CELL_SIZE} chunks.
 *
 * <p>While a level IS the current one its coverage comes from the engine's view area, so its cells
 * are withdrawn (chunk tracking continues, ready for the level to go remote again). The withdrawal
 * also cleans up the cells a level accumulated while it was remote.
 *
 * <p>Client thread only: chunk load/unload events, level unload, and the tick handler all run there.
 */
public final class ImmersivePortalsCompat {
    /** Cells are 2^CELL_SHIFT chunks on a side. */
    private static final int CELL_SHIFT = 3;
    private static final int CELL_SIZE = 1 << CELL_SHIFT;
    /**
     * How often (in client ticks) tracked chunks are checked against the chunk source, dropping any
     * whose unload event never arrived. Safety net only; unloads are normally seen as events.
     */
    private static final int VALIDATE_INTERVAL_TICKS = 100;

    /**
     * Weak keys: a level that unloads without its LevelEvent.Unload firing must not be pinned
     * forever. Entries are normally removed explicitly in {@link #onLevelUnload}.
     */
    private static final Map<Level, LevelState> STATES = new WeakHashMap<>();
    private static final Map<Long, ColoredLightEngine.LightRegion> NO_REGIONS = Map.of();
    private static int tickCounter;

    private ImmersivePortalsCompat() {}

    private static final class LevelState {
        /** Cell key -> chunks the client currently has loaded in that cell. */
        final Long2ObjectOpenHashMap<Set<ChunkPos>> cellChunks = new Long2ObjectOpenHashMap<>();
        /** Regions mirroring cellChunks; values are immutable snapshots handed to the engine. */
        final Map<Long, ColoredLightEngine.LightRegion> regions = new HashMap<>();
        final LongOpenHashSet dirtyCells = new LongOpenHashSet();
        /** Whether the engine currently holds this level's cells (i.e. the level was remote). */
        boolean synced;
    }

    public static void onChunkLoad(ClientLevel level, ChunkPos pos) {
        LevelState state = STATES.computeIfAbsent(level, l -> new LevelState());
        long cell = cellKey(pos.x >> CELL_SHIFT, pos.z >> CELL_SHIFT);
        if (state.cellChunks.computeIfAbsent(cell, c -> new HashSet<>()).add(pos)) {
            state.dirtyCells.add(cell);
        }
    }

    public static void onChunkUnload(ClientLevel level, ChunkPos pos) {
        LevelState state = STATES.get(level);
        if (state == null) return;
        long cell = cellKey(pos.x >> CELL_SHIFT, pos.z >> CELL_SHIFT);
        Set<ChunkPos> chunks = state.cellChunks.get(cell);
        if (chunks != null && chunks.remove(pos)) {
            if (chunks.isEmpty()) state.cellChunks.remove(cell);
            state.dirtyCells.add(cell);
        }
    }

    public static void onLevelUnload(Level level) {
        STATES.remove(level);
    }

    /** Called once per client tick from ClientEventListener. */
    public static void clientTick(Minecraft minecraft) {
        if (STATES.isEmpty()) return;
        boolean validate = ++tickCounter % VALIDATE_INTERVAL_TICKS == 0;
        for (Map.Entry<Level, LevelState> entry : STATES.entrySet()) {
            Level level = entry.getKey();
            LevelState state = entry.getValue();
            ColoredLightEngine engine = ((LevelAttachments) level).colorfullighting$getEngine();
            if (engine == null) continue;

            if (level == minecraft.level) {
                if (state.synced) {
                    engine.syncExtraRegions(ColoredLightEngine.REGION_NAMESPACE_REMOTE_LEVEL, NO_REGIONS);
                    state.synced = false;
                }
                continue;
            }

            if (validate) validateAgainstChunkSource(level, state);
            rebuildDirtyCells(state);
            // Synced every tick even when unchanged: an engine reset (/cl on, /cl purge) drops all
            // extra regions and relies on their owners re-syncing them.
            engine.syncExtraRegions(ColoredLightEngine.REGION_NAMESPACE_REMOTE_LEVEL, state.regions);
            state.synced = true;
        }
    }

    private static void validateAgainstChunkSource(Level level, LevelState state) {
        var chunkSource = level.getChunkSource();
        for (var it = state.cellChunks.long2ObjectEntrySet().fastIterator(); it.hasNext(); ) {
            Long2ObjectMap.Entry<Set<ChunkPos>> entry = it.next();
            Set<ChunkPos> chunks = entry.getValue();
            if (chunks.removeIf(pos -> !chunkSource.hasChunk(pos.x, pos.z))) {
                state.dirtyCells.add(entry.getLongKey());
                if (chunks.isEmpty()) it.remove();
            }
        }
    }

    private static void rebuildDirtyCells(LevelState state) {
        if (state.dirtyCells.isEmpty()) return;
        for (LongIterator it = state.dirtyCells.iterator(); it.hasNext(); ) {
            long cell = it.nextLong();
            long regionKey = regionKey(cell);
            Set<ChunkPos> chunks = state.cellChunks.get(cell);
            if (chunks == null || chunks.isEmpty()) {
                state.regions.remove(regionKey);
            } else {
                int minChunkX = cellX(cell) << CELL_SHIFT;
                int minChunkZ = cellZ(cell) << CELL_SHIFT;
                // The cell's chunks plus a 1-chunk border, mirroring ViewArea's border: propagation
                // out of an inner chunk writes into its neighbours' sections.
                ViewArea area = new ViewArea(minChunkX - 1, minChunkZ - 1,
                        minChunkX + CELL_SIZE, minChunkZ + CELL_SIZE);
                state.regions.put(regionKey, new ColoredLightEngine.LightRegion(area, Set.copyOf(chunks)));
            }
        }
        state.dirtyCells.clear();
    }

    private static long cellKey(int cellX, int cellZ) {
        return ((long) cellX << 32) | (cellZ & 0xFFFFFFFFL);
    }

    private static int cellX(long cellKey) {
        return (int) (cellKey >> 32);
    }

    private static int cellZ(long cellKey) {
        return (int) cellKey;
    }

    /**
     * The engine-facing region key: the cell coordinates packed into 24 bits each (far beyond the
     * world border at 128-block cells) under the remote-level namespace.
     */
    private static long regionKey(long cellKey) {
        return ColoredLightEngine.REGION_NAMESPACE_REMOTE_LEVEL
                | ((cellX(cellKey) & 0xFFFFFFL) << 24)
                | (cellZ(cellKey) & 0xFFFFFFL);
    }
}
