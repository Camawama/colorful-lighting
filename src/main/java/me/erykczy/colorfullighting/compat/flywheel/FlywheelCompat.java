package me.erykczy.colorfullighting.compat.flywheel;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.engine_room.flywheel.backend.gl.GlCompat;
import dev.engine_room.flywheel.backend.glsl.GlslVersion;
import me.erykczy.colorfullighting.ColorfulLighting;
import net.minecraft.world.level.LevelAccessor;

import java.util.ArrayList;
import java.util.List;

public class FlywheelCompat {
    private static FlywheelCompat instance;
    /**
     * True when flywheel shaders compile below GLSL 430 and the colored light section data must
     * therefore travel as a buffer texture instead of an SSBO. Decided once on the render thread
     * before any flywheel program can exist; read by ColoredLightFlywheelStorage and GlProgramMixin.
     */
    private static boolean textureFallback;

    /**
     * Every live level-backed storage, one per flywheel LightStorage (see LightStorageMixin);
     * self-registered in the storage's constructor, removed on delete(). Normally one entry, but
     * Immersive Portals' other-dimension levels and Ponder scenes each add their own. Used by the
     * engine-wide refresh paths (toggle, dirty sections) and '/cl flywheel report'. Render thread
     * only, like everything else in this compat.
     */
    private static final List<ColoredLightFlywheelStorage> activeStorages = new ArrayList<>();

    public static void init() {
        // Probe the Flywheel 1.0 API before ColoredLightFlywheelStorage (which references it in
        // field and method signatures) is ever loaded, so an unsupported Flywheel degrades to
        // plain vanilla-lit flywheel rendering instead of a NoClassDefFoundError. Mirrors the
        // gate ColorfulLightingMixinPlugin applies to the flywheel mixins.
        if (!hasClass("dev.engine_room.flywheel.backend.engine.LightStorage")
                || !hasClass("dev.engine_room.flywheel.backend.engine.CpuArena")
                || !hasClass("dev.engine_room.flywheel.backend.engine.indirect.StagingBuffer")
                || !hasClass("dev.engine_room.flywheel.backend.gl.GlCompat")
                // the per-level storages take their level from LightStorage#level()
                || !hasMethod("dev.engine_room.flywheel.backend.engine.LightStorage", "level")) {
            ColorfulLighting.LOGGER.warn("Flywheel is installed but not a supported version; colored light on flywheel-rendered objects is disabled");
            return;
        }
        RenderSystem.recordRenderCall(() -> {
            // Flywheel stamps "#version MAX_GLSL_VERSION" into every shader it compiles, and
            // colored_light.glsl switches on __VERSION__ >= 430 between the SSBO and the
            // buffer-texture fallback — so deciding from the same value keeps the Java side and
            // the shaders in lockstep. Below GLSL 430 only the instancing backend can run
            // (indirect needs GL 4.6), and buffer textures are core since GL 3.1, below
            // flywheel's own minimum.
            textureFallback = GlCompat.MAX_GLSL_VERSION.compareTo(GlslVersion.V430) < 0;
            // logged unconditionally: any log file must answer "which transport actually ran"
            ColorfulLighting.LOGGER.info("Flywheel colored light mode: {} (flywheel GLSL {})",
                    textureFallback ? "buffer texture" : "SSBO", GlCompat.MAX_GLSL_VERSION);
            instance = new FlywheelCompat();
        });
    }

    private static boolean hasClass(String className) {
        try {
            Class.forName(className, false, FlywheelCompat.class.getClassLoader());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean hasMethod(String className, String methodName) {
        try {
            Class.forName(className, false, FlywheelCompat.class.getClassLoader()).getMethod(methodName);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static FlywheelCompat getInstance() {
        return instance;
    }

    public static boolean isAvailable() {
        return instance != null;
    }

    public static boolean isTextureFallback() {
        return textureFallback;
    }

    static void registerStorage(ColoredLightFlywheelStorage storage) {
        activeStorages.add(storage);
    }

    static void unregisterStorage(ColoredLightFlywheelStorage storage) {
        activeStorages.remove(storage);
    }

    /** Engine toggle path: refresh every level's flywheel buffers. Safe to call with none live. */
    public static void recollectAllTracked() {
        for (ColoredLightFlywheelStorage storage : activeStorages) {
            storage.recollectAllTracked();
        }
    }

    /**
     * Dirty-section path, called by a specific level's engine. Filtered by level because section
     * coordinates overlap across dimensions: with an Immersive Portal active, the Overworld and
     * Nether both track sections near the player, and an unfiltered refresh would recollect the
     * other dimension's storage for a change that never happened there.
     */
    public static void recollectSectionIfTracked(LevelAccessor level, long section) {
        for (ColoredLightFlywheelStorage storage : activeStorages) {
            if (storage.isForLevel(level)) {
                storage.recollectSectionIfTracked(section);
            }
        }
    }

    /** '/cl flywheel report': one line per live storage so per-level state is visible. */
    public static String debugReportAll() {
        if (activeStorages.isEmpty()) {
            return "no flywheel colored light storages are live (no flywheel engine has been created yet)";
        }
        StringBuilder report = new StringBuilder();
        for (ColoredLightFlywheelStorage storage : activeStorages) {
            if (report.length() > 0) report.append('\n');
            report.append(storage.debugReport());
        }
        return report.toString();
    }

    /** Human-readable state for the '/cl flywheel' command. Safe to call with flywheel absent. */
    public static String describeMode() {
        if (!net.minecraftforge.fml.ModList.get().isLoaded("flywheel")) {
            return "Flywheel is not installed";
        }
        if (instance == null) {
            return "Flywheel colored light is inactive (unsupported flywheel version, or still initializing)";
        }
        // safe: instance != null implies the flywheel classes exist
        String glsl = String.valueOf(GlCompat.MAX_GLSL_VERSION);
        return textureFallback
                ? "Flywheel colored light mode: buffer texture (flywheel GLSL " + glsl + ")"
                : "Flywheel colored light mode: SSBO (flywheel GLSL " + glsl + ")";
    }

    /**
     * The placeholder keeps texture unit 10 holding a complete, zero-filled buffer texture from
     * the moment the fallback mode is decided — macOS validates sampler bindings at draw time
     * and drops draws over a missing or unattached one (see ColoredLightFlywheelStorage's
     * constructor). Level-null, never registered, never collects; per-level storages bind over
     * it every frame once they exist. In SSBO mode it allocates no GL objects at all.
     */
    @SuppressWarnings("unused")
    private final ColoredLightFlywheelStorage placeholderStorage;

    public FlywheelCompat() {
        placeholderStorage = new ColoredLightFlywheelStorage(null);
    }
}
