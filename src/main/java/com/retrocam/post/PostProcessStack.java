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
 * p03 color-matrix, p04 dynamic-range, p07 CCD noise, p08 CCD smear,
 * p09 chroma-bleed, p10 timebase.
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
    private final PostProcessPass p03ColorMatrix; // warm bias, hue, saturation, drift
    private final PostProcessPass p04DynamicRange;// tape DR curve: lift/crush/knee/clip
    private final PostProcessPass p07CcdNoise;    // CCD sensor noise (AGC-scaled)
    private final PostProcessPass p08CcdSmear;    // vertical column overflow streak
    private final PostProcessPass p09ChromaBleed; // rightward chroma smear
    private final PostProcessPass p10Timebase;    // per-scanline horizontal jitter
    private final PostProcessPass p11DotCrawl;    // NTSC dot crawl at luma edges
    private final PostProcessPass p12Dropout;     // magnetic tape dropout streaks
    private final PostProcessPass p13HeadSwitch;
    private final PostProcessPass p14Tracking;
    private final PostProcessPass p15EdgeEnhance;
    private final PostProcessPass p16Interlace;

    private final AtrousDenoiser atrous;

    // ── Construction ──────────────────────────────────────────────────────────

    public PostProcessStack(int fullscreenVao) {
        this.vao = fullscreenVao;

        int W = RenderSettings.RENDER_WIDTH;
        int H = RenderSettings.RENDER_HEIGHT;

        normalizeBuffer = new Framebuffer(W, H, GL_RGBA32F);
        pingPong        = new PingPongBuffer(W, H, GL_RGBA32F);

        p00Normalize   = new PostProcessPass("p00_normalize",    "/shaders/post/p00_normalize.frag");
        p01Downsample  = new PostProcessPass("p01_downsample",   "/shaders/post/p01_downsample.frag");
        p02ChromaRes   = new PostProcessPass("p02_chroma_res",   "/shaders/post/p02_chroma_res.frag");
        p03ColorMatrix = new PostProcessPass("p03_color_matrix", "/shaders/post/p03_color_matrix.frag");
        p04DynamicRange= new PostProcessPass("p04_dynamic_range","/shaders/post/p04_dynamic_range.frag");
        p07CcdNoise    = new PostProcessPass("p07_ccd_noise",    "/shaders/post/p07_ccd_noise.frag");
        p08CcdSmear    = new PostProcessPass("p08_ccd_smear",    "/shaders/post/p08_ccd_smear.frag");
        p09ChromaBleed = new PostProcessPass("p09_chroma_bleed", "/shaders/post/p09_chroma_bleed.frag");
        p10Timebase    = new PostProcessPass("p10_timebase",     "/shaders/post/p10_timebase.frag");
        p11DotCrawl    = new PostProcessPass("p11_dot_crawl",    "/shaders/post/p11_dot_crawl.frag");
        p12Dropout     = new PostProcessPass("p12_dropout",      "/shaders/post/p12_dropout.frag");
        p13HeadSwitch  = new PostProcessPass("p13_headswitch",   "/shaders/post/p13_headswitch.frag");
        p14Tracking    = new PostProcessPass("p14_tracking",     "/shaders/post/p14_tracking.frag");
        p15EdgeEnhance = new PostProcessPass("p15_edge_enhance", "/shaders/post/p15_edge_enhance.frag");
        p16Interlace   = new PostProcessPass("p16_interlace",    "/shaders/post/p16_interlace.frag");

        atrous = new AtrousDenoiser(fullscreenVao);
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
    public int runOnAccum(int accumTexId, int gBufferTexId, int totalSamples, float exposure,
                      RenderSettings s, TemporalState ts) {
        blit(p00Normalize, accumTexId, normalizeBuffer, sh -> {
            sh.setInt("u_totalSamples", Math.max(totalSamples, 1));
            sh.setFloat("u_exposure", exposure);
        });
        return runChain(normalizeBuffer.textureId(), gBufferTexId, totalSamples, s, ts);
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
        blit(p00Normalize, imageTexId, normalizeBuffer, sh -> {
            sh.setInt("u_totalSamples", 1);
            sh.setFloat("u_exposure", 1.0f);
        });
        return runChain(normalizeBuffer.textureId(), 0, 1, s, ts);
    }

    // ── Internal chain ─────────────────────────────────────────────────────────

    private int runChain(int current, int gBufferTexId, int totalSamples, RenderSettings s, TemporalState ts) {

        // À-trous denoiser: runs before VHS chain so effects layer on clean image.
        // Auto-bypasses above atrousMaxSpp threshold (0 = always active).
        boolean sppExceeded = s.atrousMaxSpp > 0 && totalSamples > s.atrousMaxSpp;
        if (s.atrousEnabled && gBufferTexId != 0 && !sppExceeded) {
            current = atrous.denoise(current, gBufferTexId, s);
        }

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

        // p03 – Colour temperature bias, hue rotation, saturation, slow drift
        if (s.p03Enabled) {
            current = swap(p03ColorMatrix, current, sh -> {
                sh.setFloat("u_colorTempBias",    s.colorTempBias);
                sh.setFloat("u_colorHueRot",      s.colorHueRot);
                sh.setFloat("u_colorSaturation",  s.colorSaturation);
                sh.setFloat("u_colorDriftSpeed",  s.colorDriftSpeed);
                sh.setFloat("u_time",             ts.time);
            });
        }

        // p04 – Magnetic tape dynamic range: black lift, shadow crush, highlight knee/clip
        if (s.p04Enabled) {
            current = swap(p04DynamicRange, current, sh -> {
                sh.setFloat("u_blackLift",      s.drBlackLift);
                sh.setFloat("u_shadowCrush",    s.drShadowCrush);
                sh.setFloat("u_highlightKnee",  s.drHighlightKnee);
                sh.setFloat("u_highlightClip",  s.drHighlightClip);
            });
        }

        // p05 – p06: not yet implemented – pass through

        // p07 – CCD sensor noise (luminance-dependent, AGC-scaled, chroma noise)
        if (s.p07Enabled) {
            current = swap(p07CcdNoise, current, sh -> {
                sh.setInt("u_frameIndex",        s.frameIndex);
                sh.setFloat("u_agcGain",          ts.agcGain);
                sh.setFloat("u_lumaNoiseBase",    s.ccdNoiseLumaBase);
                sh.setFloat("u_chromaNoiseScale", s.ccdNoiseChromaScale);
            });
        }

        // p08 – CCD vertical column overflow streak from bright sources
        if (s.p08Enabled) {
            current = swap(p08CcdSmear, current, sh -> {
                sh.setFloat("u_smearThreshold", s.ccdSmearThreshold);
                sh.setFloat("u_smearIntensity", s.ccdSmearIntensity);
                sh.setInt  ("u_smearLength",    s.ccdSmearLength);
            });
        }

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

        // p11 – NTSC dot crawl (chroma-subcarrier luma crosstalk at edges)
        if (s.p11Enabled) {
            current = swap(p11DotCrawl, current, sh -> {
                sh.setFloat("u_intensity",      s.dotCrawlIntensity);
                sh.setFloat("u_subcarrierFreq", s.dotCrawlSubcarrier);
                sh.setFloat("u_edgeThresh",     s.dotCrawlEdgeThresh);
                sh.setInt  ("u_frameIndex",     s.frameIndex);
            });
        }

        // p12 – Magnetic tape dropout (random horizontal bright streaks)
        if (s.p12Enabled) {
            current = swap(p12Dropout, current, sh -> {
                sh.setFloat("u_tapeAge",       s.tapeAge);
                sh.setFloat("u_dropoutBright", s.dropoutBright);
                sh.setInt  ("u_frameIndex",    s.frameIndex);
                sh.setFloat("u_burstRate",     s.dropoutBurstRate);
            });
        }

        // p13 – Head-switch shimmy at bottom scanlines
        if (s.p13Enabled) {
            current = swap(p13HeadSwitch, current, sh -> {
                sh.setInt  ("u_switchLines",  s.headSwitchLines);
                sh.setFloat("u_jitterScale",  s.headSwitchJitter);
                sh.setFloat("u_lumaRipple",   s.headSwitchRipple);
                sh.setFloat("u_chromaRotAmp", s.headSwitchChroma);
                sh.setInt  ("u_frameIndex",   s.frameIndex);
            });
        }

        // p14 – Tracking error bands (horizontal displacement + guard-band noise)
        if (s.p14Enabled) {
            current = swap(p14Tracking, current, sh -> {
                sh.setFloat("u_severity",      s.trackingSeverity);
                sh.setFloat("u_maxDisplacePx", s.trackingMaxDisplacePx);
                sh.setFloat("u_fringeWidthPx", s.trackingFringeWidthPx);
                sh.setInt  ("u_frameIndex",    s.frameIndex);
            });
        }

        if (s.p15Enabled) {
            current = swap(p15EdgeEnhance, current, sh -> {
                sh.setFloat("u_amount",        s.edgeEnhanceAmount);
                sh.setFloat("u_coreThreshold", s.edgeEnhanceCoreThreshold);
            });
        }
        if (s.p16Enabled) {
            current = swap(p16Interlace, current, sh -> {
                sh.setFloat("u_fieldOffsetPx",  s.interlaceFieldOffsetPx);
                sh.setFloat("u_combStrength",   s.interlaceCombStrength);
                sh.setFloat("u_combEdgeThresh", s.interlaceCombEdgeThresh);
                sh.setFloat("u_lineWeighting",  s.interlaceLineWeighting);
            });
        }

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
        p03ColorMatrix.destroy();
        p04DynamicRange.destroy();
        p07CcdNoise.destroy();
        p08CcdSmear.destroy();
        p09ChromaBleed.destroy();
        p10Timebase.destroy();
        p11DotCrawl.destroy();
        p12Dropout.destroy();
        p13HeadSwitch.destroy();
        p14Tracking.destroy();
        p15EdgeEnhance.destroy();
        p16Interlace.destroy();
        atrous.destroy();
    }

    // ── Functional interface ───────────────────────────────────────────────────

    @FunctionalInterface
    private interface UniformSetter {
        void set(ShaderProgram shader);
    }
}