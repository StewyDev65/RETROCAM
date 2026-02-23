package com.retrocam.core;

import com.retrocam.gl.ShaderProgram;

/**
 * Per-frame temporal state for all time-varying camera/electronics effects.
 * Each field is an IIR-filtered value that tracks a target with a configurable
 * time constant. Values are uploaded as uniforms to every shader that needs them.
 *
 * Phase 3: agcGain and focalDist are wired. whiteBalance is identity for now.
 * Phase 7 will wire AGC analysis feedback from p05 and WB from the scene hue.
 */
public final class TemporalState {

    // ── Current (filtered) values ─────────────────────────────────────────────
    public float agcGain    = 1.0f;
    public float focalDist  = 2.0f;    // synced from ThinLensCamera each frame
    public float time       = 0.0f;    // seconds elapsed

    // White balance: 3×3 matrix, identity by default (row-major, uploaded col-major)
    public float[] whiteBalance = {
        1,0,0,
        0,1,0,
        0,0,1
    };

    // ── IIR targets (set externally each frame) ───────────────────────────────
    private float agcGainTarget   = 1.0f;
    private float focalDistTarget = 2.0f;

    // ── Update ────────────────────────────────────────────────────────────────

    /**
     * Advances IIR filters by {@code dt} seconds using settings constants.
     * Call once per frame before uploading uniforms.
     */
    public void update(float dt, RenderSettings settings) {
        time += dt;

        // AGC IIR  (Phase 7 will set agcGainTarget from scene luminance)
        float agcAlpha = 1.0f - (float) Math.exp(-settings.agcSpeed * dt);
        agcGain += (agcGainTarget - agcGain) * agcAlpha;
        agcGain  = Math.max(1.0f, Math.min(settings.agcMaxGain, agcGain));

        // AF IIR
        float afAlpha = 1.0f - (float) Math.exp(-settings.afSpeed * dt);
        focalDist += (focalDistTarget - focalDist) * afAlpha;
    }

    // ── Target setters (called from Main each frame) ──────────────────────────

    public void setAgcGainTarget(float target)   { this.agcGainTarget   = target; }
    public void setFocalDistTarget(float target) { this.focalDistTarget = target; }

    // ── Uniform upload ────────────────────────────────────────────────────────

    /**
     * Uploads all temporal uniforms to the given bound shader program.
     * The shader must declare these uniforms (common.glsl documents the names).
     */
    public void uploadTo(ShaderProgram shader, RenderSettings settings) {
        shader.setFloat("u_agcGain",  agcGain);
        shader.setFloat("u_time",     time);
        shader.setFloat("u_tapeAge",  settings.tapeAge);
        shader.setMat3("u_whiteBalance", whiteBalance);
    }
}