package com.retrocam.core;

import com.retrocam.camera.OrbitCamera;
import com.retrocam.camera.ThinLensCamera;
import com.retrocam.gl.ShaderProgram;
import com.retrocam.scene.SceneUploader;

import static org.lwjgl.opengl.GL43.*;

/**
 * Manages the progressive path-tracing accumulation loop.
 *
 * Phase 4: {@link #render} now accepts {@link TemporalState} and passes it
 * to {@link ThinLensCamera#uploadTo} so the IIR-filtered focal distance is
 * used for ray generation (auto-focus lag, R4).
 */
public final class Renderer {

    private final ShaderProgram pathTraceShader;
    private int   accumTexture;
    private int   totalSamples = 0;

    private static final int RENDER_W = RenderSettings.RENDER_WIDTH;
    private static final int RENDER_H = RenderSettings.RENDER_HEIGHT;

    // ── Construction ──────────────────────────────────────────────────────────

    public Renderer() {
        pathTraceShader = ShaderProgram.createCompute("/shaders/pathtrace.comp");
        accumTexture    = createAccumTexture();
        System.out.println("[Renderer] Ready.");
    }

    // ── Render one accumulation pass ──────────────────────────────────────────

    /**
     * Dispatches the path-trace compute shader for one sample-per-pixel pass.
     *
     * @param temporal supplies the IIR-filtered focal distance and AGC gain.
     *                 Must be updated by the caller before this is invoked.
     */
    public void render(SceneUploader sceneUploader,
                       OrbitCamera orbit,
                       ThinLensCamera tlc,
                       TemporalState temporal,
                       RenderSettings settings) {

        pathTraceShader.bind();

        // Bind accumulation image (READ_WRITE)
        glBindImageTexture(0, accumTexture, 0, false, 0, GL_READ_WRITE, GL_RGBA32F);

        // Camera + optical uniforms (Phase 4: includes SA, LCA, IIR focal dist)
        tlc.uploadTo(pathTraceShader, orbit, settings, temporal);

        // Temporal uniforms (AGC gain, time, tape age, white balance)
        temporal.uploadTo(pathTraceShader, settings);

        // Frame counter (used as RNG seed multiplier in shader)
        pathTraceShader.setInt("u_frameIndex", totalSamples);
        glUniform2i(glGetUniformLocation(pathTraceShader.id(), "u_resolution"),
                    RENDER_W, RENDER_H);

        // Scene SSBOs
        sceneUploader.bind();

        // Dispatch 16×16 tiles
        int gx = (RENDER_W + 15) / 16;
        int gy = (RENDER_H + 15) / 16;
        glDispatchCompute(gx, gy, 1);
        glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL_TEXTURE_FETCH_BARRIER_BIT);

        sceneUploader.unbind();
        pathTraceShader.unbind();

        totalSamples++;
    }

    // ── Reset ─────────────────────────────────────────────────────────────────

    public void reset() {
        if (accumTexture != 0) glDeleteTextures(accumTexture);
        accumTexture  = createAccumTexture();
        totalSamples  = 0;
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public int  getAccumTexture()  { return accumTexture;  }
    public int  getTotalSamples()  { return totalSamples;  }

    // ── Cleanup ───────────────────────────────────────────────────────────────

    public void destroy() {
        pathTraceShader.destroy();
        if (accumTexture != 0) glDeleteTextures(accumTexture);
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private static int createAccumTexture() {
        int tex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, tex);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA32F, RENDER_W, RENDER_H,
                     0, GL_RGBA, GL_FLOAT, (java.nio.ByteBuffer) null);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glBindTexture(GL_TEXTURE_2D, 0);
        return tex;
    }
}