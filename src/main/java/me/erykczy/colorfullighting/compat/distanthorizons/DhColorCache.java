package me.erykczy.colorfullighting.compat.distanthorizons;

import me.erykczy.colorfullighting.ColorfulLighting;
import me.erykczy.colorfullighting.common.ColoredLightSection;
import net.minecraft.core.SectionPos;

import javax.annotation.Nullable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Remembered net light colour (light minus darkness) per section, kept after the section leaves the
 * engine's view area so far LODs can replay it. The engine only stores colour for loaded chunks;
 * Distant Horizons renders far beyond them, so this cache is the only colour source out there. Data
 * is possibly stale by design: whatever the chunk looked like when it was last loaded.
 *
 * <p>Stored downsampled, not at block resolution: the render volume samples at 4 blocks per texel at
 * best ({@link DhColorVolume}), so each section keeps a 4x4x4 RGB mip (192 bytes) plus a whole-section
 * average. That keeps a long play session's cache in the low tens of MB instead of GBs.
 *
 * <p>Thread model: entries are written by the {@link DhCompat} worker thread and read by the render
 * thread (volume rebuild) via the concurrent map. Load/save also run on the worker.
 */
public final class DhColorCache {
    /** 4x4x4 texels per section, 3 bytes each. */
    public static final int MIP_TEXELS = 64;
    public static final int MIP_BYTES = MIP_TEXELS * 3;
    /** ~26MB of RAM/disk at worst; beyond this the farthest sections from the player are dropped. */
    public static final int MAX_SECTIONS = 131_072;
    private static final int MAGIC = 0x434C4432; // "CLD2"
    private static final int FORMAT_VERSION = 1;

    /** Immutable snapshot of one section's remembered colour. */
    public static final class Entry {
        /** RGB888 per 4x4x4-block cluster, indexed {@code (y>>2)<<4 | (z>>2)<<2 | (x>>2)}, each times 3. */
        public final byte[] mip;
        /** Whole-section average, for the coarse far volume (one texel per section). */
        public final byte avgR, avgG, avgB;

        Entry(byte[] mip, int avgR, int avgG, int avgB) {
            this.mip = mip;
            this.avgR = (byte) avgR;
            this.avgG = (byte) avgG;
            this.avgB = (byte) avgB;
        }

        static Entry fromMip(byte[] mip) {
            int r = 0, g = 0, b = 0;
            for (int i = 0; i < MIP_TEXELS; ++i) {
                r += mip[i * 3] & 0xFF;
                g += mip[i * 3 + 1] & 0xFF;
                b += mip[i * 3 + 2] & 0xFF;
            }
            return new Entry(mip, r / MIP_TEXELS, g / MIP_TEXELS, b / MIP_TEXELS);
        }
    }

    private final ConcurrentHashMap<Long, Entry> sections = new ConcurrentHashMap<>();
    /** Bumped on every content change; the render volume rebuilds when it moves. */
    private final AtomicInteger version = new AtomicInteger();
    private final Path file;
    private volatile boolean dirtySinceSave = false;

    public DhColorCache(Path file) {
        this.file = file;
    }

    public int getVersion() { return version.get(); }
    public int getSectionCount() { return sections.size(); }
    public Path getFile() { return file; }

    /** Iteration for the volume rebuild (render thread); weakly consistent, which is fine here. */
    public Iterable<Map.Entry<Long, Entry>> entries() { return sections.entrySet(); }

    /**
     * Builds the downsampled entry for one section from the engine's live storage, or null when the
     * section holds no colour at all. Reading the sections off-thread races the propagator the same
     * way render-thread sampling does: worst case one stale block, and the section goes dirty (and is
     * recaptured) on the next change anyway.
     */
    @Nullable
    public static Entry buildEntry(@Nullable ColoredLightSection light, @Nullable ColoredLightSection darkness) {
        if (light == null) return null;
        int[] sums = null;
        for (int idx = 0; idx < 4096; ++idx) {
            int l = light.getPacked(idx);
            if (l == 0) continue;
            int d = darkness == null ? 0 : darkness.getPacked(idx);
            int r = Math.max(0, ((l >>> 8) & 0xF) - ((d >>> 8) & 0xF));
            int g = Math.max(0, ((l >>> 4) & 0xF) - ((d >>> 4) & 0xF));
            int b = Math.max(0, (l & 0xF) - (d & 0xF));
            if ((r | g | b) == 0) continue;
            if (sums == null) sums = new int[MIP_TEXELS * 3];
            // getColorIndex is y<<8 | z<<4 | x
            int x = idx & 15, z = (idx >>> 4) & 15, y = (idx >>> 8) & 15;
            int mi = ((y >> 2) << 4 | (z >> 2) << 2 | (x >> 2)) * 3;
            sums[mi] += r * 17;     // nibble 0..15 -> 0..255
            sums[mi + 1] += g * 17;
            sums[mi + 2] += b * 17;
        }
        if (sums == null) return null;
        byte[] mip = new byte[MIP_BYTES];
        for (int i = 0; i < MIP_BYTES; ++i) {
            mip[i] = (byte) (sums[i] / 64); // average over the 4x4x4 cluster, empty blocks included
        }
        return Entry.fromMip(mip);
    }

    /** Stores or (when {@code entry} is null) erases the remembered colour for a section. */
    public void store(long sectionPos, @Nullable Entry entry) {
        boolean changed;
        if (entry == null) {
            changed = sections.remove(sectionPos) != null;
        } else {
            sections.put(sectionPos, entry);
            changed = true;
        }
        if (changed) {
            version.incrementAndGet();
            dirtySinceSave = true;
        }
    }

    /** Drops the farthest sections from the given player section once over budget. Worker thread. */
    public void pruneIfNeeded(int centerSectionX, int centerSectionZ) {
        if (sections.size() <= MAX_SECTIONS) return;
        int toDrop = sections.size() - (MAX_SECTIONS - MAX_SECTIONS / 16);
        List<long[]> byDistance = new ArrayList<>(sections.size()); // [distSq, sectionPos]
        for (Long pos : sections.keySet()) {
            long dx = SectionPos.x(pos) - centerSectionX;
            long dz = SectionPos.z(pos) - centerSectionZ;
            byDistance.add(new long[]{dx * dx + dz * dz, pos});
        }
        byDistance.sort((a, b) -> Long.compare(b[0], a[0]));
        for (int i = 0; i < toDrop && i < byDistance.size(); ++i) {
            sections.remove(byDistance.get(i)[1]);
        }
        version.incrementAndGet();
        dirtySinceSave = true;
        ColorfulLighting.LOGGER.info("[DH color cache] pruned {} far sections ({} kept)", toDrop, sections.size());
    }

    public boolean needsSave() { return dirtySinceSave; }

    /** Worker thread. Failures only lose the remembered colour, so they log and move on. */
    public void load() {
        if (!Files.isRegularFile(file)) return;
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(newInputStream()))) {
            if (in.readInt() != MAGIC) throw new IOException("bad magic");
            int formatVersion = in.readInt();
            if (formatVersion != FORMAT_VERSION) throw new IOException("unknown format version " + formatVersion);
            int count = in.readInt();
            for (int i = 0; i < count; ++i) {
                long pos = in.readLong();
                byte[] mip = new byte[MIP_BYTES];
                in.readFully(mip);
                sections.put(pos, Entry.fromMip(mip));
            }
            version.incrementAndGet();
            ColorfulLighting.LOGGER.info("[DH color cache] loaded {} remembered sections from {}", count, file.getFileName());
        } catch (Exception e) {
            ColorfulLighting.LOGGER.warn("[DH color cache] failed to load {}: {}", file, e.toString());
        }
    }

    /** Worker thread. Writes to a temp file first so a crash mid-save keeps the previous cache. */
    public void save() {
        if (!dirtySinceSave) return;
        dirtySinceSave = false;
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(tmp)))) {
                out.writeInt(MAGIC);
                out.writeInt(FORMAT_VERSION);
                // Snapshot the entries first: the map may grow while we write, and the count must match.
                List<Map.Entry<Long, Entry>> snapshot = new ArrayList<>(sections.entrySet());
                out.writeInt(snapshot.size());
                for (Map.Entry<Long, Entry> entry : snapshot) {
                    out.writeLong(entry.getKey());
                    out.write(entry.getValue().mip);
                }
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            dirtySinceSave = true;
            ColorfulLighting.LOGGER.warn("[DH color cache] failed to save {}: {}", file, e.toString());
        }
    }

    private InputStream newInputStream() throws IOException {
        return Files.newInputStream(file);
    }
}
