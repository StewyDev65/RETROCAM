package com.retrocam.core;

/**
 * Centralised, mutable settings bean.
 * Every configurable constant from the spec lives here so the ImGui
 * layer and rendering systems share the same values without coupling.
 *
 * New fields are added here and exposed in ImGuiLayer as each phase
 * of the renderer is implemented.
 */
public final class RenderSettings {

    // ── Window / display ──────────────────────────────────────────────────────
    public boolean vSync          = true;
    public int     displayWidth   = 1280;
    public int     displayHeight  = 720;

    // ── Render resolution (fixed VHS SD) ──────────────────────────────────────
    public static final int RENDER_WIDTH  = 854;
    public static final int RENDER_HEIGHT = 480;
    public static final float FRAME_RATE  = 29.97f;

    // ── Thin-lens camera (Phase 3/4) ──────────────────────────────────────────
    public float focalLengthMm   = 35.0f;   // 35 mm-equivalent
    public float apertureFStop   = 2.0f;    // f/2.0
    public float focusDistM      = 2.0f;    // metres
    public int   aperatureBlades = 5;       // pentagon bokeh
    public float saStrength      = 0.04f;   // spherical aberration
    public float[] lcaDelta      = { -0.002f, 0.000f, 0.003f }; // R G B
    public float afSpeed         = 3.0f;    // auto-focus IIR speed (1/s)

    // ── SPPM (Phase 5) ────────────────────────────────────────────────────────
    public int   photonsPerIter   = 500_000;
    public float sppmInitRadius   = 0.05f;
    public float sppmAlpha        = 0.7f;

    // ── AGC / temporal (Phase 7) ──────────────────────────────────────────────
    public float agcTargetLum = 0.4f;
    public float agcMaxGain   = 6.0f;
    public float agcSpeed     = 1.5f;       // IIR speed (1/s)
    public float wbSpeed      = 0.33f;      // white balance drift speed (1/s)

    // ── Post-process master toggles (Phase 6) ────────────────────────────────
    public boolean p01Enabled = true;   // downsample
    public boolean p02Enabled = true;   // chroma resolution
    public boolean p03Enabled = true;   // color matrix
    public boolean p04Enabled = true;   // dynamic range
    public boolean p05Enabled = true;   // AGC
    public boolean p06Enabled = true;   // halation
    public boolean p07Enabled = true;   // CCD noise
    public boolean p08Enabled = true;   // CCD smear
    public boolean p09Enabled = true;   // chroma bleed
    public boolean p10Enabled = true;   // timebase
    public boolean p11Enabled = true;   // dot crawl
    public boolean p12Enabled = true;   // dropout
    public boolean p13Enabled = true;   // head switch
    public boolean p14Enabled = true;   // tracking
    public boolean p15Enabled = true;   // edge enhance
    public boolean p16Enabled = true;   // interlace
    public boolean p17Enabled = true;   // optics
    public boolean p18Enabled = true;   // lens flare
    public boolean p19Enabled = true;   // scanlines
    public boolean p20Enabled = true;   // tonemap

    // ── Post-process key values (Phase 6) ─────────────────────────────────────
    public float chromaResSigma      = 7.0f;
    public float timbaseAmplitudePx  = 1.5f;
    public float dropoutProbability  = 0.15f;
    public float edgeEnhanceAmount   = 1.5f;
    public float vignetteStrength    = 1.4f;
    public float tapeAge             = 0.3f;    // 0=mint, 1=damaged
    public float trackingSeverity    = 0.1f;
}
