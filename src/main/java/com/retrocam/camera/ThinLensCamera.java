package com.retrocam.camera;

import com.retrocam.core.RenderSettings;
import com.retrocam.gl.ShaderProgram;

/**
 * Thin-lens camera model.
 *
 * Derives physical lens parameters from the settings bean and uploads
 * them to any shader that implements the pathtrace camera model:
 *   u_camPos, u_camRight, u_camUp, u_camForward
 *   u_focalDist, u_lensRadius, u_apertureBlades
 *   u_saStrength (Phase 4 — uploaded as 0 in Phase 3)
 *   u_lcaDelta   (Phase 4 — uploaded as zeroes in Phase 3)
 *
 * Lens radius is derived from focal length and f-stop:
 *   lensRadius = (focalLengthMm / 1000) / (2 * fStop)
 * The result is in metres (world-space units).
 */
public final class ThinLensCamera {

    /** Upload all camera uniforms to {@code shader} (must be bound). */
    public void uploadTo(ShaderProgram shader, OrbitCamera orbit, RenderSettings s) {
        float[] eye     = orbit.getEyePosition();
        float[] right   = orbit.getCameraRight();
        float[] up      = orbit.getCameraUp();
        float[] forward = orbit.getCameraForward();

        shader.setFloat3("u_camPos",     eye[0],     eye[1],     eye[2]);
        shader.setFloat3("u_camRight",   right[0],   right[1],   right[2]);
        shader.setFloat3("u_camUp",      up[0],      up[1],      up[2]);
        shader.setFloat3("u_camForward", forward[0], forward[1], forward[2]);

        // Convert focal length mm → metres, then compute lens radius
        float focalM     = s.focalLengthMm / 1000.0f;
        float lensRadius = focalM / (2.0f * s.apertureFStop);

        shader.setFloat("u_focalDist",       s.focusDistM);
        shader.setFloat("u_lensRadius",      lensRadius);
        shader.setFloat("u_fovTanHalf",      tanHalfFov(s));
        shader.setInt  ("u_apertureBlades",  s.aperatureBlades);

        // Phase 4 stubs — set to neutral values so the shader compiles correctly
        shader.setFloat("u_saStrength", 0.0f);
        shader.setFloat3("u_lcaDelta",  0.0f, 0.0f, 0.0f);
    }

    /**
     * tan(fovY/2) used in the shader to construct view rays from pixel coordinates.
     * We derive a vertical FOV from the focal length using the 35mm full-frame
     * sensor height of 24mm:
     *   fovY = 2 * atan(sensorHalfHeight / focalLength)
     */
    public static float tanHalfFov(RenderSettings s) {
        float sensorHalfH = 0.012f; // 24mm sensor / 2, in metres
        float focalM = s.focalLengthMm / 1000.0f;
        return (float) Math.tan(Math.atan(sensorHalfH / focalM));
    }
}