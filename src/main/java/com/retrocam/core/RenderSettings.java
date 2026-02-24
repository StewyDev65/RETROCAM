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
    public boolean sppmEnabled    = true;
    public int   photonsPerIter   = 500_000;
    public float sppmInitRadius   = 0.05f;
    public float sppmAlpha        = 0.7f;

    // ── AGC / temporal (Phase 7) ──────────────────────────────────────────────
    public float agcTargetLum = 0.4f;
    public float agcMaxGain   = 6.0f;
    public float agcSpeed     = 1.5f;       // IIR speed (1/s)
    public float wbSpeed      = 0.33f;      // white balance drift speed (1/s)

    // ── Post-process master toggles (Phase 6) ────────────────────────────────
    // All off by default — enable individually to test each effect.
    // p20 (tonemap) stays on so the display is always correct.
    public boolean p01Enabled = false;  // downsample
    public boolean p02Enabled = false;  // chroma resolution
    public boolean p03Enabled = false;  // color matrix
    public boolean p04Enabled = false;  // dynamic range
    public boolean p05Enabled = false;  // AGC
    public boolean p06Enabled = false;  // halation
    public boolean p07Enabled = false;  // CCD noise
    public boolean p08Enabled = false;  // CCD smear
    public boolean p09Enabled = false;  // chroma bleed
    public boolean p10Enabled = false;  // timebase
    public boolean p11Enabled = false;  // dot crawl
    public boolean p12Enabled = false;  // dropout
    public boolean p13Enabled = false;  // head switch
    public boolean p14Enabled = false;  // tracking
    public boolean p15Enabled = false;  // edge enhance
    public boolean p16Enabled = false;  // interlace
    public boolean p17Enabled = false;  // optics
    public boolean p18Enabled = false;  // lens flare
    public boolean p19Enabled = false;  // scanlines

    // ── Post-process key values (Phase 6) ─────────────────────────────────────
    public float chromaResSigma      = 15.0f;
    public float timebaseAmplitudePx = 0.45f;
    public float dropoutProbability  = 0.15f;
    public float edgeEnhanceAmount   = 1.5f;
    public float vignetteStrength    = 1.4f;
    public float tapeAge             = 0.3f;    // 0=mint, 1=damaged
    public float trackingSeverity    = 0.1f;

    // ── Path tracer display (Phase 3) ─────────────────────────────────────────
    public float exposure = 1.0f;   // EV multiplier before tonemapping

    // ── Phase 6: frame counter (incremented by Main each render frame) ─────────
    // Used by noise/jitter shaders to produce frame-varying randomness.
    public int frameIndex = 0;

    // ── Phase 6: p01 Downsample ───────────────────────────────────────────────
    // Horizontal Gaussian sigma (px) for VHS luma bandwidth limit.
    // At 854 px wide, sigma=1.5 yields ~240 effective horizontal TV lines.
    public float lumaBlurSigma = 0.333f;

    // ── Phase 6: p07 CCD Noise ────────────────────────────────────────────────
    // lumaNoiseBase: floor amplitude for bright pixels.
    // chromaNoiseScale: chroma noise amplitude as a multiplier of luma amplitude.
    public float ccdNoiseLumaBase    = 0.001f;
    public float ccdNoiseChromaScale = 1.05f;

    // ── Phase 6: p09 Chroma Bleed ─────────────────────────────────────────────
    // IIR decay factor per pixel (k).  Higher = longer smear tail.
    // 0.35 ≈ real composite demodulator settling time at VHS resolution.
    public float chromaBleedFactor = 0.75f;

    // ── Phase 6: p10 Timebase ─────────────────────────────────────────────────
    // Spatial frequency (rad/px) and temporal rate (rad/s) of the sinusoidal
    // wobble component.  Noise component uses the same amplitude.
    public float timebaseFreq  = 0.02f;
    public float timebaseSpeed = 0.70f;
}