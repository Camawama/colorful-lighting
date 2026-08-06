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
    /**
     * v2: mip clusters hold the dominant (most colorful) block instead of an average, and captures
     * are inner-area-only. Old files may carry averaged or clipped data, so a version bump discards
     * them and everything re-captures clean.
     */
    private static final int FORMAT_VERSION = 2;

    /** Immutable snapshot of one section's remembered colour. */
    public static final class Entry {
        /** RGB888 per 4x4x4-block cluster, indexed {@code (y>>2)<<4 | (z>>2)<<2 | (x>>2)}, each times 3. */
        public final byte[] mip;
        /**
         * The section's colour for the coarse far volume (one texel per section). The HUE is the
         * most COLORFUL cluster's (highest chroma), not the brightest: brightest picked a beacon's
         * white core over the blue light its stained glass casts, turning the whole area white at
         * distance; white light is "vanilla" anyway, so a real colour must always win over it
         * (falls back to the brightest cluster when the whole section is white/gray light).
         *
         * <p>The BRIGHTNESS is the section's average level (mean of the mip cells' peaks, unlit
         * cells included), NOT the dominant cluster's own. The shader uses the sampled brightness
         * as a floor under DH's baked block light, and lifting a whole 16-block section (plus its
         * filtering neighbourhood) to the brightest source's level made every distant LOD with a
         * light in it glow at full intensity; the average gives the section's aggregate glow.
         */
        public final byte farR, farG, farB;

        Entry(byte[] mip, int farR, int farG, int farB) {
            this.mip = mip;
            this.farR = (byte) farR;
            this.farG = (byte) farG;
            this.farB = (byte) farB;
        }

        static Entry fromMip(byte[] mip) {
            int best = 0;
            int bestChroma = -1;
            int bestPeak = -1;
            int levelSum = 0;
            for (int i = 0; i < MIP_TEXELS; ++i) {
                int r = mip[i * 3] & 0xFF, g = mip[i * 3 + 1] & 0xFF, b = mip[i * 3 + 2] & 0xFF;
                int peak = Math.max(r, Math.max(g, b));
                int chroma = peak - Math.min(r, Math.min(g, b));
                levelSum += peak;
                if (chroma > bestChroma || (chroma == bestChroma && peak > bestPeak)) {
                    bestChroma = chroma;
                    bestPeak = peak;
                    best = i;
                }
            }
            int domR = mip[best * 3] & 0xFF, domG = mip[best * 3 + 1] & 0xFF, domB = mip[best * 3 + 2] & 0xFF;
            int domPeak = Math.max(domR, Math.max(domG, domB));
            int avgLevel = levelSum / MIP_TEXELS;
            if (domPeak == 0) return new Entry(mip, 0, 0, 0);
            return new Entry(mip,
                    Math.round(domR * (float) avgLevel / domPeak),
                    Math.round(domG * (float) avgLevel / domPeak),
                    Math.round(domB * (float) avgLevel / domPeak));
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
        // Each 4x4x4 cluster keeps its dominant (most colorful, then brightest) block rather than an
        // average. Only the HUE of a cluster is ever used (brightness comes from the LOD's own baked
        // block light), and averaging over mostly-unlit blocks floored faint fringe clusters to
        // zero, which cut light fields off jaggedly at their edges on LODs.
        byte[] mip = null;
        int[] bestChroma = null;
        int[] bestPeak = null;
        for (int idx = 0; idx < 4096; ++idx) {
            int l = light.getPacked(idx);
            if (l == 0) continue;
            int d = darkness == null ? 0 : darkness.getPacked(idx);
            int r = Math.max(0, ((l >>> 8) & 0xF) - ((d >>> 8) & 0xF));
            int g = Math.max(0, ((l >>> 4) & 0xF) - ((d >>> 4) & 0xF));
            int b = Math.max(0, (l & 0xF) - (d & 0xF));
            if ((r | g | b) == 0) continue;
            if (mip == null) {
                mip = new byte[MIP_BYTES];
                bestChroma = new int[MIP_TEXELS];
                bestPeak = new int[MIP_TEXELS];
                java.util.Arrays.fill(bestChroma, -1);
            }
            int peak = Math.max(r, Math.max(g, b));
            int chroma = peak - Math.min(r, Math.min(g, b));
            // getColorIndex is y<<8 | z<<4 | x
            int x = idx & 15, z = (idx >>> 4) & 15, y = (idx >>> 8) & 15;
            int mi = (y >> 2) << 4 | (z >> 2) << 2 | (x >> 2);
            if (chroma > bestChroma[mi] || (chroma == bestChroma[mi] && peak > bestPeak[mi])) {
                bestChroma[mi] = chroma;
                bestPeak[mi] = peak;
                mip[mi * 3] = (byte) (r * 17); // nibble 0..15 -> 0..255
                mip[mi * 3 + 1] = (byte) (g * 17);
                mip[mi * 3 + 2] = (byte) (b * 17);
            }
        }
        if (mip == null) return null;
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
