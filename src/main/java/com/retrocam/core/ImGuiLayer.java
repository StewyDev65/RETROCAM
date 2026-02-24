package com.retrocam.core;

import com.retrocam.scene.Material;
import com.retrocam.scene.SceneEditor;
import com.retrocam.scene.SceneObject;
import com.retrocam.scene.SceneSerializer;
import imgui.ImGui;
import imgui.ImGuiIO;
import imgui.flag.ImGuiConfigFlags;
import imgui.flag.ImGuiWindowFlags;
import imgui.gl3.ImGuiImplGl3;
import imgui.glfw.ImGuiImplGlfw;
import imgui.type.ImBoolean;
import imgui.type.ImString;

import java.util.List;

/**
 * Owns the ImGui context and renders:
 *  — the RetroCam render-settings panel
 *  — the real-time Scene Editor panel (primitives, materials, save/load)
 *
 * Scene Editor design goals:
 *  • Any change that affects geometry or materials sets {@link SceneEditor#markDirty()},
 *    which the main loop detects to trigger a GPU re-upload and accumulation reset.
 *  • Material property edits are reflected in-place on the live {@link Material} objects;
 *    the editor marks dirty so the uploader re-uploads the material SSBO.
 *  • Save/Load round-trips through {@link SceneSerializer} with user-supplied filename.
 */
public final class ImGuiLayer {

    private final ImGuiImplGlfw imGuiGlfw = new ImGuiImplGlfw();
    private final ImGuiImplGl3  imGuiGl3  = new ImGuiImplGl3();

    // ── Shared scratch buffers ─────────────────────────────────────────────────
    private final ImBoolean boolBuf   = new ImBoolean();
    private final float[]   floatBuf  = new float[1];
    private final float[]   float3Buf = new float[3];
    private final int[]     intBuf    = new int[1];
    private final imgui.type.ImInt imIntBuf = new imgui.type.ImInt();

    // ── Stats ──────────────────────────────────────────────────────────────────
    private int   iterationCount;
    private float samplesPerSecond;
    private float agcGain;
    private float sppmSearchRadius;
    private int   sppmIteration;

    // ── Scene editor state ─────────────────────────────────────────────────────
    private int      selectedObjectIdx   = -1;
    private int      selectedMaterialIdx = -1;
    private final ImString filenameInput = new ImString("scene.json", 256);
    private final ImString nameInputBuf  = new ImString(256);
    private String   statusMessage       = "";
    private float    statusTimer         = 0f;

    // ── Static image test mode ─────────────────────────────────────────────────
    private final ImString testImagePath  = new ImString("test.png", 512);
    private boolean        testModeActive = false;
    private boolean        wantsLoadImage = false;

    // ── Render export state ────────────────────────────────────────────────────
    private com.retrocam.export.RenderPipeline renderPipeline;
    private final ImString exportOutputPath = new ImString("output", 512);
    private static final String[] RENDER_FORMAT_LABELS =
        com.retrocam.export.RenderFormat.labels();

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    public void init(long windowHandle) {
        ImGui.createContext();
        ImGuiIO io = ImGui.getIO();
        io.addConfigFlags(ImGuiConfigFlags.NavEnableKeyboard);
        imGuiGlfw.init(windowHandle, true);
        imGuiGl3.init("#version 430");
    }

    public void setRenderPipeline(com.retrocam.export.RenderPipeline pipeline) {
        this.renderPipeline = pipeline;
    }

    public void destroy() {
        imGuiGl3.dispose();
        imGuiGlfw.dispose();
        ImGui.destroyContext();
    }

    // ── Per-frame ──────────────────────────────────────────────────────────────

    public void beginFrame() {
        imGuiGlfw.newFrame();
        ImGui.newFrame();
    }

    /**
     * Renders all panels.
     *
     * @param settings    render/post-process settings
     * @param sceneEditor live mutable scene graph (may be {@code null} to disable editor)
     * @param dt          frame delta-time in seconds (used for UI timer ticks)
     */
    public void render(RenderSettings settings, SceneEditor sceneEditor, float dt) {
        tickTimers(dt);
        renderStatsOverlay();
        renderMainPanel(settings, sceneEditor);
    }

    public void endFrame() {
        ImGui.render();
        imGuiGl3.renderDrawData(ImGui.getDrawData());
    }

    // ── Stats overlay ──────────────────────────────────────────────────────────

    private void renderStatsOverlay() {
        int flags = ImGuiWindowFlags.NoDecoration
                  | ImGuiWindowFlags.AlwaysAutoResize
                  | ImGuiWindowFlags.NoBackground
                  | ImGuiWindowFlags.NoFocusOnAppearing
                  | ImGuiWindowFlags.NoNav
                  | ImGuiWindowFlags.NoMove;

        ImGui.setNextWindowPos(8, 8);
        if (ImGui.begin("##stats", new ImBoolean(true), flags)) {
            ImGui.text(String.format("Iterations : %d",     iterationCount));
            ImGui.text(String.format("Samples/s  : %.0f",   samplesPerSecond));
            ImGui.text(String.format("AGC Gain   : %.2fx",  agcGain));
            if (statusTimer > 0f)
                ImGui.textColored(0.4f, 1.0f, 0.4f, 1.0f, statusMessage);
        }
        ImGui.end();
    }

    // ── Main settings panel ────────────────────────────────────────────────────

    private void renderMainPanel(RenderSettings s, SceneEditor editor) {
        ImGui.setNextWindowSize(370, 720, imgui.flag.ImGuiCond.Once);
        ImGui.setNextWindowPos(8, 70, imgui.flag.ImGuiCond.Once);
        ImGui.setNextWindowSizeConstraints(370, 200, 370, 9000);
        if (!ImGui.begin("RetroCam Settings")) { ImGui.end(); return; }

        renderDisplaySection(s);
        renderCameraSection(s);
        renderSppmSection(s);
        renderDenoisingSection(s);
        renderAgcSection(s);
        renderPostToggleSection(s);
        renderPostValuesSection(s);
        renderStaticTestSection();

        if (renderPipeline != null)
            renderExportSection(s);

        if (editor != null)
            renderSceneEditor(editor);

        ImGui.end();
    }

    // ── Display ────────────────────────────────────────────────────────────────

    private void renderDisplaySection(RenderSettings s) {
        if (!ImGui.collapsingHeader("Display")) return;

        boolBuf.set(s.vSync);
        if (ImGui.checkbox("VSync", boolBuf)) s.vSync = boolBuf.get();

        floatBuf[0] = s.exposure;
        if (ImGui.sliderFloat("Exposure", floatBuf, 0.1f, 8.0f))
            s.exposure = floatBuf[0];

        ImGui.text(String.format("Render res : %dx%d @ %.2f fps",
            RenderSettings.RENDER_WIDTH, RenderSettings.RENDER_HEIGHT, RenderSettings.FRAME_RATE));
    }

    // ── Camera & Optics ────────────────────────────────────────────────────────

    private void renderCameraSection(RenderSettings s) {
        if (!ImGui.collapsingHeader("Camera & Optics")) return;

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
        ImGui.text("Longitudinal CA (focal offset, m)");
        floatBuf[0] = s.lcaDelta[0];
        if (ImGui.sliderFloat("LCA Red", floatBuf, -0.02f, 0.02f))
            s.lcaDelta[0] = floatBuf[0];

        ImGui.text("  LCA Green  :  0.000 (reference)");

        floatBuf[0] = s.lcaDelta[2];
        if (ImGui.sliderFloat("LCA Blue", floatBuf, -0.02f, 0.02f))
            s.lcaDelta[2] = floatBuf[0];
    }

    // ── Render Export ──────────────────────────────────────────────────────────

    private void renderExportSection(RenderSettings s) {
        if (!ImGui.collapsingHeader("Render Export")) return;

        // ── Output path ──────────────────────────────────────────────────
        ImGui.textDisabled("Output path (no extension)");
        ImGui.setNextItemWidth(-1);
        if (ImGui.inputText("##exportPath", exportOutputPath))
            s.exportOutputPath = exportOutputPath.get();

        // ── Format ───────────────────────────────────────────────────────
        ImGui.setNextItemWidth(-1);
        imIntBuf.set(s.exportFormatIndex);
        if (ImGui.combo("Format##exportFmt", imIntBuf, RENDER_FORMAT_LABELS))
            s.exportFormatIndex = imIntBuf.get();

        com.retrocam.export.RenderFormat fmt =
            com.retrocam.export.RenderFormat.values()[s.exportFormatIndex];

        // ── Quality ───────────────────────────────────────────────────────
        intBuf[0] = s.exportSamplesPerFrame;
        if (ImGui.sliderInt("Samples / frame##exportSpp", intBuf, 1, 2048))
            s.exportSamplesPerFrame = intBuf[0];

        if (fmt == com.retrocam.export.RenderFormat.PHOTO_JPEG) {
            intBuf[0] = s.exportJpegQuality;
            if (ImGui.sliderInt("JPEG Quality##exportJpeg", intBuf, 1, 100))
                s.exportJpegQuality = intBuf[0];
        }

        // ── Video settings ────────────────────────────────────────────────
        if (fmt.isVideo) {
            ImGui.separator();
            ImGui.textColored(0.8f, 0.8f, 0.4f, 1f, "Video Settings");

            floatBuf[0] = s.exportDurationSec;
            if (ImGui.sliderFloat("Duration (s)##exportDur", floatBuf, 0.5f, 300f))
                s.exportDurationSec = floatBuf[0];

            floatBuf[0] = s.exportFps;
            if (ImGui.sliderFloat("FPS##exportFps", floatBuf, 1f, 120f))
                s.exportFps = floatBuf[0];

            int frames = Math.max(1, Math.round(s.exportDurationSec * s.exportFps));
            ImGui.textDisabled(String.format("  → %d frames  |  ~%.1f min at current spp",
                frames, estimateRenderMinutes(frames, s.exportSamplesPerFrame)));

            if (fmt.requiresFFmpeg) {
                boolean hasFfmpeg = com.retrocam.export.VideoExporter.isFFmpegAvailable();
                if (hasFfmpeg)
                    ImGui.textColored(0.4f, 1f, 0.4f, 1f, "  FFmpeg found \u2713");
                else
                    ImGui.textColored(1f, 0.5f, 0.3f, 1f,
                        "  FFmpeg not found — will save image sequence");
            }
        }

        ImGui.spacing();
        ImGui.separator();

        // ── Pipeline status / controls ────────────────────────────────────
        com.retrocam.export.RenderPipeline.State pState = renderPipeline.getState();

        if (renderPipeline.isRunning()) {
            // Progress bar
            float progress = renderPipeline.getProgress();
            String overlay = fmt.isVideo
                ? String.format("Frame %d / %d", renderPipeline.getCurrentFrame(),
                                renderPipeline.getTotalFrames())
                : "Rendering…";
            ImGui.progressBar(progress, -1, 0, overlay);
            ImGui.textDisabled(renderPipeline.getStatusMessage());

            if (ImGui.button("Cancel##exportCancel"))
                renderPipeline.cancel();

        } else if (pState == com.retrocam.export.RenderPipeline.State.COMPLETE) {
            ImGui.textColored(0.4f, 1f, 0.4f, 1f, "\u2713 " + renderPipeline.getStatusMessage());
            if (ImGui.button("New Render##exportNew"))  renderPipeline.reset();
            ImGui.sameLine();
            if (ImGui.button("Render Again##exportAgain")) startRenderJob(s);

        } else if (pState == com.retrocam.export.RenderPipeline.State.ERROR
                || pState == com.retrocam.export.RenderPipeline.State.CANCELLED) {
            ImGui.textColored(1f, 0.4f, 0.4f, 1f, renderPipeline.getStatusMessage());
            if (ImGui.button("Dismiss##exportDismiss")) renderPipeline.reset();
            ImGui.sameLine();
            if (ImGui.button("Retry##exportRetry")) startRenderJob(s);

        } else {
            // IDLE — show start button
            if (ImGui.button("Start Render##exportStart"))
                startRenderJob(s);
        }
    }

    private void startRenderJob(RenderSettings s) {
        try {
            exportOutputPath.set(s.exportOutputPath);
            com.retrocam.export.RenderFormat fmt =
                com.retrocam.export.RenderFormat.values()[s.exportFormatIndex];
            com.retrocam.export.RenderJob job = com.retrocam.export.RenderJob.builder()
                .outputPath(s.exportOutputPath)
                .format(fmt)
                .samplesPerFrame(s.exportSamplesPerFrame)
                .jpegQuality(s.exportJpegQuality)
                .durationSeconds(s.exportDurationSec)
                .fps(s.exportFps)
                .build();
            renderPipeline.startJob(job);
        } catch (Exception e) {
            showStatus("Render error: " + e.getMessage());
        }
    }

    /** Very rough estimate: assumes ~0.5s per sample per frame on a mid-range GPU. */
    private float estimateRenderMinutes(int totalFrames, int spp) {
        return (totalFrames * spp * 0.5f) / 60f;
    }

    // ── SPPM ───────────────────────────────────────────────────────────────────

    private void renderSppmSection(RenderSettings s) {
        if (!ImGui.collapsingHeader("SPPM Caustics")) return;

        // NEE toggle lives here because both systems handle direct/caustic lighting
        boolBuf.set(s.neeEnabled);
        if (ImGui.checkbox("NEE Direct Lighting", boolBuf)) s.neeEnabled = boolBuf.get();

        if (s.neeEnabled) {
            floatBuf[0] = s.neeFireflyClamp;
            if (ImGui.sliderFloat("NEE Firefly Clamp", floatBuf, 1.0f, 100.0f))
                s.neeFireflyClamp = floatBuf[0];
        }

        ImGui.sameLine();
        ImGui.textDisabled("(Next Event Estimation)");
        ImGui.separator();

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

    // ── À-trous Denoiser ───────────────────────────────────────────────────────

    private void renderDenoisingSection(RenderSettings s) {
        if (!ImGui.collapsingHeader("Spatial Denoiser (À-Trous)")) return;

        boolBuf.set(s.atrousEnabled);
        if (ImGui.checkbox("Enable À-Trous Denoiser", boolBuf)) s.atrousEnabled = boolBuf.get();

        if (!s.atrousEnabled) return;

        intBuf[0] = s.atrousIterations;
        if (ImGui.sliderInt("Iterations##atrous", intBuf, 1, 5))
            s.atrousIterations = intBuf[0];
        ImGui.sameLine();
        ImGui.textDisabled("(filter radius 2^N px)");

        floatBuf[0] = s.atrousSigmaColor;
        if (ImGui.sliderFloat("Sigma Colour##atrous", floatBuf, 0.01f, 0.5f))
            s.atrousSigmaColor = floatBuf[0];

        floatBuf[0] = s.atrousSigmaNormal;
        if (ImGui.sliderFloat("Sigma Normal##atrous", floatBuf, 1.0f, 128.0f))
            s.atrousSigmaNormal = floatBuf[0];

        floatBuf[0] = s.atrousSigmaDepth;
        if (ImGui.sliderFloat("Sigma Depth##atrous", floatBuf, 0.05f, 2.0f))
            s.atrousSigmaDepth = floatBuf[0];

        intBuf[0] = s.atrousMaxSpp;
        if (ImGui.sliderInt("Auto-Off SPP##atrous", intBuf, 0, 256))
            s.atrousMaxSpp = intBuf[0];
        ImGui.sameLine();
        ImGui.textDisabled("(0 = always on)");
    }

    // ── AGC & Temporal ─────────────────────────────────────────────────────────

    private void renderAgcSection(RenderSettings s) {
        if (!ImGui.collapsingHeader("AGC & Temporal")) return;

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

    // ── Post-Process Toggles ───────────────────────────────────────────────────

    private void renderPostToggleSection(RenderSettings s) {
        if (!ImGui.collapsingHeader("Post-Process Passes")) return;

        s.p01Enabled = postToggle("p01 Downsample",     s.p01Enabled);
        s.p02Enabled = postToggle("p02 Chroma Res",     s.p02Enabled);
        s.p03Enabled = postToggle("p03 Color Matrix",   s.p03Enabled);
        s.p04Enabled = postToggle("p04 Dynamic Range",  s.p04Enabled);
        s.p05Enabled = postToggle("p05 AGC",            s.p05Enabled);
        s.p06Enabled = postToggle("p06 Halation",       s.p06Enabled);
        s.p07Enabled = postToggle("p07 CCD Noise",      s.p07Enabled);
        s.p08Enabled = postToggle("p08 CCD Smear",      s.p08Enabled);
        s.p09Enabled = postToggle("p09 Chroma Bleed",   s.p09Enabled);
        s.p10Enabled = postToggle("p10 Timebase",       s.p10Enabled);
        s.p11Enabled = postToggle("p11 Dot Crawl",      s.p11Enabled);
        s.p12Enabled = postToggle("p12 Dropout",        s.p12Enabled);
        s.p13Enabled = postToggle("p13 Head Switch",    s.p13Enabled);
        s.p14Enabled = postToggle("p14 Tracking Error", s.p14Enabled);
        s.p15Enabled = postToggle("p15 Edge Enhance",   s.p15Enabled);
        s.p16Enabled = postToggle("p16 Interlace",      s.p16Enabled);
        s.p17Enabled = postToggle("p17 Optics",         s.p17Enabled);
        s.p18Enabled = postToggle("p18 Lens Flare",     s.p18Enabled);
        s.p19Enabled = postToggle("p19 Scanlines",      s.p19Enabled);
    }

    // ── Post-Process Values ────────────────────────────────────────────────────

    private void renderPostValuesSection(RenderSettings s) {
        if (!ImGui.collapsingHeader("Post-Process Values")) return;

        // p01 – Downsample
        ImGui.textColored(0.8f, 0.8f, 0.4f, 1f, "p01  Luma Bandwidth");
        floatBuf[0] = s.lumaBlurSigma;
        if (ImGui.sliderFloat("Luma Blur Sigma (px)##p01", floatBuf, 0f, 5f))
            s.lumaBlurSigma = floatBuf[0];

        // p02 – Chroma resolution
        ImGui.textColored(0.8f, 0.8f, 0.4f, 1f, "p02  Chroma Resolution");
        floatBuf[0] = s.chromaResSigma;
        if (ImGui.sliderFloat("Chroma Blur Sigma (px)##p02", floatBuf, 1f, 20f))
            s.chromaResSigma = floatBuf[0];

        // p03 – Color matrix
        ImGui.textColored(0.8f, 0.8f, 0.4f, 1f, "p03  Color Matrix");
        floatBuf[0] = s.colorTempBias;
        if (ImGui.sliderFloat("Color Temp Bias##p03", floatBuf, -1f, 1f))
            s.colorTempBias = floatBuf[0];
        floatBuf[0] = s.colorHueRot;
        if (ImGui.sliderFloat("Hue Rotation (deg)##p03", floatBuf, -30f, 30f))
            s.colorHueRot = floatBuf[0];
        floatBuf[0] = s.colorSaturation;
        if (ImGui.sliderFloat("Saturation##p03", floatBuf, 0f, 2f))
            s.colorSaturation = floatBuf[0];
        floatBuf[0] = s.colorDriftSpeed;
        if (ImGui.sliderFloat("Drift Speed (rad/s)##p03", floatBuf, 0f, 0.5f))
            s.colorDriftSpeed = floatBuf[0];

        // p04 – Dynamic range
        ImGui.textColored(0.8f, 0.8f, 0.4f, 1f, "p04  Dynamic Range");
        floatBuf[0] = s.drBlackLift;
        if (ImGui.sliderFloat("Black Lift##p04", floatBuf, 0f, 0.10f))
            s.drBlackLift = floatBuf[0];
        floatBuf[0] = s.drShadowCrush;
        if (ImGui.sliderFloat("Shadow Crush##p04", floatBuf, 0f, 0.20f))
            s.drShadowCrush = floatBuf[0];
        floatBuf[0] = s.drHighlightKnee;
        if (ImGui.sliderFloat("Highlight Knee##p04", floatBuf, 0.40f, 1.0f))
            s.drHighlightKnee = floatBuf[0];
        floatBuf[0] = s.drHighlightClip;
        if (ImGui.sliderFloat("Highlight Clip##p04", floatBuf, 0.60f, 1.0f))
            s.drHighlightClip = floatBuf[0];

        // p07 – CCD noise
        ImGui.textColored(0.8f, 0.8f, 0.4f, 1f, "p07  CCD Noise");
        floatBuf[0] = s.ccdNoiseLumaBase;
        if (ImGui.sliderFloat("Noise Base Amplitude##p07", floatBuf, 0f, 0.1f))
            s.ccdNoiseLumaBase = floatBuf[0];
        floatBuf[0] = s.ccdNoiseChromaScale;
        if (ImGui.sliderFloat("Chroma Noise Scale##p07", floatBuf, 1f, 4f))
            s.ccdNoiseChromaScale = floatBuf[0];

        // p08 – CCD smear
        ImGui.textColored(0.8f, 0.8f, 0.4f, 1f, "p08  CCD Smear");
        floatBuf[0] = s.ccdSmearThreshold;
        if (ImGui.sliderFloat("Smear Threshold##p08", floatBuf, 0.50f, 1.0f))
            s.ccdSmearThreshold = floatBuf[0];
        floatBuf[0] = s.ccdSmearIntensity;
        if (ImGui.sliderFloat("Smear Intensity##p08", floatBuf, 0f, 1.0f))
            s.ccdSmearIntensity = floatBuf[0];
        intBuf[0] = s.ccdSmearLength;
        if (ImGui.sliderInt("Smear Length (px)##p08", intBuf, 10, 150))
            s.ccdSmearLength = intBuf[0];

        // p09 – Chroma bleed
        ImGui.textColored(0.8f, 0.8f, 0.4f, 1f, "p09  Chroma Bleed");
        floatBuf[0] = s.chromaBleedFactor;
        if (ImGui.sliderFloat("Bleed Factor (k)##p09", floatBuf, 0f, 0.95f))
            s.chromaBleedFactor = floatBuf[0];

        // p10 – Timebase
        ImGui.textColored(0.8f, 0.8f, 0.4f, 1f, "p10  Timebase Error");
        floatBuf[0] = s.timebaseAmplitudePx;
        if (ImGui.sliderFloat("TBE Amplitude (px)##p10", floatBuf, 0f, 8f))
            s.timebaseAmplitudePx = floatBuf[0];
        floatBuf[0] = s.timebaseFreq;
        if (ImGui.sliderFloat("TBE Freq (rad/px)##p10", floatBuf, 0f, 0.15f))
            s.timebaseFreq = floatBuf[0];
        floatBuf[0] = s.timebaseSpeed;
        if (ImGui.sliderFloat("TBE Speed (rad/s)##p10", floatBuf, 0f, 3f))
            s.timebaseSpeed = floatBuf[0];

        // p11 – Dot Crawl
        boolBuf.set(s.p11Enabled);
        if (ImGui.checkbox("##p11en", boolBuf)) s.p11Enabled = boolBuf.get();
        ImGui.sameLine();
        ImGui.textColored(0.8f, 0.8f, 0.4f, 1f, "p11  Dot Crawl");
        floatBuf[0] = s.dotCrawlIntensity;
        if (ImGui.sliderFloat("Intensity##p11", floatBuf, 0f, 1f))
            s.dotCrawlIntensity = floatBuf[0];
        floatBuf[0] = s.dotCrawlSubcarrier;
        if (ImGui.sliderFloat("Subcarrier Freq (cyc/px)##p11", floatBuf, 0.05f, 0.4f))
            s.dotCrawlSubcarrier = floatBuf[0];
        floatBuf[0] = s.dotCrawlEdgeThresh;
        if (ImGui.sliderFloat("Edge Threshold##p11", floatBuf, 0.005f, 0.2f))
            s.dotCrawlEdgeThresh = floatBuf[0];

        // p12 – Tape Dropout
        boolBuf.set(s.p12Enabled);
        if (ImGui.checkbox("##p12en", boolBuf)) s.p12Enabled = boolBuf.get();
        ImGui.sameLine();
        ImGui.textColored(0.8f, 0.8f, 0.4f, 1f, "p12  Tape Dropout");
        floatBuf[0] = s.tapeAge;
        if (ImGui.sliderFloat("Tape Age (0=mint)##p12", floatBuf, 0f, 1f))
            s.tapeAge = floatBuf[0];
        floatBuf[0] = s.dropoutBright;
        if (ImGui.sliderFloat("Dropout Brightness##p12", floatBuf, 0.5f, 1.0f))
            s.dropoutBright = floatBuf[0];
        floatBuf[0] = s.dropoutBurstRate;
        if (ImGui.sliderFloat("Burst Frequency##p12", floatBuf, 0.01f, 1.0f))
            s.dropoutBurstRate = floatBuf[0];

        ImGui.separator();

        // p13 – Head-Switch Noise
        boolBuf.set(s.p13Enabled);
        if (ImGui.checkbox("##p13en", boolBuf)) s.p13Enabled = boolBuf.get();
        ImGui.sameLine();
        ImGui.textColored(0.8f, 0.8f, 0.4f, 1f, "p13  Head-Switch Noise");
        intBuf[0] = s.headSwitchLines;
        if (ImGui.sliderInt("Switch Zone (lines)##p13", intBuf, 4, 24))
            s.headSwitchLines = intBuf[0];
        floatBuf[0] = s.headSwitchJitter;
        if (ImGui.sliderFloat("Jitter Scale##p13", floatBuf, 1f, 8f))
            s.headSwitchJitter = floatBuf[0];
        floatBuf[0] = s.headSwitchRipple;
        if (ImGui.sliderFloat("Luma Ripple##p13", floatBuf, 0f, 0.35f))
            s.headSwitchRipple = floatBuf[0];
        floatBuf[0] = s.headSwitchChroma;
        if (ImGui.sliderFloat("Chroma Rotation (rad)##p13", floatBuf, 0f, 1.2f))
            s.headSwitchChroma = floatBuf[0];

        // p14 – Tracking Error
        boolBuf.set(s.p14Enabled);
        if (ImGui.checkbox("##p14en", boolBuf)) s.p14Enabled = boolBuf.get();
        ImGui.sameLine();
        ImGui.textColored(0.8f, 0.8f, 0.4f, 1f, "p14  Tracking Error");
        floatBuf[0] = s.trackingSeverity;
        if (ImGui.sliderFloat("Severity##p14", floatBuf, 0f, 1f))
            s.trackingSeverity = floatBuf[0];
        floatBuf[0] = s.trackingMaxDisplacePx;
        if (ImGui.sliderFloat("Max Displacement (px)##p14", floatBuf, 4f, 80f))
            s.trackingMaxDisplacePx = floatBuf[0];
        floatBuf[0] = s.trackingFringeWidthPx;
        if (ImGui.sliderFloat("Fringe Width (px)##p14", floatBuf, 1f, 12f))
            s.trackingFringeWidthPx = floatBuf[0];

        // p15 – Edge Enhancement (Aperture Correction)
        boolBuf.set(s.p15Enabled);
        if (ImGui.checkbox("##p15en", boolBuf)) s.p15Enabled = boolBuf.get();
        ImGui.sameLine();
        ImGui.textColored(0.8f, 0.8f, 0.4f, 1f, "p15  Edge Enhancement");
        floatBuf[0] = s.edgeEnhanceAmount;
        if (ImGui.sliderFloat("Amount##p15", floatBuf, 0.5f, 3.5f))
            s.edgeEnhanceAmount = floatBuf[0];
        floatBuf[0] = s.edgeEnhanceCoreThreshold;
        if (ImGui.sliderFloat("Coring Threshold##p15", floatBuf, 0f, 0.10f))
            s.edgeEnhanceCoreThreshold = floatBuf[0];

        // p16 – Interlace
        boolBuf.set(s.p16Enabled);
        if (ImGui.checkbox("##p16en", boolBuf)) s.p16Enabled = boolBuf.get();
        ImGui.sameLine();
        ImGui.textColored(0.8f, 0.8f, 0.4f, 1f, "p16  Interlace");
        floatBuf[0] = s.interlaceFieldOffsetPx;
        if (ImGui.sliderFloat("Field Offset (px)##p16", floatBuf, 0f, 1.0f))
            s.interlaceFieldOffsetPx = floatBuf[0];
        floatBuf[0] = s.interlaceCombStrength;
        if (ImGui.sliderFloat("Comb Strength (px)##p16", floatBuf, 0f, 6.0f))
            s.interlaceCombStrength = floatBuf[0];
        floatBuf[0] = s.interlaceCombEdgeThresh;
        if (ImGui.sliderFloat("Comb Edge Thresh##p16", floatBuf, 0.01f, 0.15f))
            s.interlaceCombEdgeThresh = floatBuf[0];
        floatBuf[0] = s.interlaceLineWeighting;
        if (ImGui.sliderFloat("Line Weighting##p16", floatBuf, 0f, 0.20f))
            s.interlaceLineWeighting = floatBuf[0];

        // p17 placeholder (vignette strength used when p17 is implemented)
        ImGui.separator();
        floatBuf[0] = s.vignetteStrength;
        if (ImGui.sliderFloat("Vignette Strength##p17", floatBuf, 0f, 3f))
            s.vignetteStrength = floatBuf[0];
    }

    // ── Static image test mode ─────────────────────────────────────────────────

    private void renderStaticTestSection() {
        if (!ImGui.collapsingHeader("Post-Process Test (Static Image)")) return;

        ImGui.textWrapped(
            "Load any PNG/JPG to run the post-process stack on a static image " +
            "without the renderer.  Great for quickly tuning individual pass values.");
        ImGui.spacing();

        ImGui.setNextItemWidth(-80);
        ImGui.inputText("##testpath", testImagePath);
        ImGui.sameLine();
        if (ImGui.button("Load##testimg")) {
            wantsLoadImage = true;
        }

        if (testModeActive) {
            ImGui.sameLine();
            if (ImGui.button("Stop##testmode")) {
                testModeActive = false;
            }
            ImGui.textColored(0.4f, 1.0f, 0.4f, 1.0f,
                "Test mode active — renderer paused");
        } else {
            ImGui.textDisabled("(No test image loaded — renderer running)");
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Scene Editor
    // ═════════════════════════════════════════════════════════════════════════

    private void renderSceneEditor(SceneEditor editor) {
        if (!ImGui.collapsingHeader("Scene Editor")) return;

        renderSceneIO(editor);
        ImGui.separator();
        renderPrimitivesSection(editor);
        ImGui.separator();
        renderMaterialsSection(editor);
    }

    // ── Save / Load / Reset ────────────────────────────────────────────────────

    private void renderSceneIO(SceneEditor editor) {
        ImGui.setNextItemWidth(160);
        ImGui.inputText("##file", filenameInput);

        ImGui.sameLine();
        if (ImGui.button("Save")) {
            try {
                SceneSerializer.save(editor, filenameInput.get());
                showStatus("Saved \u2713  " + filenameInput.get());
            } catch (Exception ex) {
                showStatus("Save failed: " + ex.getMessage());
            }
        }

        ImGui.sameLine();
        if (ImGui.button("Load")) {
            try {
                SceneEditor loaded = SceneSerializer.load(filenameInput.get());
                replaceEditorContents(editor, loaded);
                selectedObjectIdx   = -1;
                selectedMaterialIdx = -1;
                showStatus("Loaded \u2713  " + filenameInput.get());
            } catch (Exception ex) {
                showStatus("Load failed: " + ex.getMessage());
            }
        }

        ImGui.sameLine();
        if (ImGui.button("Reset")) {
            replaceEditorContents(editor, SceneEditor.createDefault());
            selectedObjectIdx   = -1;
            selectedMaterialIdx = -1;
            showStatus("Reset to default Cornell box");
        }
    }

    // ── Primitives ─────────────────────────────────────────────────────────────

    private void renderPrimitivesSection(SceneEditor editor) {
        ImGui.text("Primitives  (" + editor.objectCount() + ")");

        // Add-primitive buttons (only available when at least one material exists)
        if (editor.materialCount() > 0) {
            ImGui.sameLine();
            if (ImGui.smallButton("+Box")) {
                editor.addObject(SceneObject.defaultBox(0));
                selectedObjectIdx = editor.objectCount() - 1;
            }
            ImGui.sameLine();
            if (ImGui.smallButton("+Sphere")) {
                editor.addObject(SceneObject.defaultSphere(0));
                selectedObjectIdx = editor.objectCount() - 1;
            }
        }

        // Object list
        List<SceneObject> objs = editor.getObjects();
        int listH = Math.min(objs.size() * 20 + 8, 140);
        ImGui.beginChild("##objList", 0, listH, true);
        for (int i = 0; i < objs.size(); i++) {
            SceneObject o = objs.get(i);
            String label = o.name + "  [" + o.type.name().charAt(0) + "]##oi" + i;
            if (ImGui.selectable(label, i == selectedObjectIdx))
                selectedObjectIdx = i;
        }
        ImGui.endChild();

        // Property editor for selected object
        if (selectedObjectIdx >= 0 && selectedObjectIdx < objs.size()) {
            renderObjectProperties(editor, selectedObjectIdx);
        }
    }

    private void renderObjectProperties(SceneEditor editor, int idx) {
        SceneObject obj = editor.getObject(idx);
        if (obj == null) return;

        ImGui.separator();
        ImGui.textColored(0.9f, 0.8f, 0.4f, 1f, "Edit: " + obj.type.name());

        // ── Name ──────────────────────────────────────────────────────────────
        nameInputBuf.set(obj.name);
        ImGui.setNextItemWidth(-1);
        if (ImGui.inputText("##objname", nameInputBuf))
            obj.name = nameInputBuf.get();

        // ── Position ──────────────────────────────────────────────────────────
        float[] pos = {obj.px, obj.py, obj.pz};
        if (ImGui.dragFloat3("Position##obj", pos, 0.05f)) {
            obj.px = pos[0]; obj.py = pos[1]; obj.pz = pos[2];
            editor.markDirty();
        }

        // ── Rotation ──────────────────────────────────────────────────────────────
        float[] rot = {obj.rx, obj.ry, obj.rz};
        if (ImGui.dragFloat3("Rotation (deg)##obj", rot, 0.5f)) {
            obj.rx = rot[0]; obj.ry = rot[1]; obj.rz = rot[2];
            editor.markDirty();
        }

        // ── Size ──────────────────────────────────────────────────────────────
        if (obj.type == SceneObject.Type.BOX) {
            float[] ext = {obj.sx, obj.sy, obj.sz};
            if (ImGui.dragFloat3("Half-extents##obj", ext, 0.02f, 0.001f, 100f)) {
                obj.sx = Math.max(0.001f, ext[0]);
                obj.sy = Math.max(0.001f, ext[1]);
                obj.sz = Math.max(0.001f, ext[2]);
                editor.markDirty();
            }
        } else {
            floatBuf[0] = obj.sx;
            if (ImGui.dragFloat("Radius##obj", floatBuf, 0.02f, 0.001f, 100f)) {
                obj.sx = Math.max(0.001f, floatBuf[0]);
                editor.markDirty();
            }
            intBuf[0] = obj.stacks;
            if (ImGui.sliderInt("Stacks##obj", intBuf, 4, 64)) {
                obj.stacks = intBuf[0]; editor.markDirty();
            }
            intBuf[0] = obj.slices;
            if (ImGui.sliderInt("Slices##obj", intBuf, 4, 64)) {
                obj.slices = intBuf[0]; editor.markDirty();
            }
        }

        // ── Material selector ─────────────────────────────────────────────────
        List<String> mnames = editor.getMatNames();
        if (!mnames.isEmpty()) {
            String[] mnArr = mnames.toArray(new String[0]);
            imIntBuf.set(Math.min(obj.materialIndex, mnArr.length - 1));
            ImGui.setNextItemWidth(-1);
            if (ImGui.combo("##objmat", imIntBuf, mnArr)) {
                obj.materialIndex = imIntBuf.get();
                editor.markDirty();
            }
            ImGui.sameLine(0, 4);
            ImGui.text("Material");
        }

        // ── Remove ────────────────────────────────────────────────────────────
        if (ImGui.button("Remove Object##obj")) {
            editor.removeObject(idx);
            selectedObjectIdx = Math.min(idx, editor.objectCount() - 1);
        }
    }

    // ── Materials ──────────────────────────────────────────────────────────────

    private void renderMaterialsSection(SceneEditor editor) {
        ImGui.text("Materials  (" + editor.materialCount() + ")");
        ImGui.sameLine();
        if (ImGui.smallButton("+Mat")) {
            editor.addMaterial("New Material", Material.diffuse(0.7f, 0.7f, 0.7f));
            selectedMaterialIdx = editor.materialCount() - 1;
        }

        List<String>   mnames = editor.getMatNames();
        List<Material> mats   = editor.getMaterials();
        int listH = Math.min(mats.size() * 20 + 8, 110);
        ImGui.beginChild("##matList", 0, listH, true);
        for (int i = 0; i < mats.size(); i++) {
            String label = "[" + i + "]  " + mnames.get(i) + "##mi" + i;
            if (ImGui.selectable(label, i == selectedMaterialIdx))
                selectedMaterialIdx = i;
        }
        ImGui.endChild();

        if (selectedMaterialIdx >= 0 && selectedMaterialIdx < mats.size()) {
            renderMaterialProperties(editor, selectedMaterialIdx);
        }
    }

    private void renderMaterialProperties(SceneEditor editor, int idx) {
        Material mat = editor.getMaterial(idx);
        if (mat == null) return;

        ImGui.separator();
        ImGui.textColored(0.4f, 0.9f, 1f, 1f, "Edit Material");

        // ── Name ──────────────────────────────────────────────────────────────
        nameInputBuf.set(editor.getMatNames().get(idx));
        ImGui.setNextItemWidth(-1);
        if (ImGui.inputText("##matname", nameInputBuf))
            editor.setMatName(idx, nameInputBuf.get());

        // ── Albedo ────────────────────────────────────────────────────────────
        float3Buf[0] = mat.albedo[0]; float3Buf[1] = mat.albedo[1]; float3Buf[2] = mat.albedo[2];
        if (ImGui.colorEdit3("Albedo##mat", float3Buf)) {
            mat.albedo[0] = float3Buf[0]; mat.albedo[1] = float3Buf[1]; mat.albedo[2] = float3Buf[2];
            editor.markDirty();
        }

        // ── PBR sliders ───────────────────────────────────────────────────────
        floatBuf[0] = mat.metallic;
        if (ImGui.sliderFloat("Metallic##mat", floatBuf, 0f, 1f)) {
            mat.metallic = floatBuf[0]; editor.markDirty();
        }

        floatBuf[0] = mat.roughness;
        if (ImGui.sliderFloat("Roughness##mat", floatBuf, 0f, 1f)) {
            mat.roughness = floatBuf[0]; editor.markDirty();
        }

        // ── Emission ──────────────────────────────────────────────────────────
        ImGui.separator();
        float3Buf[0] = mat.emission[0]; float3Buf[1] = mat.emission[1]; float3Buf[2] = mat.emission[2];
        if (ImGui.colorEdit3("Emission##mat", float3Buf)) {
            mat.emission[0] = float3Buf[0]; mat.emission[1] = float3Buf[1]; mat.emission[2] = float3Buf[2];
            editor.markDirty();
        }

        floatBuf[0] = mat.emissionStrength;
        if (ImGui.dragFloat("Em. Strength##mat", floatBuf, 0.1f, 0f, 500f)) {
            mat.emissionStrength = Math.max(0f, floatBuf[0]); editor.markDirty();
        }

        // ── Transmission / IOR ────────────────────────────────────────────────
        ImGui.separator();
        floatBuf[0] = mat.transmission;
        if (ImGui.sliderFloat("Transmission##mat", floatBuf, 0f, 1f)) {
            mat.transmission = floatBuf[0]; editor.markDirty();
        }

        floatBuf[0] = mat.ior;
        if (ImGui.sliderFloat("IOR##mat", floatBuf, 1.0f, 3.0f)) {
            mat.ior = floatBuf[0]; editor.markDirty();
        }

        // ── Remove (guard: keep at least one material) ────────────────────────
        if (editor.materialCount() > 1) {
            if (ImGui.button("Remove Material##mat")) {
                editor.removeMaterial(idx);
                selectedMaterialIdx = Math.min(idx, editor.materialCount() - 1);
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Helpers
    // ═════════════════════════════════════════════════════════════════════════

    /** Replaces all contents of {@code target} with contents from {@code source}. */
    private void replaceEditorContents(SceneEditor target, SceneEditor source) {
        target.clearAll();
        List<String>   snames = source.getMatNames();
        List<Material> smats  = source.getMaterials();
        for (int i = 0; i < smats.size(); i++)
            target.addMaterial(snames.get(i), smats.get(i));
        for (SceneObject o : source.getObjects())
            target.addObject(o);
        target.markDirty();
    }

    private boolean postToggle(String label, boolean current) {
        boolBuf.set(current);
        ImGui.checkbox(label, boolBuf);
        return boolBuf.get();
    }

    private void showStatus(String msg) {
        statusMessage = msg;
        statusTimer   = 3.5f;
    }

    private void tickTimers(float dt) {
        if (statusTimer > 0f) statusTimer = Math.max(0f, statusTimer - dt);
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

    // ── Static image test mode accessors ──────────────────────────────────────

    /** True when the user has engaged post-process test mode with a loaded image. */
    public boolean isTestModeActive() { return testModeActive; }

    /** Returns the file path typed in the test-mode panel. */
    public String getTestImagePath() { return testImagePath.get(); }

    /**
     * Consumes the "load image" request flag.
     * Returns {@code true} once after the Load button is pressed; resets to false.
     */
    public boolean pollWantsLoadImage() {
        boolean v = wantsLoadImage;
        wantsLoadImage = false;
        return v;
    }

    /**
     * Called by Main after successfully loading a test image to activate test mode.
     * Also used to deactivate ({@code active = false}) when image loading fails.
     */
    public void setTestModeActive(boolean active) {
        this.testModeActive = active;
    }
}