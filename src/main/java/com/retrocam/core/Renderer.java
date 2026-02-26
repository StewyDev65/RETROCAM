package com.retrocam.core;

import com.retrocam.camera.OrbitCamera;
import com.retrocam.camera.ThinLensCamera;
import com.retrocam.camera.CameraView;
import com.retrocam.gl.ShaderProgram;
import com.retrocam.scene.SceneUploader;

import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL11.GL_NEAREST;
import static org.lwjgl.opengl.GL11.GL_RED;
import static org.lwjgl.opengl.GL11.GL_RGBA;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_MAG_FILTER;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_MIN_FILTER;
import static org.lwjgl.opengl.GL11.glBindTexture;
import static org.lwjgl.opengl.GL11.glDeleteTextures;
import static org.lwjgl.opengl.GL11.glGenTextures;
import static org.lwjgl.opengl.GL11.glTexImage2D;
import static org.lwjgl.opengl.GL11.glTexParameteri;
import static org.lwjgl.opengl.GL15.GL_READ_WRITE;
import static org.lwjgl.opengl.GL20.glGetUniformLocation;
import static org.lwjgl.opengl.GL20.glUniform2i;
import static org.lwjgl.opengl.GL30.GL_RGBA32F;
import static org.lwjgl.opengl.GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT;
import static org.lwjgl.opengl.GL42.GL_TEXTURE_FETCH_BARRIER_BIT;
import static org.lwjgl.opengl.GL42.glBindImageTexture;
import static org.lwjgl.opengl.GL42.glMemoryBarrier;
import static org.lwjgl.opengl.GL43.*;
import static org.lwjgl.opengl.GL30.GL_R32F;
import static org.lwjgl.opengl.GL30.GL_RGBA16F;
import static org.lwjgl.opengl.GL15.GL_WRITE_ONLY;

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
    private int   gBufferTexture;
    private int   gAlbedoTexture;
    private int   varianceTexture;
    private int   totalSamples = 0;

    private static final int RENDER_W = RenderSettings.RENDER_WIDTH;
    private static final int RENDER_H = RenderSettings.RENDER_HEIGHT;

    // ── Construction ──────────────────────────────────────────────────────────

    public Renderer() {
        pathTraceShader = ShaderProgram.createCompute("/shaders/pathtrace.comp");
        accumTexture    = createAccumTexture();
        gBufferTexture  = createGBufferTexture();
        gAlbedoTexture  = createGAlbedoTexture();
        varianceTexture = createVarianceTexture();
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
                       CameraView orbit,
                       ThinLensCamera tlc,
                       TemporalState temporal,
                       RenderSettings settings) {

        pathTraceShader.bind();

        // Bind accumulation image (READ_WRITE)
        glBindImageTexture(0, accumTexture, 0, false, 0, GL_READ_WRITE, GL_RGBA32F);
        glBindImageTexture(1, gBufferTexture, 0, false, 0, GL_WRITE_ONLY,  GL_RGBA16F);
        glBindImageTexture(2, gAlbedoTexture,  0, false, 0, GL_WRITE_ONLY,  GL_RGBA16F);
        glBindImageTexture(3, varianceTexture, 0, false, 0, GL_READ_WRITE,  GL_R32F);

        // Camera + optical uniforms (Phase 4: includes SA, LCA, IIR focal dist)
        tlc.uploadTo(pathTraceShader, orbit, settings, temporal);

        // Temporal uniforms (AGC gain, time, tape age, white balance)
        temporal.uploadTo(pathTraceShader, settings);

        // NEE direct light sampling uniforms
        pathTraceShader.setInt  ("u_neeEnabled",       settings.neeEnabled ? 1 : 0);
        pathTraceShader.setInt  ("u_lightCount",        sceneUploader.getLightCount());
        pathTraceShader.setFloat("u_totalLightPower",   sceneUploader.getTotalLightPower());
        pathTraceShader.setFloat("u_neeFireflyClamp",   settings.neeFireflyClamp);

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
        if (accumTexture   != 0) glDeleteTextures(accumTexture);
        if (gBufferTexture != 0) glDeleteTextures(gBufferTexture);
        if (gAlbedoTexture != 0) glDeleteTextures(gAlbedoTexture);
        if (varianceTexture!= 0) glDeleteTextures(varianceTexture);
        accumTexture    = createAccumTexture();
        gBufferTexture  = createGBufferTexture();
        gAlbedoTexture  = createGAlbedoTexture();
        varianceTexture = createVarianceTexture();
        totalSamples    = 0;
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public int  getAccumTexture()    { return accumTexture;  }
    public int  getGBufferTexture()  { return gBufferTexture; }
    public int  getGAlbedoTexture()   { return gAlbedoTexture;  }
    public int  getVarianceTexture()  { return varianceTexture; }
    public int  getTotalSamples()    { return totalSamples;  }

    // ── Cleanup ───────────────────────────────────────────────────────────────

    public void destroy() {
        pathTraceShader.destroy();
        if (accumTexture != 0) glDeleteTextures(accumTexture);
        if (gBufferTexture != 0) glDeleteTextures(gBufferTexture);
        if (gAlbedoTexture  != 0) glDeleteTextures(gAlbedoTexture);
        if (varianceTexture != 0) glDeleteTextures(varianceTexture);
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

    private static int createGBufferTexture() {
        int tex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, tex);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA16F, RENDER_W, RENDER_H,
                    0, GL_RGBA, GL_FLOAT, (java.nio.ByteBuffer) null);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glBindTexture(GL_TEXTURE_2D, 0);
        return tex;
    }

    private static int createGAlbedoTexture() {
        int tex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, tex);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA16F, RENDER_W, RENDER_H,
                     0, GL_RGBA, GL_FLOAT, (java.nio.ByteBuffer) null);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glBindTexture(GL_TEXTURE_2D, 0);
        return tex;
    }

    private static int createVarianceTexture() {
        int tex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, tex);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_R32F, RENDER_W, RENDER_H,
                     0, GL_RED, GL_FLOAT, (java.nio.ByteBuffer) null);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glBindTexture(GL_TEXTURE_2D, 0);
        return tex;
    }
}