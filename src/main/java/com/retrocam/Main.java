package com.retrocam;

import com.retrocam.camera.OrbitCamera;
import com.retrocam.camera.ThinLensCamera;
import com.retrocam.core.ImGuiLayer;
import com.retrocam.core.Renderer;
import com.retrocam.core.RenderSettings;
import com.retrocam.core.TemporalState;
import com.retrocam.gl.ShaderProgram;
import com.retrocam.post.PostProcessStack;
import com.retrocam.post.StaticImageLoader;
import com.retrocam.scene.Scene;
import com.retrocam.scene.SceneUploader;
import com.retrocam.scene.SPPMManager;
import com.retrocam.scene.SceneEditor;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.opengl.GL;
import imgui.ImGui;

import static org.lwjgl.glfw.Callbacks.glfwFreeCallbacks;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_DEPTH_TEST;
import static org.lwjgl.opengl.GL11.GL_RENDERER;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL11.GL_TRIANGLES;
import static org.lwjgl.opengl.GL11.glBindTexture;
import static org.lwjgl.opengl.GL11.glClear;
import static org.lwjgl.opengl.GL11.glClearColor;
import static org.lwjgl.opengl.GL11.glDisable;
import static org.lwjgl.opengl.GL11.glDrawArrays;
import static org.lwjgl.opengl.GL11.glGetString;
import static org.lwjgl.opengl.GL11.glViewport;
import static org.lwjgl.opengl.GL13.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13.glActiveTexture;
import static org.lwjgl.opengl.GL20.GL_SHADING_LANGUAGE_VERSION;
import static org.lwjgl.opengl.GL30.glBindVertexArray;
import static org.lwjgl.opengl.GL30.glDeleteVertexArrays;
import static org.lwjgl.opengl.GL30.glGenVertexArrays;
import static org.lwjgl.opengl.GL43.*;
import static org.lwjgl.system.MemoryUtil.NULL;

public final class Main {

    private static final String WINDOW_TITLE = "RetroCam";

    // ── Core ──────────────────────────────────────────────────────────────────
    private long           window;
    private RenderSettings settings;
    private ImGuiLayer     imGui;
    private OrbitCamera    camera;
    private ThinLensCamera thinLens;
    private TemporalState  temporal;

    // ── Scene ─────────────────────────────────────────────────────────────────
    private SceneEditor   sceneEditor;
    private SceneUploader sceneUploader;

    // ── Rendering ─────────────────────────────────────────────────────────────
    private Renderer           renderer;
    private SPPMManager        sppmManager;
    private PostProcessStack   postStack;
    private StaticImageLoader  staticImageLoader;
    private ShaderProgram      displayShader;   // display.frag: ACES tonemap + gamma for screen
    private int                fullscreenVao;
    private com.retrocam.export.RenderPipeline renderPipeline;
    private com.retrocam.export.RenderContext  renderContext;

    // ── Settings snapshot for accumulation-reset detection ────────────────────
    // Phase 4: also track SA and LCA so optical changes trigger a clean restart.
    private float   prevFocalLength, prevFStop, prevFocusDist;
    private float   prevSaStrength;
    private float   prevLcaR, prevLcaG, prevLcaB;
    private boolean prevSppmEnabled;

    // ── Timing ────────────────────────────────────────────────────────────────
    private long   lastNanoTime  = System.nanoTime();
    private double fpsTimer      = 0.0;
    private int    fpsFrameCount = 0;
    private float  currentFps   = 0.0f;

    // ── Entry ─────────────────────────────────────────────────────────────────

    public static void main(String[] args) { new Main().run(); }
    private void run() { init(); loop(); shutdown(); }

    // ── Init ──────────────────────────────────────────────────────────────────

    private void init() {
        settings = new RenderSettings();
        initGlfw();
        createWindow();
        initOpenGL();
        initScene();
        initGpuResources();
        initImGui();
        camera.setImguiMouseGuard(() -> ImGui.getIO().getWantCaptureMouse());
        snapshotSettings();

        System.out.println("[RetroCam] Phase 6 ready — post-process stack (batch 1: p01, p02, p07, p09, p10, p20)");
        System.out.println("[RetroCam] GL  : " + glGetString(GL_RENDERER));
        System.out.println("[RetroCam] GLSL: " + glGetString(GL_SHADING_LANGUAGE_VERSION));
    }

    private void initGlfw() {
        GLFWErrorCallback.createPrint(System.err).set();
        if (!glfwInit()) throw new RuntimeException("Failed to init GLFW");
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        glfwWindowHint(GLFW_VISIBLE,   GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);
    }

    private void createWindow() {
        window = glfwCreateWindow(
            settings.displayWidth, settings.displayHeight,
            WINDOW_TITLE, NULL, NULL);
        if (window == NULL) throw new RuntimeException("Failed to create window");

        GLFWVidMode vid = glfwGetVideoMode(glfwGetPrimaryMonitor());
        if (vid != null) {
            glfwSetWindowPos(window,
                (vid.width()  - settings.displayWidth)  / 2,
                (vid.height() - settings.displayHeight) / 2);
        }

        glfwSetFramebufferSizeCallback(window, (w, width, height) -> {
            settings.displayWidth  = width;
            settings.displayHeight = height;
            glViewport(0, 0, width, height);
        });

        camera = new OrbitCamera();
        camera.registerCallbacks(window);

        thinLens = new ThinLensCamera();
        temporal = new TemporalState();

        glfwSetKeyCallback(window, (w, key, scancode, action, mods) -> {
            if (key == GLFW_KEY_ESCAPE && action == GLFW_PRESS)
                glfwSetWindowShouldClose(w, true);
        });

        glfwShowWindow(window);
        glfwMakeContextCurrent(window);
    }

    private void initOpenGL() {
        GL.createCapabilities();
        glClearColor(0f, 0f, 0f, 1f);
        glDisable(GL_DEPTH_TEST);
        glfwSwapInterval(settings.vSync ? 1 : 0);
    }

    private void initScene() {
        sceneEditor   = SceneEditor.createDefault();
        sceneUploader = new SceneUploader();
        sceneUploader.upload(sceneEditor.buildScene());
        sceneEditor.clearDirty();
    }

    private void initGpuResources() {
        fullscreenVao     = glGenVertexArrays();
        renderer          = new Renderer();
        sppmManager       = new SPPMManager(settings);
        postStack         = new PostProcessStack(fullscreenVao);
        staticImageLoader = new StaticImageLoader();
        displayShader     = ShaderProgram.createRender(
            "/shaders/fullscreen.vert", "/shaders/display.frag");
        renderPipeline = new com.retrocam.export.RenderPipeline();
    }

    private void initImGui() {
        imGui = new ImGuiLayer();
        imGui.init(window);
        // Build once so pipeline has all dependencies wired before any tick
        renderContext = new com.retrocam.export.RenderContext(
            renderer, sppmManager, postStack, sceneUploader,
            camera, thinLens, temporal, settings,
            displayShader, fullscreenVao);
        imGui.setRenderPipeline(renderPipeline);
    }

    // ── Loop ──────────────────────────────────────────────────────────────────

    private void loop() {
        while (!glfwWindowShouldClose(window)) {
            long now = System.nanoTime();
            float dt = Math.min((now - lastNanoTime) / 1_000_000_000f, 0.1f);
            lastNanoTime = now;

            glfwPollEvents();
            camera.update(dt, window);

            // Re-upload scene when the editor marks geometry or materials dirty
            if (sceneEditor.isDirty()) {
                sceneUploader.upload(sceneEditor.buildScene());
                renderer.reset();
                sppmManager.reset(settings);
                camera.clearDirty();
                snapshotSettings();
                sceneEditor.clearDirty();
            }

            // ── Phase 4: wire AF target each frame ────────────────────────────
            // The ImGui slider drives settings.focusDistM (the desired distance).
            // TemporalState tracks it with an IIR filter so the actual focal dist
            // used by the shader lags behind, simulating consumer AF behaviour.
            temporal.setFocalDistTarget(settings.focusDistM);
            temporal.update(dt, settings);

            glfwSwapInterval(settings.vSync ? 1 : 0);

            // Reset accumulation if camera moved or any optical setting changed
            if (camera.isDirty() || settingsChanged()) {
                renderer.reset();
                sppmManager.reset(settings);
                camera.clearDirty();
                snapshotSettings();
            }

            // ── Render pipeline / interactive loop ────────────────────────────────────
            int finalTexId;

            if (renderPipeline.isRunning()) {
                // Offline render pipeline owns all GPU work this tick.
                // Tick does one complete frame (all spp + post) and returns its output.
                finalTexId = renderPipeline.tick(renderContext, dt);
                if (finalTexId < 0) finalTexId = postStack.runOnAccum(
                    renderer.getAccumTexture(), renderer.getGBufferTexture(),
                    renderer.getTotalSamples(), settings.exposure, settings, temporal);

                // Update window title with pipeline progress
                glfwSetWindowTitle(window, String.format("%s  |  Rendering frame %d/%d  |  %.0f%%",
                    WINDOW_TITLE,
                    renderPipeline.getCurrentFrame(), renderPipeline.getTotalFrames(),
                    renderPipeline.getProgress() * 100f));

            } else {
                // Normal interactive loop
                if (!imGui.isTestModeActive()
                        && settings.sppmEnabled
                        && sceneUploader.getLightCount() > 0) {
                    sppmManager.tracePhotons(sceneUploader, settings);
                }
                if (!imGui.isTestModeActive()) {
                    renderer.render(sceneUploader, camera, thinLens, temporal, settings);
                }
                if (!imGui.isTestModeActive()
                        && settings.sppmEnabled
                        && sceneUploader.getLightCount() > 0) {
                    sppmManager.gatherRadiance(sceneUploader, camera,
                        renderer.getAccumTexture(), settings, renderer.getTotalSamples());
                    sppmManager.updateRadius(settings.sppmAlpha);
                }

                // Post-process stack (test image or accumulation)
                if (imGui.pollWantsLoadImage()) {
                    try {
                        staticImageLoader.load(imGui.getTestImagePath());
                        imGui.setTestModeActive(true);
                        renderer.reset();
                        sppmManager.reset(settings);
                    } catch (Exception ex) {
                        System.err.println("[RetroCam] Test image load failed: " + ex.getMessage());
                        imGui.setTestModeActive(false);
                    }
                }

                if (imGui.isTestModeActive() && staticImageLoader.isLoaded()) {
                    finalTexId = postStack.runOnImage(
                        staticImageLoader.texId(), settings, temporal);
                } else {
                    finalTexId = postStack.runOnAccum(
                        renderer.getAccumTexture(), renderer.getGBufferTexture(),
                        renderer.getTotalSamples(), settings.exposure, settings, temporal);
                }
            }

            // Increment frame counter each rendered frame (for noise seeds etc.)
            settings.frameIndex++;

            // Display result — restore display viewport first, since the post-stack
            // FBOs call glViewport(854, 480) internally and leave it at that size.
            glClear(GL_COLOR_BUFFER_BIT);
            glViewport(0, 0, settings.displayWidth, settings.displayHeight);
            drawBlit(finalTexId);

            // ImGui overlay
            imGui.beginFrame();
            imGui.setStats(renderer.getTotalSamples(), currentFps, temporal.agcGain);
            imGui.setSppmStats(sppmManager.getSearchRadius(), sppmManager.getIteration());
            imGui.render(settings, sceneEditor, dt);
            imGui.endFrame();

            glfwSwapBuffers(window);
            updateStats(dt);
        }
    }

    // ── Display ───────────────────────────────────────────────────────────────

    /**
     * Tonemap and display the post-stack output using display.frag (ACES + gamma).
     * p00_normalize already divided by sample count and applied exposure, so we
     * pass totalSamples=1 and exposure=1.0 to avoid double-dividing.
     */
    private void drawBlit(int texId) {
        displayShader.bind();
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, texId);
        displayShader.setInt("u_tex",          0);
        displayShader.setInt("u_totalSamples", 1);    // already normalised by p00
        displayShader.setFloat("u_exposure",   1.0f); // already applied by p00
        glBindVertexArray(fullscreenVao);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        glBindVertexArray(0);
        displayShader.unbind();
    }

    // ── Settings snapshot ─────────────────────────────────────────────────────

    /** Capture all values that, when changed, require accumulation to restart. */
    private void snapshotSettings() {
        prevFocalLength = settings.focalLengthMm;
        prevFStop       = settings.apertureFStop;
        prevFocusDist   = settings.focusDistM;
        prevSaStrength  = settings.saStrength;
        prevLcaR        = settings.lcaDelta[0];
        prevLcaG        = settings.lcaDelta[1];
        prevLcaB        = settings.lcaDelta[2];
        prevSppmEnabled = settings.sppmEnabled;
    }

    /**
     * Returns true if any optical setting has changed since the last snapshot.
     * Phase 4: SA strength and LCA deltas are now included — changing either
     * completely alters the per-channel focal distribution, so stale samples
     * must be discarded.
     */
    private boolean settingsChanged() {
        return settings.focalLengthMm  != prevFocalLength
            || settings.apertureFStop  != prevFStop
            || settings.focusDistM     != prevFocusDist
            || settings.saStrength     != prevSaStrength
            || settings.lcaDelta[0]    != prevLcaR
            || settings.lcaDelta[1]    != prevLcaG
            || settings.lcaDelta[2]    != prevLcaB
            || settings.sppmEnabled    != prevSppmEnabled;
    }

    // ── Stats ─────────────────────────────────────────────────────────────────

    private void updateStats(float dt) {
        fpsTimer      += dt;
        fpsFrameCount++;
        if (fpsTimer >= 1.0) {
            currentFps = fpsFrameCount / (float) fpsTimer;
            glfwSetWindowTitle(window, String.format(
                "%s  |  %d spp  |  %.1f fps",
                WINDOW_TITLE, renderer.getTotalSamples(), currentFps));
            fpsTimer      = 0;
            fpsFrameCount = 0;
        }
    }

    // ── Shutdown ──────────────────────────────────────────────────────────────

    private void shutdown() {
        imGui.destroy();
        renderer.destroy();
        sppmManager.destroy();
        postStack.destroy();
        staticImageLoader.destroy();
        displayShader.destroy();
        glDeleteVertexArrays(fullscreenVao);
        sceneUploader.destroy();

        glfwFreeCallbacks(window);
        glfwDestroyWindow(window);
        glfwTerminate();
        glfwSetErrorCallback(null).free();

        renderPipeline.cancel();

        System.out.println("[RetroCam] Shutdown clean.");
    }
}