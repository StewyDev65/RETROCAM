package com.retrocam.post;

import com.retrocam.core.RenderSettings;
import com.retrocam.gl.Framebuffer;
import com.retrocam.gl.ShaderProgram;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * À-trous (hole-punched) wavelet edge-stopping spatial denoiser.
 *
 * <p>Runs up to 5 sparse-kernel passes over the normalised HDR colour buffer
 * using the G-buffer (world-space normal + primary-hit depth) as edge-stopping
 * weights.  Each pass doubles the step width (1→2→4→8→16), giving a wide
 * effective filter radius with only 25 texture fetches per pass.</p>
 *
 * <p>Based on: Dammertz et al. "Edge-Avoiding À-Trous Wavelet Transform for
 * fast Global Illumination Filtering", HPG 2010.</p>
 *
 * <p>This is applied after {@code p00_normalize} (linear HDR) and before the
 * VHS post-process chain, so VHS artefacts are layered on top of a clean
 * denoised image rather than on top of Monte-Carlo noise.</p>
 */
public final class AtrousDenoiser {

    private final ShaderProgram shader;
    private final Framebuffer   bufA;
    private final Framebuffer   bufB;
    private final int           vao;

    private static final float TEX_W = RenderSettings.RENDER_WIDTH;
    private static final float TEX_H = RenderSettings.RENDER_HEIGHT;

    // ── Construction ──────────────────────────────────────────────────────────

    public AtrousDenoiser(int fullscreenVao) {
        this.vao = fullscreenVao;
        int W = RenderSettings.RENDER_WIDTH;
        int H = RenderSettings.RENDER_HEIGHT;
        shader = ShaderProgram.createRender(
            "/shaders/fullscreen.vert",
            "/shaders/post/atrous_denoise.frag");
        bufA = new Framebuffer(W, H, GL_RGBA32F);
        bufB = new Framebuffer(W, H, GL_RGBA32F);
        System.out.println("[AtrousDenoiser] Ready.");
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Run the à-trous filter chain and return the denoised texture ID.
     *
     * @param colorTexId   normalised HDR colour (output of p00_normalize)
     * @param gBufferTexId G-buffer texture (rgba16f: normal.xyz, hit-depth)
     * @param s            current render settings
     * @return GL texture ID of the denoised result
     */
    public int denoise(int colorTexId, int gBufferTexId, RenderSettings s) {
        int   numPasses = Math.max(1, Math.min(s.atrousIterations, 5));
        int   readTex   = colorTexId;
        Framebuffer write = bufA;
        Framebuffer other = bufB;

        shader.bind();
        shader.setFloat2("u_texelSize", 1.0f / TEX_W, 1.0f / TEX_H);
        shader.setFloat ("u_sigmaColor",  s.atrousSigmaColor);
        shader.setFloat ("u_sigmaNormal", s.atrousSigmaNormal);
        shader.setFloat ("u_sigmaDepth",  s.atrousSigmaDepth);

        // Bind G-buffer once — it does not change between passes
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, gBufferTexId);
        shader.setInt("u_gBuffer", 1);

        for (int i = 0; i < numPasses; i++) {
            shader.setInt("u_stepWidth", 1 << i);   // 1, 2, 4, 8, 16

            write.bindForWrite();

            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, readTex);
            shader.setInt("u_tex", 0);

            glBindVertexArray(vao);
            glDrawArrays(GL_TRIANGLES, 0, 3);
            glBindVertexArray(0);

            write.unbind();

            readTex = write.textureId();
            // Ping-pong: swap write and other
            Framebuffer tmp = write;
            write = other;
            other = tmp;
        }

        shader.unbind();

        // Clean up texture unit 1
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, 0);
        glActiveTexture(GL_TEXTURE0);

        return readTex;
    }

    // ── Cleanup ───────────────────────────────────────────────────────────────

    public void destroy() {
        shader.destroy();
        bufA.destroy();
        bufB.destroy();
    }
}