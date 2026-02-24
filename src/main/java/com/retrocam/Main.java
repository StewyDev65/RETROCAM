package com.retrocam;

import com.retrocam.camera.OrbitCamera;
import com.retrocam.camera.ThinLensCamera;
import com.retrocam.core.ImGuiLayer;
import com.retrocam.core.Renderer;
import com.retrocam.core.RenderSettings;
import com.retrocam.core.TemporalState;
import com.retrocam.gl.ShaderProgram;
import com.retrocam.scene.Scene;
import com.retrocam.scene.SceneUploader;
import com.retrocam.scene.SPPMManager;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.opengl.GL;

import static org.lwjgl.glfw.Callbacks.glfwFreeCallbacks;
import static org.lwjgl.glfw.GLFW.*;
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
    private Scene         scene;
    private SceneUploader sceneUploader;

    // ── Rendering ─────────────────────────────────────────────────────────────
    private Renderer      renderer;
    private SPPMManager   sppmManager;
    private ShaderProgram displayShader;
    private int           fullscreenVao;

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
        snapshotSettings();

        System.out.println("[RetroCam] Phase 4 ready — optical effects (SA, LCA, AF lag, polygon bokeh)");
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
        scene         = Scene.createDefault();
        sceneUploader = new SceneUploader();
        sceneUploader.upload(scene);
    }

    private void initGpuResources() {
        fullscreenVao = glGenVertexArrays();
        renderer      = new Renderer();
        sppmManager   = new SPPMManager(settings);
        displayShader = ShaderProgram.createRender(
            "/shaders/fullscreen.vert", "/shaders/display.frag");
    }

    private void initImGui() {
        imGui = new ImGuiLayer();
        imGui.init(window);
    }

    // ── Loop ──────────────────────────────────────────────────────────────────

    private void loop() {
        while (!glfwWindowShouldClose(window)) {
            long now = System.nanoTime();
            float dt = Math.min((now - lastNanoTime) / 1_000_000_000f, 0.1f);
            lastNanoTime = now;

            glfwPollEvents();
            camera.update(dt, window);

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

            // Per-frame loop order (spec §10):
            // 1. Temporal update (already done above via temporal.update)
            // 2. SPPM photon trace
            if (settings.sppmEnabled && sceneUploader.getLightCount() > 0) {
                sppmManager.tracePhotons(sceneUploader, settings);
            }
            // 3. Path trace accumulation
            renderer.render(sceneUploader, camera, thinLens, temporal, settings);
            // 4. SPPM gather (adds caustic radiance to accumulation buffer)
            if (settings.sppmEnabled && sceneUploader.getLightCount() > 0) {
                sppmManager.gatherRadiance(sceneUploader, camera,
                    renderer.getAccumTexture(), settings, renderer.getTotalSamples());
                // 5. Shrink search radius
                sppmManager.updateRadius(settings.sppmAlpha);
            }

            // Display result
            glClear(GL_COLOR_BUFFER_BIT);
            drawFullscreen();

            // ImGui overlay
            imGui.beginFrame();
            imGui.setStats(renderer.getTotalSamples(), currentFps, temporal.agcGain);
            imGui.setSppmStats(sppmManager.getSearchRadius(), sppmManager.getIteration());
            imGui.render(settings);
            imGui.endFrame();

            glfwSwapBuffers(window);
            updateStats(dt);
        }
    }

    // ── Display ───────────────────────────────────────────────────────────────

    private void drawFullscreen() {
        displayShader.bind();
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, renderer.getAccumTexture());
        displayShader.setInt("u_tex",          0);
        displayShader.setInt("u_totalSamples", renderer.getTotalSamples());
        displayShader.setFloat("u_exposure",   settings.exposure);

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
        displayShader.destroy();
        glDeleteVertexArrays(fullscreenVao);
        sceneUploader.destroy();

        glfwFreeCallbacks(window);
        glfwDestroyWindow(window);
        glfwTerminate();
        glfwSetErrorCallback(null).free();

        System.out.println("[RetroCam] Shutdown clean.");
    }
}