package me.erykczy.colorfullighting.compat.distanthorizons;

import com.seibel.distanthorizons.api.interfaces.override.rendering.IDhApiShaderProgram;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;
import com.seibel.distanthorizons.api.objects.math.DhApiMat4f;
import com.seibel.distanthorizons.api.objects.math.DhApiVec3f;
import me.erykczy.colorfullighting.ColorfulLighting;
import me.erykczy.colorfullighting.common.ColoredLightEngine;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Replacement for DH's terrain shader program that samples the remembered colour volume and tints
 * the block-light part of every LOD fragment. Registered through {@code DhApi.overrides}, so no DH
 * internals are touched; if anything in here fails, {@link #overrideThisFrame()} starts returning
 * false and DH silently falls back to its own shader.
 *
 * <p>Mirrors {@code GlDhTerrainShaderProgram_forge} (DH 3.1.2, decompiled 2026-08-05):
 * <ul>
 * <li>Vertex format, stride 16: attr0 = 4 x u16 as integers ({@code uvec4 vPosition}: xyz local to
 *     the buffer + 16-bit meta whose low byte is {@code sky*16+block} light and bits 8-13 a micro
 *     offset), attr1 = 4 x u8 normalized (albedo), attr2 = 4 x u8 (unused).</li>
 * <li>Attribute names bound pre-link: {@code vPosition} to 0, {@code color} to 1.</li>
 * <li>{@code uCombinedMatrix} = dhProjection x dhModelView. DH's {@code storeMatrixInBuffer} writes
 *     column-major ({@code bufferIndex(row,col) = col*4 + row}); {@code putValuesInArray} is
 *     row-major, so the upload uses transpose=true.</li>
 * <li>{@code uModelOffset} per buffer = minCornerBlockPos - exactCameraPosition, via
 *     {@link #setModelOffsetPos}; vertex world pos is camera-relative, so absolute position =
 *     vertexWorldPos + camera.</li>
 * <li>{@code uClipDistance} = nearClipPlane + 16 with dithered fade, DH's default. (The July 2026
 *     attempt guessed renderDistance*16 here, which drew a wrong-sized transparent ring.)</li>
 * <li>DH calls {@code bind()} FIRST (not {@code overrideThisFrame()}), so GL init is lazy in every
 *     entry point. The element buffer DH binds after {@code bind()} lands in our VAO, as intended.</li>
 * </ul>
 *
 * <p>Every DH-facing callback is wrapped: one failure flips {@link #failed} and the override backs
 * off for the rest of the session instead of blanking DH's LODs.
 */
public final class DhTerrainColorShaderProgram implements IDhApiShaderProgram {
    private static final String VERT_PATH = "/assets/colorful_lighting/shaders/dh/colorful_dh_terrain.vert";
    private static final String FRAG_PATH = "/assets/colorful_lighting/shaders/dh/colorful_dh_terrain.frag";
    private static final int VERTEX_STRIDE_BYTES = 16;
    /** Well clear of unit 0, where DH binds the vanilla lightmap. */
    private static final int NEAR_VOLUME_TEXTURE_UNIT = 4;
    private static final int FAR_VOLUME_TEXTURE_UNIT = 5;

    private boolean initialized;
    private volatile boolean failed;
    private int program;
    private int vao;

    private int uCombinedMatrix;
    private int uModelOffset;
    private int uWorldYOffset;
    private int uMircoOffset;
    private int uLightMap;
    private int uClipDistance;
    private int uClVolumeNear;
    private int uClVolumeFar;
    private int uClCameraPos;
    private int uClNearMin;
    private int uClNearInvSize;
    private int uClFarMin;
    private int uClFarInvSize;
    private int uClDebugMode;

    private final float[] matrixScratch = new float[16];

    @Override
    public boolean overrideThisFrame() {
        return !failed && DhCompat.isOverrideEnabled() && ColoredLightEngine.isEnabled() && ensureInitialized();
    }

    @Override
    public int getId() {
        ensureInitialized();
        return program;
    }

    @Override
    public void bind() {
        if (!ensureInitialized()) return;
        try {
            GL20.glUseProgram(program);
            GL30.glBindVertexArray(vao);
        } catch (Throwable t) {
            fail("bind", t);
        }
    }

    @Override
    public void unbind() {
        try {
            GL30.glBindVertexArray(0);
            GL20.glUseProgram(0);
        } catch (Throwable t) {
            fail("unbind", t);
        }
    }

    @Override
    public void bindVertexBuffer(int vbo) {
        if (!ensureInitialized()) return;
        try {
            // Pointer state is per-buffer in core GL, so respecify on every buffer like DH's
            // pre-GL43 path does. Our VAO is bound (bind() ran earlier in DH's draw loop).
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
            GL30.glVertexAttribIPointer(0, 4, GL11.GL_UNSIGNED_SHORT, VERTEX_STRIDE_BYTES, 0);
            GL20.glVertexAttribPointer(1, 4, GL11.GL_UNSIGNED_BYTE, true, VERTEX_STRIDE_BYTES, 8);
        } catch (Throwable t) {
            fail("bindVertexBuffer", t);
        }
    }

    @Override
    public void setModelOffsetPos(DhApiVec3f modelPos) {
        if (!ensureInitialized()) return;
        try {
            GL20.glUseProgram(program);
            GL20.glUniform3f(uModelOffset, modelPos.x, modelPos.y, modelPos.z);
        } catch (Throwable t) {
            fail("setModelOffsetPos", t);
        }
    }

    @Override
    public void fillUniformData(DhApiRenderParam param) {
        if (!ensureInitialized()) return;
        try {
            GL20.glUseProgram(program);

            // Keep the colour volume fresh; internally throttled, usually a no-op.
            Vec3 camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
            DhColorVolume volume = DhCompat.getOrCreateVolume();
            volume.update(DhCompat.getActiveCache(), camera.x, camera.y, camera.z);

            DhApiMat4f combined = new DhApiMat4f(param.dhProjectionMatrix);
            combined.multiply(param.dhModelViewMatrix);
            combined.putValuesInArray(matrixScratch);
            // putValuesInArray is row-major (mRC rows first); DH's own storeMatrixInBuffer writes
            // col*4+row (column-major), so upload with transpose=true to match. Getting this wrong
            // renders the LODs as a camera-glued inverted-rotation sheet (test #2, 2026-08-05).
            GL20.glUniformMatrix4fv(uCombinedMatrix, true, matrixScratch);

            GL20.glUniform1f(uMircoOffset, 0.01f);
            GL20.glUniform1i(uLightMap, 0);
            GL20.glUniform1f(uWorldYOffset, param.worldYOffset);
            GL20.glUniform1f(uClipDistance, param.nearClipPlane + 16.0f);

            GL20.glUniform1i(uClVolumeNear, NEAR_VOLUME_TEXTURE_UNIT);
            GL20.glUniform1i(uClVolumeFar, FAR_VOLUME_TEXTURE_UNIT);
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + NEAR_VOLUME_TEXTURE_UNIT);
            GL11.glBindTexture(GL12.GL_TEXTURE_3D, volume.nearTextureId());
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + FAR_VOLUME_TEXTURE_UNIT);
            GL11.glBindTexture(GL12.GL_TEXTURE_3D, volume.farTextureId());
            GL13.glActiveTexture(GL13.GL_TEXTURE0);

            GL20.glUniform3f(uClCameraPos, (float) camera.x, (float) camera.y, (float) camera.z);
            GL20.glUniform3f(uClNearMin, volume.nearMinX(), volume.nearMinY(), volume.nearMinZ());
            GL20.glUniform1f(uClNearInvSize, volume.nearInvSizeBlocks());
            GL20.glUniform3f(uClFarMin, volume.farMinX(), volume.farMinY(), volume.farMinZ());
            GL20.glUniform1f(uClFarInvSize, volume.farInvSizeBlocks());
            GL20.glUniform1i(uClDebugMode, DhCompat.getDebugMode());
        } catch (Throwable t) {
            fail("fillUniformData", t);
        }
    }

    @Override
    public void free() {
        try {
            if (program != 0) GL20.glDeleteProgram(program);
            if (vao != 0) GL30.glDeleteVertexArrays(vao);
        } catch (Throwable ignored) {
        }
        program = 0;
        vao = 0;
        initialized = false;
    }

    /**
     * Lazy GL setup: DH invokes the callbacks on its render thread with a current context, and which
     * callback comes first differs between DH builds (3.1.2 starts with bind()).
     */
    private boolean ensureInitialized() {
        if (initialized) return !failed;
        if (failed) return false;
        try {
            init();
            initialized = true;
            ColorfulLighting.LOGGER.info("[DH override] shader program initialised (program id {})", program);
            return true;
        } catch (Throwable t) {
            fail("init", t);
            return false;
        }
    }

    private void init() throws IOException {
        int vert = compileShader(GL20.GL_VERTEX_SHADER, readResource(VERT_PATH), VERT_PATH);
        int frag;
        try {
            frag = compileShader(GL20.GL_FRAGMENT_SHADER, readResource(FRAG_PATH), FRAG_PATH);
        } catch (RuntimeException | IOException e) {
            GL20.glDeleteShader(vert);
            throw e;
        }

        program = GL20.glCreateProgram();
        GL20.glAttachShader(program, vert);
        GL20.glAttachShader(program, frag);
        // Same attribute locations DH assigns to its own program; the vertex buffers expect them.
        GL20.glBindAttribLocation(program, 0, "vPosition");
        GL20.glBindAttribLocation(program, 1, "color");
        GL20.glLinkProgram(program);
        GL20.glDetachShader(program, vert);
        GL20.glDetachShader(program, frag);
        GL20.glDeleteShader(vert);
        GL20.glDeleteShader(frag);
        if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetProgramInfoLog(program);
            GL20.glDeleteProgram(program);
            program = 0;
            throw new RuntimeException("program link failed: " + log);
        }

        uCombinedMatrix = GL20.glGetUniformLocation(program, "uCombinedMatrix");
        uModelOffset = GL20.glGetUniformLocation(program, "uModelOffset");
        uWorldYOffset = GL20.glGetUniformLocation(program, "uWorldYOffset");
        uMircoOffset = GL20.glGetUniformLocation(program, "uMircoOffset");
        uLightMap = GL20.glGetUniformLocation(program, "uLightMap");
        uClipDistance = GL20.glGetUniformLocation(program, "uClipDistance");
        uClVolumeNear = GL20.glGetUniformLocation(program, "uClVolumeNear");
        uClVolumeFar = GL20.glGetUniformLocation(program, "uClVolumeFar");
        uClCameraPos = GL20.glGetUniformLocation(program, "uClCameraPos");
        uClNearMin = GL20.glGetUniformLocation(program, "uClNearMin");
        uClNearInvSize = GL20.glGetUniformLocation(program, "uClNearInvSize");
        uClFarMin = GL20.glGetUniformLocation(program, "uClFarMin");
        uClFarInvSize = GL20.glGetUniformLocation(program, "uClFarInvSize");
        uClDebugMode = GL20.glGetUniformLocation(program, "uClDebugMode");

        vao = GL30.glGenVertexArrays();
        GL30.glBindVertexArray(vao);
        GL20.glEnableVertexAttribArray(0);
        GL20.glEnableVertexAttribArray(1);
        GL30.glBindVertexArray(0);
    }

    private static int compileShader(int type, String source, String name) {
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetShaderInfoLog(shader);
            GL20.glDeleteShader(shader);
            throw new RuntimeException(name + " compile failed: " + log);
        }
        return shader;
    }

    private static String readResource(String path) throws IOException {
        try (InputStream in = DhTerrainColorShaderProgram.class.getResourceAsStream(path)) {
            if (in == null) throw new IOException("missing classpath resource " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private void fail(String where, Throwable t) {
        if (!failed) {
            failed = true;
            ColorfulLighting.LOGGER.error(
                    "[DH override] {} failed; disabling colored LOD lighting for this session (DH falls back to its own shader)",
                    where, t);
            // DH 3.1.2 does NOT consult our overrideThisFrame() when picking the frame's program
            // (GlDhMetaRenderer_forge checks the default program's, a DH quirk), so returning false
            // is not enough — a bound-but-broken override would keep glitching LODs forever.
            // Actually unbinding is the only real fallback.
            DhCompat.requestEmergencyUnbind();
        }
    }
}
