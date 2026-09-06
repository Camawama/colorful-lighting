package net.camacraft.colorfullighting.compat.distanthorizons;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.camacraft.colorfullighting.ColorfulLighting;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.Map;

/**
 * Two moving 3D textures holding the remembered light colour around the camera, sampled by the DH
 * terrain shader override. RGBA8: RGB is the cached net colour, A encodes presence and absorption
 * (0 = the cache knows nothing there and the shader falls back to plain vanilla LOD lighting,
 * ~128 = remembered, above that = remembered plus light absorption).
 *
 * <p>Three levels because one dense volume cannot span DH render distances: a near volume at 4
 * blocks/texel covering the first LOD ring in detail, a far volume at 16 blocks/texel (one texel
 * per section) reaching 1536 blocks, and an ultra volume at 64 blocks/texel (4x4x4 sections
 * max-combined per texel) reaching 6144 blocks so remembered light doesn't vanish at extreme DH
 * render distances. The July 2026 attempt proved a single 192-block-radius volume barely cleared
 * the vanilla render distance.
 *
 * <p>Render thread only. Ordinary cache stores are applied as tiny per-section
 * {@code glTexSubImage3D} patches every frame; a FULL rebuild (memset + 28MB upload per level,
 * several ms) only happens for that level when its window moves, and for everything when the
 * cache is swapped or bulk-changed (load/prune). The 2026-08-07 profile showed the old
 * rebuild-everything-on-any-change design running at its throttle ceiling: ~17ms hitches four
 * times a second, 6.9% of render-thread time. Window min corners snap to a coarse per-level
 * grid (a multiple of both the section grid and the level's texel size, so cached sections
 * never straddle texels) to keep camera movement from forcing frequent full rebuilds.
 */
public final class DhColorVolume {
    public static final int NEAR_SIZE = 192;             // texels per axis
    public static final int NEAR_BLOCKS_PER_TEXEL = 4;   // radius 384 blocks, ~28MB VRAM
    public static final int NEAR_SNAP_BLOCKS = 64;       // effective radius 320+ blocks
    public static final int FAR_SIZE = 192;
    public static final int FAR_BLOCKS_PER_TEXEL = 16;   // radius 1536 blocks, ~28MB VRAM
    public static final int FAR_SNAP_BLOCKS = 256;       // effective radius 1280+ blocks
    public static final int FAR_RADIUS_BLOCKS = FAR_SIZE * FAR_BLOCKS_PER_TEXEL / 2;
    public static final int ULTRA_SIZE = 192;
    public static final int ULTRA_BLOCKS_PER_TEXEL = 64; // radius 6144 blocks, ~28MB VRAM
    public static final int ULTRA_SNAP_BLOCKS = 1024;    // effective radius 5120+ blocks
    private static final long MIN_REBUILD_INTERVAL_NANOS = 250_000_000L; // 250ms, full rebuilds only
    /** Above this many pending section patches one full rebuild is cheaper than the patch calls. */
    private static final int FULL_REBUILD_DIRTY_THRESHOLD = 2048;
    /** Patch budget per update; the remainder stays queued for the next frame. */
    private static final int MAX_PATCH_SECTIONS_PER_UPDATE = 256;

    private final Level near = new Level(NEAR_SIZE, NEAR_BLOCKS_PER_TEXEL, NEAR_SNAP_BLOCKS);
    private final Level far = new Level(FAR_SIZE, FAR_BLOCKS_PER_TEXEL, FAR_SNAP_BLOCKS);
    private final Level ultra = new Level(ULTRA_SIZE, ULTRA_BLOCKS_PER_TEXEL, ULTRA_SNAP_BLOCKS);
    private DhColorCache lastCache;
    private int lastStructureVersion = -1;
    private long lastRebuildNanos;
    /** Reused CPU staging buffer, sized for the bigger level. */
    private ByteBuffer staging;
    /** Changed sections drained from the cache but not yet uploaded (dedup + carry-over). */
    private final LongOpenHashSet pending = new LongOpenHashSet();
    /** Sections taken from {@link #pending} for this update's patch pass. */
    private final LongArrayList patchBatch = new LongArrayList();
    /** 4x4x4 RGBA8 scratch for one near-level section patch (far/ultra use its first texel). */
    private ByteBuffer patchBuf;

    public int nearTextureId() { return near.textureId; }
    public int farTextureId() { return far.textureId; }
    public int ultraTextureId() { return ultra.textureId; }
    public int nearMinX() { return near.minX; }
    public int nearMinY() { return near.minY; }
    public int nearMinZ() { return near.minZ; }
    public int farMinX() { return far.minX; }
    public int farMinY() { return far.minY; }
    public int farMinZ() { return far.minZ; }
    public int ultraMinX() { return ultra.minX; }
    public int ultraMinY() { return ultra.minY; }
    public int ultraMinZ() { return ultra.minZ; }
    public float nearInvSizeBlocks() { return 1.0f / (NEAR_SIZE * NEAR_BLOCKS_PER_TEXEL); }
    public float farInvSizeBlocks() { return 1.0f / (FAR_SIZE * FAR_BLOCKS_PER_TEXEL); }
    public float ultraInvSizeBlocks() { return 1.0f / (ULTRA_SIZE * ULTRA_BLOCKS_PER_TEXEL); }

    private static final class Level {
        final int size;
        final int blocksPerTexel;
        /**
         * Grid the window min corner snaps to. Always a multiple of 16 (sections must not
         * straddle texels) and of {@code blocksPerTexel} (so the ultra tier's 4x4x4-section
         * texel grouping stays stable across window moves). Coarser than either so ordinary
         * movement doesn't cross a boundary every 16 blocks and force full rebuilds.
         */
        final int snapBlocks;
        int textureId;
        int minX, minY, minZ; // block coords of the window's min corner, snap-grid aligned

        Level(int size, int blocksPerTexel, int snapBlocks) {
            this.size = size;
            this.blocksPerTexel = blocksPerTexel;
            this.snapBlocks = snapBlocks;
        }

        int radiusBlocks() { return size * blocksPerTexel / 2; }
    }

    /**
     * Keeps the volumes fresh. Ordinary light changes are applied as per-section texture patches
     * (cheap, runs every frame); full per-level rebuilds only happen when that level's window
     * moves, and full rebuilds of everything only when the cache is swapped or bulk-changed.
     * Full rebuilds are throttled to {@link #MIN_REBUILD_INTERVAL_NANOS}.
     */
    public void update(DhColorCache cache, double camX, double camY, double camZ) {
        long now = System.nanoTime();
        boolean throttleOk = now - lastRebuildNanos >= MIN_REBUILD_INTERVAL_NANOS;

        boolean cacheSwapped = cache != lastCache;
        boolean bulkChanged = cache != null && cache.getStructureVersion() != lastStructureVersion;
        if (cacheSwapped || bulkChanged || near.textureId == 0) {
            // A dimension change is never throttled: until the rebuild runs, every LOD samples the
            // previous dimension's volume through the previous dimension's windows, and for the
            // first frames after an Immersive Portals teleport the LODs are all there is to see.
            if (near.textureId != 0 && !throttleOk && !cacheSwapped) return;
            if (cacheSwapped && lastCache != null) {
                ColorfulLighting.LOGGER.info("[DH volume] dimension cache swapped, rebuilding volumes");
            }
            lastRebuildNanos = now;
            lastCache = cache;
            lastStructureVersion = cache == null ? -1 : cache.getStructureVersion();
            pending.clear();
            if (cache != null) cache.clearDirtySections(); // the rebuild reads every entry anyway
            moveWindow(near, camX, camY, camZ);
            moveWindow(far, camX, camY, camZ);
            moveWindow(ultra, camX, camY, camZ);
            rebuildAll(cache);
            return;
        }

        // Per-level window moves: full rebuild of just the levels that moved. If throttled, keep
        // everything (including queued patches) for a later frame; patching against a window
        // that is about to move would be wasted work.
        boolean nearMoved = windowMoved(near, camX, camY, camZ);
        boolean farMoved = windowMoved(far, camX, camY, camZ);
        boolean ultraMoved = windowMoved(ultra, camX, camY, camZ);
        if (nearMoved || farMoved || ultraMoved) {
            if (!throttleOk) return;
            lastRebuildNanos = now;
            if (nearMoved) { moveWindow(near, camX, camY, camZ); rebuildLevel(near, cache); }
            if (farMoved) { moveWindow(far, camX, camY, camZ); rebuildLevel(far, cache); }
            if (ultraMoved) { moveWindow(ultra, camX, camY, camZ); rebuildLevel(ultra, cache); }
        }

        if (cache == null) return;
        cache.drainDirtySections(pending::add);
        if (pending.isEmpty()) return;
        if (pending.size() > FULL_REBUILD_DIRTY_THRESHOLD) {
            if (!throttleOk) return;
            lastRebuildNanos = now;
            pending.clear();
            rebuildAll(cache);
            return;
        }
        applyPatches(cache);
    }

    private void rebuildAll(DhColorCache cache) {
        long start = System.nanoTime();
        int inNear = rebuildLevel(near, cache);
        int inFar = rebuildLevel(far, cache);
        int inUltra = rebuildLevel(ultra, cache);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        if (elapsedMs > 30) {
            ColorfulLighting.LOGGER.info(
                    "[DH volume] rebuilt in {} ms: {} cached sections, {} in near window, {} in far window, {} in ultra window",
                    elapsedMs, cache == null ? 0 : cache.getSectionCount(), inNear, inFar, inUltra);
        }
    }

    private static boolean windowMoved(Level level, double camX, double camY, double camZ) {
        return windowMin(camX, level) != level.minX
                || windowMinY(camY, level) != level.minY
                || windowMin(camZ, level) != level.minZ;
    }

    private static void moveWindow(Level level, double camX, double camY, double camZ) {
        level.minX = windowMin(camX, level);
        level.minY = windowMinY(camY, level);
        level.minZ = windowMin(camZ, level);
    }

    /** Window min corner: camera-centred, snapped to the level's coarse grid (see {@link Level#snapBlocks}). */
    private static int windowMin(double cam, Level level) {
        return Math.floorDiv(Mth.floor(cam) - level.radiusBlocks(), level.snapBlocks) * level.snapBlocks;
    }

    /**
     * Vertical window min corner: pinned to the WORLD's Y band, not the camera. Terrain only
     * exists inside the build height (384 blocks in 1.20), while even the near window is 768 tall,
     * so camera-centred Y wasted half of every window and, worse, flying up pushed the ground out
     * of the near and far windows entirely — the ground then fell through to the ultra tier and a
     * lone torch ballooned into a 64-block-texel glow. Only pins when the window actually covers
     * the world band; a taller-than-window dimension falls back to camera-centred, clamped so the
     * window never leaves the band.
     */
    private static int windowMinY(double camY, Level level) {
        int windowBlocks = level.size * level.blocksPerTexel;
        int min;
        net.minecraft.client.multiplayer.ClientLevel mcLevel = net.minecraft.client.Minecraft.getInstance().level;
        if (mcLevel == null) {
            min = Mth.floor(camY) - level.radiusBlocks();
        } else {
            int worldMinY = mcLevel.getMinBuildHeight();
            int worldMaxY = mcLevel.getMaxBuildHeight();
            if (windowBlocks >= worldMaxY - worldMinY) {
                min = (worldMinY + worldMaxY) / 2 - windowBlocks / 2;
            } else {
                min = Mth.floor(camY) - level.radiusBlocks();
                min = Math.max(worldMinY, Math.min(min, worldMaxY - windowBlocks));
            }
        }
        return Math.floorDiv(min, level.snapBlocks) * level.snapBlocks;
    }

    /** @return number of cached sections that landed inside the window */
    private int rebuildLevel(Level level, DhColorCache cache) {
        int sizeBytes = level.size * level.size * level.size * 4;
        if (staging == null || staging.capacity() < sizeBytes) {
            if (staging != null) MemoryUtil.memFree(staging);
            staging = MemoryUtil.memAlloc(sizeBytes);
        }
        MemoryUtil.memSet(MemoryUtil.memAddress(staging), 0, sizeBytes);

        int written = 0;
        if (cache != null) {
            // near: a section spans 4x4x4 texels (mip); far: exactly 1; ultra: 4x4x4 sections
            // share one texel (max-combined).
            int texelsPerSection = Math.max(1, 16 / level.blocksPerTexel);
            for (Map.Entry<Long, DhColorCache.Entry> mapEntry : cache.entries()) {
                long pos = mapEntry.getKey();
                int blockX = SectionPos.x(pos) << 4;
                int blockY = SectionPos.y(pos) << 4;
                int blockZ = SectionPos.z(pos) << 4;
                int tx = (blockX - level.minX) / level.blocksPerTexel;
                int ty = (blockY - level.minY) / level.blocksPerTexel;
                int tz = (blockZ - level.minZ) / level.blocksPerTexel;
                if (blockX < level.minX || blockY < level.minY || blockZ < level.minZ
                        || tx + texelsPerSection > level.size
                        || ty + texelsPerSection > level.size
                        || tz + texelsPerSection > level.size) {
                    continue;
                }
                ++written;
                DhColorCache.Entry entry = mapEntry.getValue();
                // Alpha encodes presence AND absorption in one band: 0 = nothing remembered,
                // ~128 = remembered, up to 255 = remembered + fully absorbing. Crucially this
                // keeps absorbed-dark sections registered as REMEMBERED (presence saturates at
                // 128), so the shader's remembered-level authority renders them dark instead of
                // falling back to DH's baked glow. The band above 128 is currently informational
                // (the shader stopped decoding it once the remembered level became authoritative).
                if (level.blocksPerTexel < 16) {
                    for (int mi = 0; mi < DhColorCache.MIP_TEXELS; ++mi) {
                        int mx = mi & 3, mz = (mi >>> 2) & 3, my = (mi >>> 4) & 3;
                        int index = (((tz + mz) * level.size + (ty + my)) * level.size + (tx + mx)) * 4;
                        staging.put(index, entry.mip[mi * 4]);
                        staging.put(index + 1, entry.mip[mi * 4 + 1]);
                        staging.put(index + 2, entry.mip[mi * 4 + 2]);
                        staging.put(index + 3, (byte) (128 + ((entry.mip[mi * 4 + 3] & 0xFF) >> 1)));
                    }
                } else if (level.blocksPerTexel == 16) {
                    int index = ((tz * level.size + ty) * level.size + tx) * 4;
                    staging.put(index, entry.farR);
                    staging.put(index + 1, entry.farG);
                    staging.put(index + 2, entry.farB);
                    staging.put(index + 3, (byte) (128 + ((entry.farAbsorption & 0xFF) >> 1)));
                } else {
                    // Several sections share this texel: keep the componentwise max. That can mix
                    // hues from different lights toward white, but a texel is 64 blocks here —
                    // at that range the goal is "there is light of roughly this colour", and max
                    // never drops a remembered light the way an average diluted by empty
                    // neighbours would.
                    int index = ((tz * level.size + ty) * level.size + tx) * 4;
                    putMax(staging, index, entry.farR);
                    putMax(staging, index + 1, entry.farG);
                    putMax(staging, index + 2, entry.farB);
                    putMax(staging, index + 3, (byte) (128 + ((entry.farAbsorption & 0xFF) >> 1)));
                }
            }
        }

        staging.position(0).limit(sizeBytes);
        setUnpackState();
        if (level.textureId == 0) {
            level.textureId = GL11.glGenTextures();
            GL11.glBindTexture(GL12.GL_TEXTURE_3D, level.textureId);
            GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL12.GL_TEXTURE_WRAP_R, GL12.GL_CLAMP_TO_EDGE);
            GL12.glTexImage3D(GL12.GL_TEXTURE_3D, 0, GL11.GL_RGBA8, level.size, level.size, level.size,
                    0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, staging);
        } else {
            GL11.glBindTexture(GL12.GL_TEXTURE_3D, level.textureId);
            GL12.glTexSubImage3D(GL12.GL_TEXTURE_3D, 0, 0, 0, 0, level.size, level.size, level.size,
                    GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, staging);
        }
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0);
        return written;
    }

    private static void putMax(ByteBuffer buffer, int index, byte value) {
        if ((value & 0xFF) > (buffer.get(index) & 0xFF)) buffer.put(index, value);
    }

    /**
     * Minecraft leaves nonzero unpack state behind (row length, skips); with our tightly
     * packed buffers that would shear the upload into garbage.
     */
    private static void setUnpackState() {
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
        GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);
        GL11.glPixelStorei(GL12.GL_UNPACK_IMAGE_HEIGHT, 0);
        GL11.glPixelStorei(GL12.GL_UNPACK_SKIP_IMAGES, 0);
    }

    /**
     * Uploads up to {@link #MAX_PATCH_SECTIONS_PER_UPDATE} queued section changes as per-section
     * texture patches on all three levels; the remainder stays in {@link #pending} for the next
     * frame. A near patch is 4x4x4 texels (256 bytes), far and ultra one texel each, so even a
     * full batch is a fraction of one full-level rebuild.
     */
    private void applyPatches(DhColorCache cache) {
        patchBatch.clear();
        LongIterator it = pending.iterator();
        while (patchBatch.size() < MAX_PATCH_SECTIONS_PER_UPDATE && it.hasNext()) {
            patchBatch.add(it.nextLong());
            it.remove();
        }
        if (patchBuf == null) patchBuf = MemoryUtil.memAlloc(4 * 4 * 4 * 4);
        setUnpackState();
        patchLevel(near, cache);
        patchLevel(far, cache);
        patchLevel(ultra, cache);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0);
    }

    private void patchLevel(Level level, DhColorCache cache) {
        if (level.textureId == 0) return;
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, level.textureId);
        for (int i = 0; i < patchBatch.size(); ++i) {
            patchSection(level, cache, patchBatch.getLong(i));
        }
    }

    private void patchSection(Level level, DhColorCache cache, long sectionPos) {
        // Restore full limit/position: the previous patch shrank the limit for its upload, and
        // absolute puts check against the limit (a stale limit(4) from a far/ultra patch made
        // the next near patch throw and killed the whole override, 2026-08-07).
        patchBuf.clear();
        int blockX = SectionPos.x(sectionPos) << 4;
        int blockY = SectionPos.y(sectionPos) << 4;
        int blockZ = SectionPos.z(sectionPos) << 4;
        int texelsPerSection = Math.max(1, 16 / level.blocksPerTexel);
        int tx = (blockX - level.minX) / level.blocksPerTexel;
        int ty = (blockY - level.minY) / level.blocksPerTexel;
        int tz = (blockZ - level.minZ) / level.blocksPerTexel;
        if (blockX < level.minX || blockY < level.minY || blockZ < level.minZ
                || tx + texelsPerSection > level.size
                || ty + texelsPerSection > level.size
                || tz + texelsPerSection > level.size) {
            return;
        }

        DhColorCache.Entry entry = cache.getEntry(sectionPos); // null = section was erased
        if (level.blocksPerTexel < 16) {
            // near: the section's 4x4x4 mip. GL wants x fastest, then y rows, then z slices;
            // the mip is indexed y<<4 | z<<2 | x (see rebuildLevel).
            for (int pz = 0; pz < 4; ++pz) {
                for (int py = 0; py < 4; ++py) {
                    for (int px = 0; px < 4; ++px) {
                        int dst = ((pz * 4 + py) * 4 + px) * 4;
                        if (entry == null) {
                            patchBuf.putInt(dst, 0);
                        } else {
                            int mi = py << 4 | pz << 2 | px;
                            patchBuf.put(dst, entry.mip[mi * 4]);
                            patchBuf.put(dst + 1, entry.mip[mi * 4 + 1]);
                            patchBuf.put(dst + 2, entry.mip[mi * 4 + 2]);
                            patchBuf.put(dst + 3, (byte) (128 + ((entry.mip[mi * 4 + 3] & 0xFF) >> 1)));
                        }
                    }
                }
            }
            patchBuf.position(0).limit(4 * 4 * 4 * 4);
            GL12.glTexSubImage3D(GL12.GL_TEXTURE_3D, 0, tx, ty, tz, 4, 4, 4,
                    GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, patchBuf);
        } else if (level.blocksPerTexel == 16) {
            // far: one texel per section
            if (entry == null) {
                patchBuf.putInt(0, 0);
            } else {
                patchBuf.put(0, entry.farR);
                patchBuf.put(1, entry.farG);
                patchBuf.put(2, entry.farB);
                patchBuf.put(3, (byte) (128 + ((entry.farAbsorption & 0xFF) >> 1)));
            }
            patchBuf.position(0).limit(4);
            GL12.glTexSubImage3D(GL12.GL_TEXTURE_3D, 0, tx, ty, tz, 1, 1, 1,
                    GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, patchBuf);
        } else {
            // ultra: the texel is the componentwise max of its whole 4x4x4 section group, so
            // recompute it from the cache; the store that queued this patch may have raised OR
            // lowered this section, and a running max cannot be lowered in place.
            int sectionsPerTexel = level.blocksPerTexel / 16;
            int baseSx = (level.minX >> 4) + tx * sectionsPerTexel;
            int baseSy = (level.minY >> 4) + ty * sectionsPerTexel;
            int baseSz = (level.minZ >> 4) + tz * sectionsPerTexel;
            int r = 0, g = 0, b = 0, a = 0;
            for (int sy = 0; sy < sectionsPerTexel; ++sy) {
                for (int sz = 0; sz < sectionsPerTexel; ++sz) {
                    for (int sx = 0; sx < sectionsPerTexel; ++sx) {
                        DhColorCache.Entry e = cache.getEntry(
                                SectionPos.asLong(baseSx + sx, baseSy + sy, baseSz + sz));
                        if (e == null) continue;
                        r = Math.max(r, e.farR & 0xFF);
                        g = Math.max(g, e.farG & 0xFF);
                        b = Math.max(b, e.farB & 0xFF);
                        a = Math.max(a, 128 + ((e.farAbsorption & 0xFF) >> 1));
                    }
                }
            }
            patchBuf.put(0, (byte) r);
            patchBuf.put(1, (byte) g);
            patchBuf.put(2, (byte) b);
            patchBuf.put(3, (byte) a);
            patchBuf.position(0).limit(4);
            GL12.glTexSubImage3D(GL12.GL_TEXTURE_3D, 0, tx, ty, tz, 1, 1, 1,
                    GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, patchBuf);
        }
    }

    /** Frees GL objects and the staging buffer. Render thread. */
    public void free() {
        if (near.textureId != 0) GL11.glDeleteTextures(near.textureId);
        if (far.textureId != 0) GL11.glDeleteTextures(far.textureId);
        if (ultra.textureId != 0) GL11.glDeleteTextures(ultra.textureId);
        near.textureId = 0;
        far.textureId = 0;
        ultra.textureId = 0;
        if (staging != null) {
            MemoryUtil.memFree(staging);
            staging = null;
        }
        if (patchBuf != null) {
            MemoryUtil.memFree(patchBuf);
            patchBuf = null;
        }
        pending.clear();
        lastCache = null;
        lastStructureVersion = -1;
    }
}
