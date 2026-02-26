package com.retrocam.core;

import com.retrocam.keyframe.Keyframeable;

/**
 * Centralised, mutable settings bean.
 * Every configurable constant from the spec lives here so the ImGui
 * layer and rendering systems share the same values without coupling.
 *
 * New fields are added here and exposed in ImGuiLayer as each phase
 * of the renderer is implemented.
 */
public final class RenderSettings implements Keyframeable {

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
    public float[] lcaDelta      = { 0.000f, 0.000f, 0.000f }; // R G B
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
    public float chromaResSigma      = 7.0f;
    public float timebaseAmplitudePx = 1.5f;
    public float edgeEnhanceAmount   = 1.5f;
    public float vignetteStrength    = 1.4f;
    public float tapeAge             = 0.3f;    // 0=mint, 1=damaged
    public float trackingSeverity    = 0.1f;

    // ── Path tracer display (Phase 3) ─────────────────────────────────────────
    public float exposure = 1.0f;   // EV multiplier before tonemapping
    public boolean neeEnabled        = true;
    public float   neeFireflyClamp   = 1.0f;  // max contribution multiplier per NEE sample

    // ── À-trous spatial denoiser ──────────────────────────────────────────────────
    // atrousEnabled:    master toggle.
    // atrousIterations: number of sparse-kernel passes (1–5). 4 is typical.
    // atrousSigmaColor: colour edge-stopping std-dev (lower = sharper edges).
    // atrousSigmaNormal:normal power exponent (higher = sharper edges).
    // atrousSigmaDepth: relative depth std-dev (lower = sharper depth edges).
    // atrousMaxSpp:     auto-bypass above this sample count (0 = always active).
    public boolean atrousEnabled     = false;
    public int     atrousIterations  = 4;
    public float   atrousSigmaColor  = 1.0f;
    public float   atrousSigmaNormal = 64.0f;
    public float   atrousSigmaDepth  = 0.15f;
    public int     atrousMaxSpp      = 0;       // 0 = always active

    // ── Phase 6: frame counter (incremented by Main each render frame) ─────────
    // Used by noise/jitter shaders to produce frame-varying randomness.
    public int frameIndex = 0;

    // ── Phase 6: p01 Downsample ───────────────────────────────────────────────
    // Horizontal Gaussian sigma (px) for VHS luma bandwidth limit.
    // At 854 px wide, sigma=1.5 yields ~240 effective horizontal TV lines.
    public float lumaBlurSigma = 1.5f;

    // ── Phase 6: p07 CCD Noise ────────────────────────────────────────────────
    // lumaNoiseBase: floor amplitude for bright pixels.
    // chromaNoiseScale: chroma noise amplitude as a multiplier of luma amplitude.
    public float ccdNoiseLumaBase    = 0.015f;
    public float ccdNoiseChromaScale = 1.6f;

    // ── Phase 6: p09 Chroma Bleed ─────────────────────────────────────────────
    // IIR decay factor per pixel (k).  Higher = longer smear tail.
    // 0.35 ≈ real composite demodulator settling time at VHS resolution.
    public float chromaBleedFactor = 0.35f;

    // ── Phase 6: p10 Timebase ─────────────────────────────────────────────────
    // Spatial frequency (rad/px) and temporal rate (rad/s) of the sinusoidal
    // wobble component.  Noise component uses the same amplitude.
    public float timebaseFreq  = 0.02f;
    public float timebaseSpeed = 0.70f;

    // ── Phase 6: p11 Dot Crawl ────────────────────────────────────────────────
    // intensity:       amplitude of I/Q contamination at edges. 0.25 = mild, 1.0 = severe.
    // subcarrierFreq:  normalized cycles/pixel matching VHS chroma BW (~0.14).
    // edgeThresh:      luma gradient magnitude required to trigger dot crawl.
    public float dotCrawlIntensity     = 0.25f;
    public float dotCrawlSubcarrier    = 0.14f;
    public float dotCrawlEdgeThresh    = 0.04f;

    // ── Phase 6: p12 Tape Dropout ─────────────────────────────────────────────
    // tapeAge (already declared above) controls frequency (0=mint, 1=worn).
    // dropoutBright:   peak luma level of the dropout stripe (linear light).
    public float dropoutBright        = 0.92f;

    // dropoutBurstRate: fraction of video fields that carry dropout activity.
    // 0.05 = rare isolated bursts; 0.5 = frequent; 1.0 = every field.
    public float dropoutBurstRate    = 0.45f;

    // ── Phase 6: p03 Color Matrix ─────────────────────────────────────────────
    // colorTempBias:   -1 = cool (blue push), +1 = warm (amber push).
    // colorHueRot:     I/Q phase error in degrees; ±5 is typical VHS drift.
    // colorSaturation: VHS SP desaturates ~5% vs broadcast; default 0.95.
    // colorDriftSpeed: Sinusoidal colour-temp oscillation rate (rad/s).
    //                  Period = 2pi/speed; at 0.05 rad/s -> ~126 s full cycle.
    public float colorTempBias    = 0.0f;
    public float colorHueRot      = 0.0f;
    public float colorSaturation  = 0.95f;
    public float colorDriftSpeed  = 0.05f;

    // ── Phase 6: p04 Dynamic Range ────────────────────────────────────────────
    // blackLift:       IRE 7.5 setup level ~0.03 in linear light.
    // shadowCrush:     Below this level, detail is lost in tape noise floor.
    // highlightKnee:   Onset of magnetic tape B-H saturation compression.
    // highlightClip:   Remanence ceiling — signals are hard-capped here.
    public float drBlackLift     = 0.01f;
    public float drShadowCrush   = 0.03f;
    public float drHighlightKnee = 0.78f;
    public float drHighlightClip = 0.95f;

    // ── Phase 6: p08 CCD Smear ────────────────────────────────────────────────
    // smearThreshold:  Full-well fraction; pixels above this overflow.
    // smearIntensity:  Multiplier on overflow -> streak brightness.
    // smearLength:     Vertical extent in pixels; 80 px ~= consumer 1/3" CCD.
    public float ccdSmearThreshold = 0.85f;
    public float ccdSmearIntensity = 0.009f;
    public int   ccdSmearLength    = 20;

    // ── Phase 6: p13 Head-Switch Noise ───────────────────────────────────────────
    // switchLines:  how many scanlines from the bottom are in the switch zone.
    // jitterScale:  TBE amplitude multiplier at the switch line vs normal TBE.
    // lumaRipple:   peak brightness oscillation amplitude (fraction of full scale).
    // chromaRotAmp: peak hue rotation at switch line (radians; π/8 ≈ 22.5°).
    public int   headSwitchLines    = 12;
    public float headSwitchJitter   = 4.0f;
    public float headSwitchRipple   = 0.10f;
    public float headSwitchChroma   = 0.35f;

    // ── Phase 6: p14 Tracking Error ──────────────────────────────────────────────
    // trackingSeverity (already declared above) controls event probability.
    // trackingMaxDisplacePx: maximum horizontal displacement in pixels.
    public float trackingMaxDisplacePx = 28.0f;
    public float trackingFringeWidthPx = 4.0f;

    // ── Phase 6: p15 Edge Enhancement ────────────────────────────────────────────
    public float edgeEnhanceCoreThreshold = 0.025f;

    // ── Phase 6: p16 Interlace ────────────────────────────────────────────────────
    public float interlaceFieldOffsetPx  = 0.5f;
    public float interlaceCombStrength   = 1.5f;
    public float interlaceCombEdgeThresh = 0.04f;
    public float interlaceLineWeighting  = 0.05f;

    // ── Render Export ─────────────────────────────────────────────────────────────
    public String exportOutputPath      = "output";
    public int    exportFormatIndex     = 0;
    public int    exportSamplesPerFrame = 128;
    public int    exportJpegQuality     = 90;
    public float  exportDurationSec     = 5.0f;
    public float  exportFps             = 29.97f;

    // ── HFCS Motion Import ────────────────────────────────────────────────────────
    public boolean freeCamActive         = false;   // true = FreeCamera drives render
    public boolean freeCamApplyZoom      = true;    // apply HFCS zoom to focal length
    public float   freeCamPositionScale  = 0.02f;   // HitFilm px → scene units
    public float   freeCamPlaybackSpeed  = 1.0f;    // 1.0 = real-time, 0.5 = half speed
    public float   freeCamStartTime      = 0.0f;    // seconds offset into the animation

    // ── Keyframeable ──────────────────────────────────────────────────────────────

    private static final String[] KF_NAMES = {
        "lens.focalLength", "lens.aperture", "lens.focusDist", "lens.saStrength",
        "post.exposure", "post.saturation", "post.colorTemp", "post.tapeAge"
    };
    private static final String[] KF_DISPLAY = {
        "Focal Length (mm)", "Aperture (f/stop)", "Focus Distance (m)", "Spherical Aberration",
        "Exposure", "Saturation", "Color Temp Bias", "Tape Age"
    };

    @Override public String[] getKeyframeablePropertyNames()        { return KF_NAMES; }
    @Override public String[] getKeyframeablePropertyDisplayNames() { return KF_DISPLAY; }

    @Override
    public float getKeyframeableProperty(String name) {
        return switch (name) {
            case "lens.focalLength" -> focalLengthMm;
            case "lens.aperture"    -> apertureFStop;
            case "lens.focusDist"   -> focusDistM;
            case "lens.saStrength"  -> saStrength;
            case "post.exposure"    -> exposure;
            case "post.saturation"  -> colorSaturation;
            case "post.colorTemp"   -> colorTempBias;
            case "post.tapeAge"     -> tapeAge;
            default -> 0f;
        };
    }

    @Override
    public void setKeyframeableProperty(String name, float value) {
        switch (name) {
            case "lens.focalLength" -> focalLengthMm   = Math.max(1f, value);
            case "lens.aperture"    -> apertureFStop    = Math.max(0.7f, value);
            case "lens.focusDist"   -> focusDistM       = Math.max(0.1f, value);
            case "lens.saStrength"  -> saStrength       = value;
            case "post.exposure"    -> exposure         = Math.max(0f, value);
            case "post.saturation"  -> colorSaturation  = Math.max(0f, value);
            case "post.colorTemp"   -> colorTempBias    = value;
            case "post.tapeAge"     -> tapeAge          = Math.max(0f, Math.min(1f, value));
        }
    }
}