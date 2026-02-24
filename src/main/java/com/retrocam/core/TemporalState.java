package com.retrocam.core;

import com.retrocam.gl.ShaderProgram;

/**
 * Per-frame temporal state for all time-varying camera/electronics effects.
 *
 * Phase 4 additions:
 *   Auto-focus hunting noise — a small multi-frequency sinusoidal oscillation
 *   added on top of the IIR convergence to simulate the micro-hunting behaviour
 *   of a real camcorder's contrast-detection autofocus servo.
 */
public final class TemporalState {

    // ── Current (IIR-filtered) values ─────────────────────────────────────────
    public float agcGain   = 1.0f;
    public float focalDist = 2.0f;    // IIR-filtered, used directly by ThinLensCamera
    public float time      = 0.0f;    // seconds elapsed

    // White balance: 3×3 matrix, identity by default (row-major, uploaded col-major)
    public float[] whiteBalance = {
        1, 0, 0,
        0, 1, 0,
        0, 0, 1
    };

    // ── IIR targets (set externally each frame) ───────────────────────────────
    private float agcGainTarget   = 1.0f;
    private float focalDistTarget = 2.0f;

    // ── Update ────────────────────────────────────────────────────────────────

    /**
     * Advances IIR filters by {@code dt} seconds.
     *
     * Auto-focus hunting:
     *   Real contrast-detection AF slightly overshoots and oscillates around
     *   the target before locking. We approximate this with a pair of
     *   low-amplitude, slowly evolving sinusoids whose combined output stays
     *   within ±~0.01 m of the target. The amplitude scales with the IIR alpha
     *   so hunting is most visible during an active focus pull and dies away
     *   when the focal distance has settled.
     */
    public void update(float dt, RenderSettings settings) {
        time += dt;

        // ── AGC IIR ───────────────────────────────────────────────────────────
        float agcAlpha = 1.0f - (float) Math.exp(-settings.agcSpeed * dt);
        agcGain += (agcGainTarget - agcGain) * agcAlpha;
        agcGain  = Math.max(1.0f, Math.min(settings.agcMaxGain, agcGain));

        // ── Auto-Focus IIR + hunting noise ────────────────────────────────────
        float afAlpha = 1.0f - (float) Math.exp(-settings.afSpeed * dt);
        focalDist += (focalDistTarget - focalDist) * afAlpha;

        // Hunting oscillation: two incommensurate frequencies so the combined
        // waveform never perfectly repeats. Amplitude of ~0.01 m, scaled by
        // afAlpha so it dies down when the servo has settled.
        float hunt = (float)(
            Math.sin(time * 7.31)  * 0.006
          + Math.sin(time * 13.77) * 0.004
        ) * afAlpha;
        focalDist = Math.max(0.1f, focalDist + hunt);
    }

    // ── Target setters (called by Main each frame) ────────────────────────────

    public void setAgcGainTarget(float target)   { this.agcGainTarget   = target; }
    public void setFocalDistTarget(float target) { this.focalDistTarget = target; }

    // ── Uniform upload ────────────────────────────────────────────────────────

    /**
     * Uploads temporal uniforms to the given bound shader.
     * Note: u_focalDist is NOT uploaded here — ThinLensCamera.uploadTo() reads
     * {@link #focalDist} directly so the IIR value is used for ray generation.
     */
    public void uploadTo(ShaderProgram shader, RenderSettings settings) {
        shader.setFloat("u_agcGain", agcGain);
        shader.setFloat("u_time",    time);
        shader.setFloat("u_tapeAge", settings.tapeAge);
        shader.setMat3("u_whiteBalance", whiteBalance);
    }
}