package com.retrocam.core;

import imgui.ImGui;
import imgui.ImGuiIO;
import imgui.flag.ImGuiConfigFlags;
import imgui.flag.ImGuiWindowFlags;
import imgui.gl3.ImGuiImplGl3;
import imgui.glfw.ImGuiImplGlfw;
import imgui.type.ImBoolean;

/**
 * Owns the ImGui context and renders the RetroCam settings panel.
 *
 * Phase 4 additions (Camera & Optics section):
 *   LCA Red delta   — focal shift for the red channel (negative = nearer focus)
 *   LCA Blue delta  — focal shift for the blue channel (positive = farther focus)
 *   Green is treated as the reference channel (delta = 0) and is shown read-only.
 */
public final class ImGuiLayer {

    private final ImGuiImplGlfw imGuiGlfw = new ImGuiImplGlfw();
    private final ImGuiImplGl3  imGuiGl3  = new ImGuiImplGl3();

    private final ImBoolean boolBuf  = new ImBoolean();
    private final float[]   floatBuf = new float[1];
    private final int[]     intBuf   = new int[1];

    private int   iterationCount;
    private float samplesPerSecond;
    private float agcGain;
    private float sppmSearchRadius;
    private int   sppmIteration;

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    public void init(long windowHandle) {
        ImGui.createContext();
        ImGuiIO io = ImGui.getIO();
        io.addConfigFlags(ImGuiConfigFlags.NavEnableKeyboard);

        imGuiGlfw.init(windowHandle, true);
        imGuiGl3.init("#version 430");
    }

    public void destroy() {
        imGuiGl3.dispose();
        imGuiGlfw.dispose();
        ImGui.destroyContext();
    }

    // ── Per-frame ─────────────────────────────────────────────────────────────

    public void beginFrame() {
        imGuiGlfw.newFrame();
        ImGui.newFrame();
    }

    public void render(RenderSettings settings) {
        renderStatsOverlay();
        renderMainPanel(settings);
    }

    public void endFrame() {
        ImGui.render();
        imGuiGl3.renderDrawData(ImGui.getDrawData());
    }

    // ── Stats overlay ─────────────────────────────────────────────────────────

    private void renderStatsOverlay() {
        int flags = ImGuiWindowFlags.NoDecoration
                  | ImGuiWindowFlags.AlwaysAutoResize
                  | ImGuiWindowFlags.NoBackground
                  | ImGuiWindowFlags.NoFocusOnAppearing
                  | ImGuiWindowFlags.NoNav
                  | ImGuiWindowFlags.NoMove;

        ImGui.setNextWindowPos(8, 8);
        if (ImGui.begin("##stats", new ImBoolean(true), flags)) {
            ImGui.text(String.format("Iterations : %d", iterationCount));
            ImGui.text(String.format("Samples/s  : %.0f", samplesPerSecond));
            ImGui.text(String.format("AGC Gain   : %.2fx", agcGain));
        }
        ImGui.end();
    }

    // ── Main settings panel ───────────────────────────────────────────────────

    private void renderMainPanel(RenderSettings s) {
        ImGui.setNextWindowSize(340, 0);
        ImGui.setNextWindowPos(8, 70, imgui.flag.ImGuiCond.Once);

        if (!ImGui.begin("RetroCam Settings")) { ImGui.end(); return; }

        // ── Display ───────────────────────────────────────────────────────────
        if (ImGui.collapsingHeader("Display")) {
            boolBuf.set(s.vSync);
            if (ImGui.checkbox("VSync", boolBuf)) s.vSync = boolBuf.get();

            floatBuf[0] = s.exposure;
            if (ImGui.sliderFloat("Exposure", floatBuf, 0.1f, 8.0f))
                s.exposure = floatBuf[0];

            ImGui.text(String.format("Render res : %dx%d @ %.2f fps",
                RenderSettings.RENDER_WIDTH,
                RenderSettings.RENDER_HEIGHT,
                RenderSettings.FRAME_RATE));
        }

        // ── Camera / Optics ───────────────────────────────────────────────────
        if (ImGui.collapsingHeader("Camera & Optics")) {
            floatBuf[0] = s.focalLengthMm;
            if (ImGui.sliderFloat("Focal Length (mm)", floatBuf, 10f, 120f))
                s.focalLengthMm = floatBuf[0];

            floatBuf[0] = s.apertureFStop;
            if (ImGui.sliderFloat("Aperture f/stop", floatBuf, 0.2f, 22.0f))
                s.apertureFStop = floatBuf[0];

            floatBuf[0] = s.focusDistM;
            if (ImGui.sliderFloat("Focus Distance (m)", floatBuf, 0.3f, 30f))
                s.focusDistM = floatBuf[0];

            intBuf[0] = s.aperatureBlades;
            if (ImGui.sliderInt("Aperture Blades", intBuf, 3, 8))
                s.aperatureBlades = intBuf[0];

            floatBuf[0] = s.afSpeed;
            if (ImGui.sliderFloat("AF Speed (1/s)", floatBuf, 0.5f, 10f))
                s.afSpeed = floatBuf[0];

            ImGui.separator();
            ImGui.text("Spherical Aberration");

            floatBuf[0] = s.saStrength;
            if (ImGui.sliderFloat("SA Strength (m)", floatBuf, 0f, 0.3f))
                s.saStrength = floatBuf[0];

            ImGui.separator();
            // Longitudinal CA — Red and Blue deltas; Green is the reference (0).
            // Negative = channel focuses nearer than green; Positive = farther.
            ImGui.text("Longitudinal CA (focal offset, m)");

            floatBuf[0] = s.lcaDelta[0];
            if (ImGui.sliderFloat("LCA Red", floatBuf, -0.02f, 0.02f))
                s.lcaDelta[0] = floatBuf[0];

            // Green is always the reference; displayed as read-only.
            ImGui.text("  LCA Green  :  0.000 (reference)");

            floatBuf[0] = s.lcaDelta[2];
            if (ImGui.sliderFloat("LCA Blue", floatBuf, -0.02f, 0.02f))
                s.lcaDelta[2] = floatBuf[0];
        }

        // ── SPPM ──────────────────────────────────────────────────────────────
        if (ImGui.collapsingHeader("SPPM Caustics")) {
            boolBuf.set(s.sppmEnabled);
            if (ImGui.checkbox("Enable SPPM Caustics", boolBuf)) s.sppmEnabled = boolBuf.get();

            if (s.sppmEnabled) {
            intBuf[0] = s.photonsPerIter / 1000;
            if (ImGui.sliderInt("Photons/iter (x1000)", intBuf, 10, 2000))
                s.photonsPerIter = intBuf[0] * 1000;
            ImGui.text(String.format("  = %,d photons", s.photonsPerIter));

            floatBuf[0] = s.sppmInitRadius;
            if (ImGui.sliderFloat("Initial Radius", floatBuf, 0.001f, 0.5f))
                s.sppmInitRadius = floatBuf[0];

            floatBuf[0] = s.sppmAlpha;
            if (ImGui.sliderFloat("Alpha", floatBuf, 0.5f, 1.0f))
                s.sppmAlpha = floatBuf[0];

            ImGui.text(String.format("  Search radius : %.5f m", sppmSearchRadius));
            ImGui.text(String.format("  Iteration     : %d",     sppmIteration));
            }
        }

        // ── AGC / Temporal ────────────────────────────────────────────────────
        if (ImGui.collapsingHeader("AGC & Temporal")) {
            floatBuf[0] = s.agcTargetLum;
            if (ImGui.sliderFloat("Target Luminance", floatBuf, 0.1f, 0.9f))
                s.agcTargetLum = floatBuf[0];

            floatBuf[0] = s.agcMaxGain;
            if (ImGui.sliderFloat("Max AGC Gain", floatBuf, 1.0f, 8.0f))
                s.agcMaxGain = floatBuf[0];

            floatBuf[0] = s.agcSpeed;
            if (ImGui.sliderFloat("AGC Speed (1/s)", floatBuf, 0.1f, 5.0f))
                s.agcSpeed = floatBuf[0];

            floatBuf[0] = s.wbSpeed;
            if (ImGui.sliderFloat("WB Speed (1/s)", floatBuf, 0.05f, 2.0f))
                s.wbSpeed = floatBuf[0];
        }

        // ── Post-Process Toggles ──────────────────────────────────────────────
        if (ImGui.collapsingHeader("Post-Process Passes")) {
            s.p01Enabled = postToggle("p01 Downsample",      s.p01Enabled);
            s.p02Enabled = postToggle("p02 Chroma Res",      s.p02Enabled);
            s.p03Enabled = postToggle("p03 Color Matrix",    s.p03Enabled);
            s.p04Enabled = postToggle("p04 Dynamic Range",   s.p04Enabled);
            s.p05Enabled = postToggle("p05 AGC",             s.p05Enabled);
            s.p06Enabled = postToggle("p06 Halation",        s.p06Enabled);
            s.p07Enabled = postToggle("p07 CCD Noise",       s.p07Enabled);
            s.p08Enabled = postToggle("p08 CCD Smear",       s.p08Enabled);
            s.p09Enabled = postToggle("p09 Chroma Bleed",    s.p09Enabled);
            s.p10Enabled = postToggle("p10 Timebase",        s.p10Enabled);
            s.p11Enabled = postToggle("p11 Dot Crawl",       s.p11Enabled);
            s.p12Enabled = postToggle("p12 Dropout",         s.p12Enabled);
            s.p13Enabled = postToggle("p13 Head Switch",     s.p13Enabled);
            s.p14Enabled = postToggle("p14 Tracking Error",  s.p14Enabled);
            s.p15Enabled = postToggle("p15 Edge Enhance",    s.p15Enabled);
            s.p16Enabled = postToggle("p16 Interlace",       s.p16Enabled);
            s.p17Enabled = postToggle("p17 Optics",          s.p17Enabled);
            s.p18Enabled = postToggle("p18 Lens Flare",      s.p18Enabled);
            s.p19Enabled = postToggle("p19 Scanlines",       s.p19Enabled);
            s.p20Enabled = postToggle("p20 Tonemap",         s.p20Enabled);
        }

        // ── Post-Process Key Values ────────────────────────────────────────────
        if (ImGui.collapsingHeader("Post-Process Values")) {
            floatBuf[0] = s.chromaResSigma;
            if (ImGui.sliderFloat("Chroma Blur Sigma (px)", floatBuf, 1f, 20f))
                s.chromaResSigma = floatBuf[0];

            floatBuf[0] = s.timbaseAmplitudePx;
            if (ImGui.sliderFloat("Timebase Amplitude (px)", floatBuf, 0f, 8f))
                s.timbaseAmplitudePx = floatBuf[0];

            floatBuf[0] = s.dropoutProbability;
            if (ImGui.sliderFloat("Dropout Probability", floatBuf, 0f, 1f))
                s.dropoutProbability = floatBuf[0];

            floatBuf[0] = s.edgeEnhanceAmount;
            if (ImGui.sliderFloat("Edge Enhance Amount", floatBuf, 0f, 4f))
                s.edgeEnhanceAmount = floatBuf[0];

            floatBuf[0] = s.vignetteStrength;
            if (ImGui.sliderFloat("Vignette Strength", floatBuf, 0f, 3f))
                s.vignetteStrength = floatBuf[0];

            floatBuf[0] = s.tapeAge;
            if (ImGui.sliderFloat("Tape Age (0=mint)", floatBuf, 0f, 1f))
                s.tapeAge = floatBuf[0];

            floatBuf[0] = s.trackingSeverity;
            if (ImGui.sliderFloat("Tracking Severity", floatBuf, 0f, 1f))
                s.trackingSeverity = floatBuf[0];
        }

        ImGui.end();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private boolean postToggle(String label, boolean current) {
        boolBuf.set(current);
        ImGui.checkbox(label, boolBuf);
        return boolBuf.get();
    }

    // ── Stats setters ─────────────────────────────────────────────────────────

    public void setStats(int iterations, float samplesPerSec, float gain) {
        this.iterationCount   = iterations;
        this.samplesPerSecond = samplesPerSec;
        this.agcGain          = gain;
    }

    public void setSppmStats(float searchRadius, int iteration) {
        this.sppmSearchRadius = searchRadius;
        this.sppmIteration    = iteration;
    }
}