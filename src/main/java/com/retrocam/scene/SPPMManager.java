package com.retrocam.scene;

import com.retrocam.camera.OrbitCamera;
import com.retrocam.camera.ThinLensCamera;
import com.retrocam.core.RenderSettings;
import com.retrocam.gl.ShaderProgram;

import static org.lwjgl.opengl.GL43.*;

/**
 * Stochastic Progressive Photon Mapping (SPPM) manager.
 *
 * Each frame the manager runs two compute passes:
 *   1. {@link #tracePhotons}  — photon_trace.comp  : emit photons from lights,
 *      trace specular bounces, store at first diffuse hit in a GPU spatial hash.
 *   2. {@link #gatherRadiance} — photon_gather.comp : for each pixel, cast a
 *      pinhole ray to the first diffuse hit, accumulate nearby photons within
 *      the current search radius, and add the caustic radiance to the
 *      existing HDR accumulation buffer.
 *
 * After gathering, call {@link #updateRadius} to shrink the search radius
 * following the SPPM formula:
 *   r_{n+1} = r_n × sqrt( (n + α) / (n + 1) )
 *
 * SSBO binding map (PhotonMap manages 4–7; SceneUploader manages 0–3):
 *   0 — Triangles  (SceneUploader)
 *   1 — BVH nodes  (SceneUploader)
 *   2 — Materials  (SceneUploader)
 *   3 — Lights     (SceneUploader)
 *   4 — Photons    (PhotonMap)
 *   5 — Hash heads (PhotonMap)
 *   6 — Photon next(PhotonMap)
 *   7 — Counter    (PhotonMap)
 */
public final class SPPMManager {

    private static final int RENDER_W = RenderSettings.RENDER_WIDTH;
    private static final int RENDER_H = RenderSettings.RENDER_HEIGHT;

    private final ShaderProgram photonTraceShader;
    private final ShaderProgram photonGatherShader;
    private final PhotonMap     photonMap;

    private float searchRadius;
    private int   iteration = 0;

    // ── Construction ──────────────────────────────────────────────────────────

    public SPPMManager(RenderSettings settings) {
        photonTraceShader  = ShaderProgram.createCompute("/shaders/photon_trace.comp");
        photonGatherShader = ShaderProgram.createCompute("/shaders/photon_gather.comp");
        photonMap          = new PhotonMap();
        searchRadius       = settings.sppmInitRadius;
        System.out.println("[SPPMManager] Ready.");
    }

    // ── Pass 1: photon trace ──────────────────────────────────────────────────

    /**
     * Emits {@code settings.photonsPerIter} photons from all area lights,
     * traces them through specular surfaces, and stores each photon at its
     * first diffuse hit inside the GPU spatial hash.
     *
     * @param sceneUploader provides triangle, BVH, material, and light SSBOs.
     * @param settings      current render settings.
     */
    public void tracePhotons(SceneUploader sceneUploader, RenderSettings settings) {
        // Reset the hash table and counter before writing new photons.
        photonMap.clear();

        photonTraceShader.bind();
        sceneUploader.bind();
        photonMap.bind();

        int photonCount = Math.min(settings.photonsPerIter, PhotonMap.MAX_PHOTONS);

        photonTraceShader.setInt  ("u_photonCount",   photonCount);
        photonTraceShader.setFloat("u_searchRadius",  searchRadius);
        photonTraceShader.setInt  ("u_seed",          iteration * 7919 + 6271);
        photonTraceShader.setInt  ("u_hashSize",      PhotonMap.HASH_SIZE);
        photonTraceShader.setInt  ("u_lightCount",    sceneUploader.getLightCount());
        photonTraceShader.setFloat("u_totalLightPower", sceneUploader.getTotalLightPower());

        // 64 threads per group — dispatch enough to cover all photons.
        int groups = (photonCount + 63) / 64;
        glDispatchCompute(groups, 1, 1);
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);

        photonMap.unbind();
        sceneUploader.unbind();
        photonTraceShader.unbind();
    }

    // ── Pass 2: radiance gather ───────────────────────────────────────────────

    /**
     * For each render pixel, casts a pinhole ray to find the first diffuse
     * surface, queries the photon spatial hash for photons within
     * {@code searchRadius}, and accumulates the caustic radiance contribution
     * into {@code accumTexture}.
     *
     * @param sceneUploader   scene SSBOs.
     * @param camera          orbit camera — supplies pinhole ray parameters.
     * @param accumTexture    GL texture ID of the HDR accumulation buffer
     *                        (image2D binding 0, GL_RGBA32F, READ_WRITE).
     * @param settings        current render settings.
     * @param frameIndex      current accumulation frame counter (for RNG seed).
     */
    public void gatherRadiance(SceneUploader sceneUploader, OrbitCamera camera,
                               int accumTexture, RenderSettings settings, int frameIndex) {
        photonGatherShader.bind();

        // Accumulation texture — same binding 0 image2D used by pathtrace.comp.
        glBindImageTexture(0, accumTexture, 0, false, 0, GL_READ_WRITE, GL_RGBA32F);

        sceneUploader.bind();
        photonMap.bind();

        // Camera (pinhole — no DoF in gather to keep caustics sharp).
        float[] eye     = camera.getEyePosition();
        float[] right   = camera.getCameraRight();
        float[] up      = camera.getCameraUp();
        float[] forward = camera.getCameraForward();

        photonGatherShader.setFloat3("u_camPos",     eye[0],     eye[1],     eye[2]);
        photonGatherShader.setFloat3("u_camRight",   right[0],   right[1],   right[2]);
        photonGatherShader.setFloat3("u_camUp",      up[0],      up[1],      up[2]);
        photonGatherShader.setFloat3("u_camForward", forward[0], forward[1], forward[2]);
        photonGatherShader.setFloat ("u_fovTanHalf", ThinLensCamera.tanHalfFov(settings));
        photonGatherShader.setFloat ("u_aspect",
                                     (float) RENDER_W / RENDER_H);

        glUniform2i(glGetUniformLocation(photonGatherShader.id(), "u_resolution"),
                    RENDER_W, RENDER_H);

        photonGatherShader.setFloat("u_searchRadius",    searchRadius);
        photonGatherShader.setInt  ("u_photonCount",
                                    Math.min(settings.photonsPerIter, PhotonMap.MAX_PHOTONS));
        photonGatherShader.setInt  ("u_hashSize",        PhotonMap.HASH_SIZE);
        photonGatherShader.setInt  ("u_frameIndex",      frameIndex);

        int gx = (RENDER_W + 15) / 16;
        int gy = (RENDER_H + 15) / 16;
        glDispatchCompute(gx, gy, 1);
        glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT
                      | GL_TEXTURE_FETCH_BARRIER_BIT);

        photonMap.unbind();
        sceneUploader.unbind();
        photonGatherShader.unbind();
    }

    // ── Radius schedule ───────────────────────────────────────────────────────

    /**
     * Advances the iteration counter and shrinks the search radius:
     *   r_{n+1} = r_n × sqrt( (n + α) / (n + 1) )
     *
     * @param alpha SPPM alpha parameter (typically 0.7).
     */
    public void updateRadius(float alpha) {
        searchRadius *= (float) Math.sqrt((iteration + alpha) / (iteration + 1.0));
        iteration++;
    }

    // ── Reset ─────────────────────────────────────────────────────────────────

    /**
     * Resets the iteration counter and search radius to the initial value.
     * Should be called whenever the renderer accumulation buffer is cleared
     * (e.g., camera move, optical settings change).
     */
    public void reset(RenderSettings settings) {
        searchRadius = settings.sppmInitRadius;
        iteration    = 0;
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public float getSearchRadius() { return searchRadius; }
    public int   getIteration()    { return iteration;    }

    // ── Cleanup ───────────────────────────────────────────────────────────────

    public void destroy() {
        photonTraceShader .destroy();
        photonGatherShader.destroy();
        photonMap         .destroy();
    }
}