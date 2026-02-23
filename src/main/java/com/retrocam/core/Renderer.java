package com.retrocam.core;

import com.retrocam.camera.OrbitCamera;
import com.retrocam.camera.ThinLensCamera;
import com.retrocam.gl.ShaderProgram;
import com.retrocam.scene.SceneUploader;

import static org.lwjgl.opengl.GL43.*;

/**
 * Manages the progressive path-tracing accumulation loop.
 *
 * Each call to {@link #render} dispatches {@code pathtrace.comp} for one sample
 * per pixel, adding to the running sum stored in the {@code GL_RGBA32F}
 * accumulation texture. The display shader divides by {@code totalSamples}
 * to produce the current average.
 *
 * Call {@link #reset()} whenever the camera or scene changes to restart
 * the accumulation from scratch.
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
     * All SSBOs in {@code sceneUploader} must already be bound (caller's responsibility).
     */
    public void render(SceneUploader sceneUploader,
                       OrbitCamera orbit,
                       ThinLensCamera tlc,
                       TemporalState temporal,
                       RenderSettings settings) {

        pathTraceShader.bind();

        // Bind accumulation image (READ_WRITE: shader reads prev, writes sum)
        glBindImageTexture(0, accumTexture, 0, false, 0, GL_READ_WRITE, GL_RGBA32F);

        // Camera uniforms
        tlc.uploadTo(pathTraceShader, orbit, settings);

        // Temporal uniforms
        temporal.uploadTo(pathTraceShader, settings);

        // Frame counters
        pathTraceShader.setInt("u_frameIndex",   totalSamples);
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

    /**
     * Resets the accumulation — call whenever the camera or scene changes.
     * Recreates the accumulation texture so stale data is never blended with
     * the new viewpoint (avoids a GPU-side clear pass).
     */
    public void reset() {
        if (accumTexture != 0) glDeleteTextures(accumTexture);
        accumTexture  = createAccumTexture();
        totalSamples  = 0;
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    /** The accumulated sum texture; divide by {@link #getTotalSamples()} to display. */
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