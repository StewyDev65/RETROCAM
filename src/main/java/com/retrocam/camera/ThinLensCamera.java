package com.retrocam.camera;

import com.retrocam.core.RenderSettings;
import com.retrocam.core.TemporalState;
import com.retrocam.gl.ShaderProgram;

/**
 * Thin-lens camera model.
 *
 * Derives physical lens parameters from the settings bean and uploads
 * them to any shader that implements the pathtrace camera model.
 *
 * Phase 4 additions:
 *   u_saStrength — spherical aberration strength (radial focal shift)
 *   u_lcaDelta   — per-channel focal offsets for longitudinal CA (R, G, B)
 *   u_focalDist  — now reads from TemporalState.focalDist (IIR-filtered) so
 *                  auto-focus lag is applied physically in the lens model
 */
public final class ThinLensCamera {

    /**
     * Upload all camera uniforms to {@code shader} (must be bound).
     *
     * @param temporal supplies the IIR-filtered focal distance and other
     *                 per-frame state so auto-focus lag affects ray generation.
     */
    public void uploadTo(ShaderProgram shader, CameraView orbit,
                     RenderSettings s, TemporalState temporal) {
        float[] eye     = orbit.getEyePosition();
        float[] right   = orbit.getCameraRight();
        float[] up      = orbit.getCameraUp();
        float[] forward = orbit.getCameraForward();

        shader.setFloat3("u_camPos",     eye[0],     eye[1],     eye[2]);
        shader.setFloat3("u_camRight",   right[0],   right[1],   right[2]);
        shader.setFloat3("u_camUp",      up[0],      up[1],      up[2]);
        shader.setFloat3("u_camForward", forward[0], forward[1], forward[2]);

        // Convert focal length mm → metres, then compute lens radius from f-stop.
        float focalM     = s.focalLengthMm / 1000.0f;
        float lensRadius = focalM / (2.0f * s.apertureFStop);

        // Use the IIR-filtered focal distance from TemporalState so auto-focus
        // lag (R4) is correctly applied in the shader ray generator.
        shader.setFloat("u_focalDist",       temporal.focalDist);
        shader.setFloat("u_lensRadius",      lensRadius);
        shader.setFloat("u_fovTanHalf",      tanHalfFov(s));
        shader.setInt  ("u_apertureBlades",  s.aperatureBlades);

        // ── Phase 4 optical effects ────────────────────────────────────────────
        // Spherical aberration: edge rays focus at focalDist + saStrength.
        shader.setFloat("u_saStrength", s.saStrength);

        // Longitudinal CA: per-channel focal offsets in metres.
        // Negative for R (near focus), zero for G, positive for B (far focus).
        shader.setFloat3("u_lcaDelta",
            s.lcaDelta[0], s.lcaDelta[1], s.lcaDelta[2]);
    }

    /**
     * tan(fovY/2) used in the shader to construct view rays from NDC coordinates.
     * Derived from focal length assuming a 35mm full-frame sensor (24mm height).
     */
    public static float tanHalfFov(RenderSettings s) {
        float sensorHalfH = 0.012f; // 24mm sensor / 2, in metres
        float focalM = s.focalLengthMm / 1000.0f;
        return (float) Math.tan(Math.atan(sensorHalfH / focalM));
    }
}