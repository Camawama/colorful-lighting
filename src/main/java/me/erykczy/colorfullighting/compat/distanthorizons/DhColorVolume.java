package me.erykczy.colorfullighting.compat.distanthorizons;

import me.erykczy.colorfullighting.ColorfulLighting;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.Map;

/**
 * Two moving 3D textures holding the remembered light colour around the camera, sampled by the DH
 * terrain shader override. RGBA8: RGB is the cached net colour, A is a presence mask (0 = the cache
 * knows nothing there, the shader falls back to plain vanilla LOD lighting).
 *
 * <p>Two levels because one dense volume cannot span DH render distances: a near volume at 4
 * blocks/texel covering the first LOD ring in detail, and a far volume at 16 blocks/texel (one texel
 * per section) reaching {@link #FAR_RADIUS_BLOCKS} blocks. The July 2026 attempt proved a single
 * 192-block-radius volume barely cleared the vanilla render distance; these reach 384 and 1536.
 *
 * <p>Render thread only. Rebuilds are throttled and only happen when the cache version moves or the
 * camera crosses a section boundary; both windows are aligned to the 16-block section grid so cached
 * sections never straddle texels.
 */
public final class DhColorVolume {
    public static final int NEAR_SIZE = 192;             // texels per axis
    public static final int NEAR_BLOCKS_PER_TEXEL = 4;   // radius 384 blocks, ~28MB VRAM
    public static final int FAR_SIZE = 192;
    public static final int FAR_BLOCKS_PER_TEXEL = 16;   // radius 1536 blocks, ~28MB VRAM
    public static final int FAR_RADIUS_BLOCKS = FAR_SIZE * FAR_BLOCKS_PER_TEXEL / 2;
    private static final long MIN_REBUILD_INTERVAL_NANOS = 250_000_000L; // 250ms

    private final Level near = new Level(NEAR_SIZE, NEAR_BLOCKS_PER_TEXEL);
    private final Level far = new Level(FAR_SIZE, FAR_BLOCKS_PER_TEXEL);
    private DhColorCache lastCache;
    private int lastCacheVersion = -1;
    private long lastRebuildNanos;
    /** Reused CPU staging buffer, sized for the bigger level. */
    private ByteBuffer staging;

    public int nearTextureId() { return near.textureId; }
    public int farTextureId() { return far.textureId; }
    public int nearMinX() { return near.minX; }
    public int nearMinY() { return near.minY; }
    public int nearMinZ() { return near.minZ; }
    public int farMinX() { return far.minX; }
    public int farMinY() { return far.minY; }
    public int farMinZ() { return far.minZ; }
    public float nearInvSizeBlocks() { return 1.0f / (NEAR_SIZE * NEAR_BLOCKS_PER_TEXEL); }
    public float farInvSizeBlocks() { return 1.0f / (FAR_SIZE * FAR_BLOCKS_PER_TEXEL); }

    private static final class Level {
        final int size;
        final int blocksPerTexel;
        int textureId;
        int minX, minY, minZ; // block coords of the window's min corner, section-grid aligned

        Level(int size, int blocksPerTexel) {
            this.size = size;
            this.blocksPerTexel = blocksPerTexel;
        }

        int radiusBlocks() { return size * blocksPerTexel / 2; }
    }

    /**
     * Refreshes both levels if needed. Cheap no-op unless the cache changed or the camera moved a
     * section; a full rebuild takes a few ms and is throttled to {@link #MIN_REBUILD_INTERVAL_NANOS}.
     */
    public void update(DhColorCache cache, double camX, double camY, double camZ) {
        boolean cacheChanged = cache != lastCache
                || (cache != null && cache.getVersion() != lastCacheVersion);
        int wantNearMinX = windowMin(camX, near);
        int wantNearMinY = windowMin(camY, near);
        int wantNearMinZ = windowMin(camZ, near);
        int wantFarMinX = windowMin(camX, far);
        int wantFarMinY = windowMin(camY, far);
        int wantFarMinZ = windowMin(camZ, far);
        boolean moved = wantNearMinX != near.minX || wantNearMinY != near.minY || wantNearMinZ != near.minZ
                || wantFarMinX != far.minX || wantFarMinY != far.minY || wantFarMinZ != far.minZ;
        if (!cacheChanged && !moved && near.textureId != 0) return;

        long now = System.nanoTime();
        if (near.textureId != 0 && now - lastRebuildNanos < MIN_REBUILD_INTERVAL_NANOS) return;
        lastRebuildNanos = now;
        lastCache = cache;
        lastCacheVersion = cache == null ? -1 : cache.getVersion();

        near.minX = wantNearMinX;
        near.minY = wantNearMinY;
        near.minZ = wantNearMinZ;
        far.minX = wantFarMinX;
        far.minY = wantFarMinY;
        far.minZ = wantFarMinZ;

        long start = System.nanoTime();
        int inNear = rebuildLevel(near, cache, true);
        int inFar = rebuildLevel(far, cache, false);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        if (elapsedMs > 30) {
            ColorfulLighting.LOGGER.info(
                    "[DH volume] rebuilt in {} ms: {} cached sections, {} in near window, {} in far window",
                    elapsedMs, cache == null ? 0 : cache.getSectionCount(), inNear, inFar);
        }
    }

    /** Window min corner: camera-centred, snapped to the section grid so texel edges match sections. */
    private static int windowMin(double cam, Level level) {
        return Math.floorDiv(Mth.floor(cam) - level.radiusBlocks(), 16) * 16;
    }

    /** @return number of cached sections that landed inside the window */
    private int rebuildLevel(Level level, DhColorCache cache, boolean useMip) {
        int sizeBytes = level.size * level.size * level.size * 4;
        if (staging == null || staging.capacity() < sizeBytes) {
            if (staging != null) MemoryUtil.memFree(staging);
            staging = MemoryUtil.memAlloc(sizeBytes);
        }
        MemoryUtil.memSet(MemoryUtil.memAddress(staging), 0, sizeBytes);

        int written = 0;
        if (cache != null) {
            int texelsPerSection = 16 / level.blocksPerTexel; // 4 for near, 1 for far
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
                if (useMip) {
                    for (int mi = 0; mi < DhColorCache.MIP_TEXELS; ++mi) {
                        int mx = mi & 3, mz = (mi >>> 2) & 3, my = (mi >>> 4) & 3;
                        int index = (((tz + mz) * level.size + (ty + my)) * level.size + (tx + mx)) * 4;
                        staging.put(index, entry.mip[mi * 3]);
                        staging.put(index + 1, entry.mip[mi * 3 + 1]);
                        staging.put(index + 2, entry.mip[mi * 3 + 2]);
                        staging.put(index + 3, (byte) 0xFF);
                    }
                } else {
                    int index = ((tz * level.size + ty) * level.size + tx) * 4;
                    staging.put(index, entry.farR);
                    staging.put(index + 1, entry.farG);
                    staging.put(index + 2, entry.farB);
                    staging.put(index + 3, (byte) 0xFF);
                }
            }
        }

        staging.position(0).limit(sizeBytes);
        // Minecraft leaves nonzero unpack state behind (row length, skips); with our tightly
        // packed buffer that would shear the whole volume into garbage.
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
        GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);
        GL11.glPixelStorei(GL12.GL_UNPACK_IMAGE_HEIGHT, 0);
        GL11.glPixelStorei(GL12.GL_UNPACK_SKIP_IMAGES, 0);
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

    /** Frees GL objects and the staging buffer. Render thread. */
    public void free() {
        if (near.textureId != 0) GL11.glDeleteTextures(near.textureId);
        if (far.textureId != 0) GL11.glDeleteTextures(far.textureId);
        near.textureId = 0;
        far.textureId = 0;
        if (staging != null) {
            MemoryUtil.memFree(staging);
            staging = null;
        }
        lastCache = null;
        lastCacheVersion = -1;
    }
}
