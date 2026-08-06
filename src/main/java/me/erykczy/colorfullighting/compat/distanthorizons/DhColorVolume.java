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
    public static final int ULTRA_SIZE = 192;
    public static final int ULTRA_BLOCKS_PER_TEXEL = 64; // radius 6144 blocks, ~28MB VRAM
    private static final long MIN_REBUILD_INTERVAL_NANOS = 250_000_000L; // 250ms

    private final Level near = new Level(NEAR_SIZE, NEAR_BLOCKS_PER_TEXEL);
    private final Level far = new Level(FAR_SIZE, FAR_BLOCKS_PER_TEXEL);
    private final Level ultra = new Level(ULTRA_SIZE, ULTRA_BLOCKS_PER_TEXEL);
    private DhColorCache lastCache;
    private int lastCacheVersion = -1;
    private long lastRebuildNanos;
    /** Reused CPU staging buffer, sized for the bigger level. */
    private ByteBuffer staging;

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
        int wantNearMinY = windowMinY(camY, near);
        int wantNearMinZ = windowMin(camZ, near);
        int wantFarMinX = windowMin(camX, far);
        int wantFarMinY = windowMinY(camY, far);
        int wantFarMinZ = windowMin(camZ, far);
        int wantUltraMinX = windowMin(camX, ultra);
        int wantUltraMinY = windowMinY(camY, ultra);
        int wantUltraMinZ = windowMin(camZ, ultra);
        boolean moved = wantNearMinX != near.minX || wantNearMinY != near.minY || wantNearMinZ != near.minZ
                || wantFarMinX != far.minX || wantFarMinY != far.minY || wantFarMinZ != far.minZ
                || wantUltraMinX != ultra.minX || wantUltraMinY != ultra.minY || wantUltraMinZ != ultra.minZ;
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
        ultra.minX = wantUltraMinX;
        ultra.minY = wantUltraMinY;
        ultra.minZ = wantUltraMinZ;

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

    /** Window min corner: camera-centred, snapped to the section grid so texel edges match sections. */
    private static int windowMin(double cam, Level level) {
        return Math.floorDiv(Mth.floor(cam) - level.radiusBlocks(), 16) * 16;
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
        return Math.floorDiv(min, 16) * 16;
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

    private static void putMax(ByteBuffer buffer, int index, byte value) {
        if ((value & 0xFF) > (buffer.get(index) & 0xFF)) buffer.put(index, value);
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
        lastCache = null;
        lastCacheVersion = -1;
    }
}
