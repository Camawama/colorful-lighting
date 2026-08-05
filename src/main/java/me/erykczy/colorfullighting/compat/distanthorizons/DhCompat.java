package me.erykczy.colorfullighting.compat.distanthorizons;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.interfaces.override.rendering.IDhApiShaderProgram;
import com.seibel.distanthorizons.api.methods.events.DhApiEventRegister;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiAfterDhInitEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiEventParam;
import me.erykczy.colorfullighting.ColorfulLighting;
import me.erykczy.colorfullighting.common.ColoredLightEngine;
import me.erykczy.colorfullighting.common.ColoredLightSection;
import net.minecraft.client.Minecraft;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.Level;
import net.minecraftforge.fml.ModList;

import javax.annotation.Nullable;
import java.lang.ref.WeakReference;
import java.nio.file.Path;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Distant Horizons compatibility: colored lighting on LODs.
 *
 * <p>Three pieces, all behind the {@code dhLodColor} config (default off) and touching nothing
 * unless DH is installed:
 * <ul>
 * <li>{@link DhColorCache}: remembers each visited section's net light colour (downsampled),
 *     persisted per world+dimension under {@code <gamedir>/colorful_lighting/dh_color_cache/}.</li>
 * <li>{@link DhColorVolume}: two moving 3D textures around the camera filled from the cache.</li>
 * <li>{@link DhTerrainColorShaderProgram}: a DH API shader override that samples the volumes and
 *     tints LOD block light. Bound/unbound at runtime via {@code /cl dh on|off}.</li>
 * </ul>
 *
 * <p>Capture runs on a single daemon worker thread fed from the client thread's dirty-section drain
 * ({@link ColoredLightEngine#onLightUpdate()}), so the render path never blocks on it.
 */
public final class DhCompat {
    public static final String MOD_ID = "distanthorizons";
    /** DhApi major version this was built and tested against (DH 3.1.2-b). */
    public static final int SUPPORTED_API_MAJOR = 7;
    private static final long SAVE_INTERVAL_MS = 30_000L;

    private static Boolean loaded;
    private static volatile boolean apiUsable;
    private static volatile boolean shaderContractOk = true;
    private static volatile boolean overrideBound;
    private static volatile int debugMode = 0;
    private static DhTerrainColorShaderProgram overrideProgram;
    private static DhColorVolume volume; // render thread only

    private static final Map<Level, DhColorCache> CACHES = new WeakHashMap<>();
    /** Cache of the level currently being played, republished for the render thread. */
    private static volatile DhColorCache activeCache;
    /** Player section, for cache pruning; written on the client thread, read on the worker. */
    private static volatile long playerSectionPos;
    private static long lastSaveMs;

    private static final LinkedBlockingQueue<Runnable> WORKER_QUEUE = new LinkedBlockingQueue<>();
    private static Thread workerThread;

    private DhCompat() {}

    public static boolean isLoaded() {
        if (loaded == null) loaded = ModList.get().isLoaded(MOD_ID);
        return loaded;
    }

    /** Whether the shader override is currently bound into DH. Gates all per-frame and capture cost. */
    public static boolean isOverrideEnabled() { return overrideBound; }

    public static int getDebugMode() { return debugMode; }
    public static void setDebugMode(int mode) { debugMode = mode; }

    /** Called once from mod loading-complete when DH is present. */
    public static void init() {
        DhApiEventRegister.on(DhApiAfterDhInitEvent.class, new DhApiAfterDhInitEvent() {
            @Override
            public void afterDistantHorizonsInit(DhApiEventParam<Void> input) {
                onDhInitialized();
            }
        });
    }

    private static void onDhInitialized() {
        int major = DhApi.getApiMajorVersion();
        apiUsable = major >= SUPPORTED_API_MAJOR;
        ColorfulLighting.LOGGER.info("Distant Horizons detected (DhApi major version {}, DH {})",
                major, DhApi.getModVersion());
        if (!apiUsable) {
            ColorfulLighting.LOGGER.warn(
                    "Distant Horizons API {} is older than the supported version {}; colored LOD lighting stays off",
                    major, SUPPORTED_API_MAJOR);
            return;
        }
        if (major != SUPPORTED_API_MAJOR) {
            ColorfulLighting.LOGGER.warn(
                    "Distant Horizons API {} is newer than the tested version {}; colored LOD lighting may not work",
                    major, SUPPORTED_API_MAJOR);
        }
        checkShaderContract();
        if (me.erykczy.colorfullighting.common.ColorfulLightingConfig.dhLodColor()) {
            setOverrideEnabled(true);
        }
    }

    /**
     * Our override replicates the exact vertex contract of DH 3.1.2's terrain shader (buffer-local
     * uvec4 positions plus a uModelOffset uniform). A DH build with a different standard.vert would
     * misrender through our program, so read DH's own shader off the classpath and compare the parts
     * we depend on. Mismatch logs loudly and blocks enabling instead of drawing garbage.
     */
    private static void checkShaderContract() {
        try (java.io.InputStream in = DhApi.class.getResourceAsStream(
                "/assets/distanthorizons/shaders/shared/gl/standard.vert")) {
            if (in == null) {
                shaderContractOk = false;
                ColorfulLighting.LOGGER.warn(
                        "[DH] this Distant Horizons build has no shared/gl/standard.vert; its render pipeline differs from the supported DH 3.1.2 and colored LOD lighting stays off");
                return;
            }
            String vert = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            boolean hasModelOffset = vert.contains("uModelOffset");
            boolean hasUvecPosition = vert.contains("uvec4 vPosition");
            shaderContractOk = hasModelOffset && hasUvecPosition;
            ColorfulLighting.LOGGER.info(
                    "[DH] terrain shader contract check: standard.vert {} bytes, uModelOffset={}, uvec4 vPosition={}",
                    vert.length(), hasModelOffset, hasUvecPosition);
            if (!shaderContractOk) {
                ColorfulLighting.LOGGER.warn(
                        "[DH] this Distant Horizons version uses a different terrain shader contract than the supported DH 3.1.2; colored LOD lighting stays off to avoid misrendering LODs");
            }
        } catch (Exception e) {
            shaderContractOk = false;
            ColorfulLighting.LOGGER.warn("[DH] failed to check DH's terrain shader contract", e);
        }
    }

    /**
     * Binds or unbinds the shader override at runtime. Safe to call from the client thread; DH reads
     * the override injector at the start of each render pass.
     *
     * @return a user-facing status message
     */
    public static String setOverrideEnabled(boolean enable) {
        if (!isLoaded()) return "Distant Horizons is not installed";
        if (enable && !apiUsable) {
            return "Distant Horizons' API version is unsupported (needs DhApi " + SUPPORTED_API_MAJOR + "+, e.g. DH 3.1.2)";
        }
        if (enable && !shaderContractOk) {
            return "This Distant Horizons version's terrain shader differs from the supported one (DH 3.1.2); colored LOD lighting would misrender, staying off";
        }
        if (enable == overrideBound) {
            return enable ? "Colored LOD lighting already on" : "Colored LOD lighting already off";
        }
        try {
            if (enable) {
                if (overrideProgram == null) overrideProgram = new DhTerrainColorShaderProgram();
                DhApi.overrides.bind(IDhApiShaderProgram.class, overrideProgram);
                overrideBound = true;
                ColorfulLighting.LOGGER.info("[DH] bound colored LOD terrain shader override");
                return "Colored LOD lighting enabled";
            } else {
                DhApi.overrides.unbind(IDhApiShaderProgram.class, overrideProgram);
                overrideBound = false;
                ColorfulLighting.LOGGER.info("[DH] unbound colored LOD terrain shader override");
                return "Colored LOD lighting disabled";
            }
        } catch (Throwable t) {
            ColorfulLighting.LOGGER.error("[DH] failed to {} the shader override", enable ? "bind" : "unbind", t);
            return "Failed, see the log";
        }
    }

    // ================ capture ================

    /**
     * Client thread, from the engine's dirty-section drain. Snapshots happen on the worker; a section
     * that got unloaded in between is simply skipped (its previously remembered colour survives).
     */
    public static void onSectionsDirty(ColoredLightEngine engine, long[] sectionPositions) {
        if (!overrideBound) return;
        Level level = engine.getLevel().getLevel();
        if (level == null) return;
        DhColorCache cache = getOrCreateCache(level);
        if (cache == null) return;
        // Client thread: filter down to fully-propagated inner-area sections while the view area
        // is still current. Unsafe sections keep whatever was remembered before.
        int kept = 0;
        long[] safe = new long[sectionPositions.length];
        for (long pos : sectionPositions) {
            if (engine.dhIsSectionCaptureSafe(pos)) safe[kept++] = pos;
        }
        if (kept == 0) return;
        final long[] positions = java.util.Arrays.copyOf(safe, kept);
        WeakReference<ColoredLightEngine> engineRef = new WeakReference<>(engine);
        submit(() -> {
            ColoredLightEngine liveEngine = engineRef.get();
            if (liveEngine == null) return;
            for (long pos : positions) {
                ColoredLightSection light = liveEngine.dhGetLightSection(pos);
                if (light == null) continue; // left the view area; keep what we remembered
                DhColorCache.Entry entry = DhColorCache.buildEntry(light, liveEngine.dhGetDarknessSection(pos));
                cache.store(pos, entry);
            }
            long player = playerSectionPos;
            cache.pruneIfNeeded(SectionPos.x(player), SectionPos.z(player));
        });
    }

    // ================ per-level cache ================

    /** Render thread accessor; the client tick keeps it pointing at the current level's cache. */
    @Nullable
    public static DhColorCache getActiveCache() { return activeCache; }

    @Nullable
    private static DhColorCache getOrCreateCache(Level level) {
        synchronized (CACHES) {
            DhColorCache cache = CACHES.get(level);
            if (cache == null) {
                Path file = cacheFileFor(level);
                if (file == null) return null;
                cache = new DhColorCache(file);
                CACHES.put(level, cache);
                DhColorCache created = cache;
                submit(created::load);
            }
            return cache;
        }
    }

    /**
     * One file per world (or server) and dimension. Keyed by save folder / server address, so the
     * remembered colour follows the world across sessions.
     */
    @Nullable
    private static Path cacheFileFor(Level level) {
        Minecraft mc = Minecraft.getInstance();
        String worldKey;
        if (mc.getSingleplayerServer() != null) {
            worldKey = "sp_" + sanitize(mc.getSingleplayerServer().getWorldData().getLevelName());
        } else if (mc.getCurrentServer() != null) {
            worldKey = "mp_" + sanitize(mc.getCurrentServer().ip);
        } else {
            return null;
        }
        String dimension = sanitize(level.dimension().location().toString());
        return mc.gameDirectory.toPath()
                .resolve("colorful_lighting").resolve("dh_color_cache")
                .resolve(worldKey).resolve(dimension + ".bin.gz");
    }

    private static String sanitize(String raw) {
        return raw.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    // ================ lifecycle ================

    /** Set from the render thread when the shader override breaks; unbound on the next client tick. */
    private static volatile boolean emergencyUnbindRequested;

    public static void requestEmergencyUnbind() {
        emergencyUnbindRequested = true;
    }

    /** Client tick: keeps the active cache current and autosaves changed caches every 30s. */
    public static void clientTick() {
        if (!isLoaded()) return;
        if (emergencyUnbindRequested) {
            emergencyUnbindRequested = false;
            setOverrideEnabled(false);
            ColorfulLighting.LOGGER.warn("[DH] shader override unbound after a failure; LODs are back to DH's own rendering");
        }

        // Follow the main engine's on/off switch. DH always uses a bound override (it never asks
        // overrideThisFrame on it), so '/cl off' must actually unbind or LODs would stay colored;
        // '/cl on' rebinds automatically when the config wants DH colors.
        boolean want = me.erykczy.colorfullighting.common.ColorfulLightingConfig.dhLodColor()
                && ColoredLightEngine.isEnabled() && apiUsable && shaderContractOk;
        if (want != overrideBound) {
            setOverrideEnabled(want);
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            activeCache = null;
            return;
        }
        playerSectionPos = SectionPos.asLong(
                mc.player.getBlockX() >> 4, mc.player.getBlockY() >> 4, mc.player.getBlockZ() >> 4);
        activeCache = overrideBound ? getOrCreateCache(mc.level) : null;

        long now = System.currentTimeMillis();
        if (now - lastSaveMs > SAVE_INTERVAL_MS) {
            lastSaveMs = now;
            saveAll(false);
        }
    }

    /** Level unload: persist what we learned. The cache object itself stays until the Level is GCed. */
    public static void onLevelUnload(Level level) {
        if (!isLoaded()) return;
        DhColorCache cache;
        synchronized (CACHES) {
            cache = CACHES.get(level);
        }
        if (cache != null) submit(cache::save);
        if (activeCache == cache) activeCache = null;
    }

    private static void saveAll(boolean force) {
        synchronized (CACHES) {
            for (DhColorCache cache : CACHES.values()) {
                if (force || cache.needsSave()) submit(cache::save);
            }
        }
    }

    // ================ render-thread volume ================

    /** Render thread only (called from the shader override's fillUniformData). */
    public static DhColorVolume getOrCreateVolume() {
        if (volume == null) volume = new DhColorVolume();
        return volume;
    }

    // ================ worker ================

    private static synchronized void submit(Runnable task) {
        if (workerThread == null) {
            workerThread = new Thread(DhCompat::workerLoop, "CL-DhColorCache");
            workerThread.setDaemon(true);
            workerThread.setPriority(Thread.MIN_PRIORITY);
            workerThread.start();
        }
        WORKER_QUEUE.add(task);
    }

    private static void workerLoop() {
        while (true) {
            try {
                WORKER_QUEUE.take().run();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable t) {
                ColorfulLighting.LOGGER.error("[DH color cache] worker task failed", t);
            }
        }
    }

    // ================ command support ================

    public static String describeStatus() {
        if (!isLoaded()) return "Distant Horizons: not installed";
        StringBuilder sb = new StringBuilder();
        sb.append("Colored LOD lighting: ").append(overrideBound ? "ON" : "OFF");
        try {
            sb.append(" (DH ").append(DhApi.getModVersion()).append(", DhApi ").append(DhApi.getApiMajorVersion()).append(")");
        } catch (Throwable ignored) {
        }
        if (!apiUsable) sb.append(" (DH API unsupported)");
        if (!shaderContractOk) sb.append(" (DH terrain shader contract mismatch)");
        DhColorCache cache = activeCache;
        if (cache != null) {
            sb.append("\nRemembered sections in this dimension: ").append(cache.getSectionCount());
            sb.append("\nCache file: ").append(cache.getFile().getFileName());
        }
        if (debugMode != 0) sb.append("\nDebug view: on (red = beyond color memory range, blue = nothing remembered)");
        return sb.toString();
    }
}
