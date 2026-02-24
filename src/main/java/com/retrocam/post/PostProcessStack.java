package com.retrocam.post;

import com.retrocam.core.RenderSettings;
import com.retrocam.core.TemporalState;
import com.retrocam.gl.Framebuffer;
import com.retrocam.gl.PingPongBuffer;
import com.retrocam.gl.ShaderProgram;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13.glActiveTexture;
import static org.lwjgl.opengl.GL20.glDrawArrays;
import static org.lwjgl.opengl.GL30.*;

/**
 * Manages the full 20-pass post-process pipeline.
 *
 * <p><b>Batch 1 (this class):</b> p00 normalize, p01 downsample, p02 chroma-res,
 * p07 CCD noise, p09 chroma-bleed, p10 timebase.
 * Tonemapping is handled by the display shader (display.frag), not a post-process pass.
 * Passes not yet implemented are skipped (input passes through unchanged).</p>
 *
 * <p>Two entry points:</p>
 * <ul>
 *   <li>{@link #runOnAccum} – renderer accumulation buffer (running sum, divided by N)</li>
 *   <li>{@link #runOnImage} – pre-loaded image texture (already [0,1]), for static testing</li>
 * </ul>
 */
public final class PostProcessStack {

    // ── GL resources ──────────────────────────────────────────────────────────

    /** Shared fullscreen VAO (owned by Main, never destroyed here). */
    private final int vao;

    /**
     * p00 normalize writes here so p01+ always starts from a known RGBA32F
     * HDR buffer regardless of the original accumulation texture format.
     */
    private final Framebuffer normalizeBuffer;

    /** Ping-pong buffers for passes p01 – p20. */
    private final PingPongBuffer pingPong;

    // ── Implemented passes (Batch 1) ──────────────────────────────────────────

    private final PostProcessPass p00Normalize;   // accum÷N + exposure
    private final PostProcessPass p01Downsample;  // luma horizontal BW limit
    private final PostProcessPass p02ChromaRes;   // VHS chroma resolution loss
    private final PostProcessPass p07CcdNoise;    // CCD sensor noise (AGC-scaled)
    private final PostProcessPass p09ChromaBleed; // rightward chroma smear
    private final PostProcessPass p10Timebase;    // per-scanline horizontal jitter

    // ── Construction ──────────────────────────────────────────────────────────

    public PostProcessStack(int fullscreenVao) {
        this.vao = fullscreenVao;

        int W = RenderSettings.RENDER_WIDTH;
        int H = RenderSettings.RENDER_HEIGHT;

        normalizeBuffer = new Framebuffer(W, H, GL_RGBA32F);
        pingPong        = new PingPongBuffer(W, H, GL_RGBA32F);

        p00Normalize   = new PostProcessPass("p00_normalize",   "/shaders/post/p00_normalize.frag");
        p01Downsample  = new PostProcessPass("p01_downsample",  "/shaders/post/p01_downsample.frag");
        p02ChromaRes   = new PostProcessPass("p02_chroma_res",  "/shaders/post/p02_chroma_res.frag");
        p07CcdNoise    = new PostProcessPass("p07_ccd_noise",   "/shaders/post/p07_ccd_noise.frag");
        p09ChromaBleed = new PostProcessPass("p09_chroma_bleed","/shaders/post/p09_chroma_bleed.frag");
        p10Timebase    = new PostProcessPass("p10_timebase",    "/shaders/post/p10_timebase.frag");
    }

    // ── Public entry points ────────────────────────────────────────────────────

    /**
     * Run the full post-process chain on the renderer's accumulation texture
     * (which holds a running SUM that must be divided by {@code totalSamples}).
     *
     * @param accumTexId   GL texture ID of the HDR accumulation buffer
     * @param totalSamples number of accumulated samples (used to normalize)
     * @param exposure     EV exposure multiplier
     * @param s            current render settings
     * @param ts           current temporal state (AGC gain, etc.)
     * @return GL texture ID of the final LDR output (valid until next call)
     */
    public int runOnAccum(int accumTexId, int totalSamples, float exposure,
                          RenderSettings s, TemporalState ts) {
        // p00: normalize running sum → linear HDR [0, ~1]
        blit(p00Normalize, accumTexId, normalizeBuffer, sh -> {
            sh.setInt("u_totalSamples", Math.max(totalSamples, 1));
            sh.setFloat("u_exposure", exposure);
        });

        return runChain(normalizeBuffer.textureId(), s, ts);
    }

    /**
     * Run the post-process chain on a pre-loaded LDR image texture.
     * Skips normalization (image is already in [0,1]).  Useful for
     * testing individual passes without running the renderer.
     *
     * @param imageTexId GL texture ID of the loaded image
     * @param s          current render settings
     * @param ts         current temporal state
     * @return GL texture ID of the final output
     */
    public int runOnImage(int imageTexId, RenderSettings s, TemporalState ts) {
        // Blit image into normalizeBuffer (converts to RGBA32F, applies no correction)
        blit(p00Normalize, imageTexId, normalizeBuffer, sh -> {
            sh.setInt("u_totalSamples", 1);   // divide by 1 = identity
            sh.setFloat("u_exposure", 1.0f);  // no exposure shift for test images
        });

        return runChain(normalizeBuffer.textureId(), s, ts);
    }

    // ── Internal chain ─────────────────────────────────────────────────────────

    private int runChain(int current, RenderSettings s, TemporalState ts) {

        // p01 – VHS luma horizontal bandwidth limit
        if (s.p01Enabled) {
            current = swap(p01Downsample, current, sh -> {
                sh.setFloat("u_lumaBlurSigma", s.lumaBlurSigma);
            });
        }

        // p02 – VHS chroma resolution loss (heavy horizontal blur on Cb/Cr)
        if (s.p02Enabled) {
            current = swap(p02ChromaRes, current, sh -> {
                sh.setFloat("u_chromaSigma", s.chromaResSigma);
            });
        }

        // p03 – p06: not yet implemented – pass through

        // p07 – CCD sensor noise (luminance-dependent, AGC-scaled, chroma noise)
        if (s.p07Enabled) {
            current = swap(p07CcdNoise, current, sh -> {
                sh.setInt("u_frameIndex",        s.frameIndex);
                sh.setFloat("u_agcGain",          ts.agcGain);
                sh.setFloat("u_lumaNoiseBase",    s.ccdNoiseLumaBase);
                sh.setFloat("u_chromaNoiseScale", s.ccdNoiseChromaScale);
            });
        }

        // p08: not yet implemented – pass through

        // p09 – Horizontal chroma bleed (rightward exponential smear)
        if (s.p09Enabled) {
            current = swap(p09ChromaBleed, current, sh -> {
                sh.setFloat("u_bleedFactor", s.chromaBleedFactor);
            });
        }

        // p10 – Per-scanline horizontal timebase jitter
        if (s.p10Enabled) {
            current = swap(p10Timebase, current, sh -> {
                sh.setFloat("u_amplitude",  s.timebaseAmplitudePx);
                sh.setFloat("u_freq",       s.timebaseFreq);
                sh.setFloat("u_speed",      s.timebaseSpeed);
                sh.setFloat("u_time",       ts.time);
                sh.setInt("u_frameIndex",   s.frameIndex);
            });
        }

        // p11 – p19: not yet implemented – pass through

        return current;
    }

    // ── GL helpers ────────────────────────────────────────────────────────────

    /**
     * Render a pass from {@code inputTex} into the ping-pong write buffer,
     * then swap so the result becomes the new read source.
     *
     * @return texture ID of the result (in the old write buffer, now the read buffer)
     */
    private int swap(PostProcessPass pass, int inputTex, UniformSetter uniforms) {
        blit(pass, inputTex, pingPong.writeBuffer(), uniforms);
        int result = pingPong.writeBuffer().textureId();
        pingPong.swap();
        return result;
    }

    /**
     * Render {@code pass} from {@code inputTex} into {@code target}.
     * Binds the FBO, sets {@code u_tex = 0}, invokes the uniform setter,
     * draws the fullscreen triangle, then cleans up bindings.
     */
    private void blit(PostProcessPass pass, int inputTex, Framebuffer target,
                      UniformSetter uniforms) {
        target.bindForWrite();

        ShaderProgram sh = pass.shader();
        sh.bind();

        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, inputTex);
        sh.setInt("u_tex", 0);

        uniforms.set(sh);

        glBindVertexArray(vao);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        glBindVertexArray(0);

        sh.unbind();
        target.unbind();
    }

    // ── Cleanup ───────────────────────────────────────────────────────────────

    public void destroy() {
        normalizeBuffer.destroy();
        pingPong.destroy();
        p00Normalize.destroy();
        p01Downsample.destroy();
        p02ChromaRes.destroy();
        p07CcdNoise.destroy();
        p09ChromaBleed.destroy();
        p10Timebase.destroy();
    }

    // ── Functional interface ───────────────────────────────────────────────────

    @FunctionalInterface
    private interface UniformSetter {
        void set(ShaderProgram shader);
    }
}