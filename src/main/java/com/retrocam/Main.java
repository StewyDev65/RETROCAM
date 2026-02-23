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
    private ShaderProgram displayShader;
    private int           fullscreenVao;

    // ── Previous settings snapshot (detect changes that need accum reset) ─────
    private float prevFocalLength, prevFStop, prevFocusDist;

    // ── Timing ────────────────────────────────────────────────────────────────
    private long   lastNanoTime  = System.nanoTime();
    private double fpsTimer      = 0.0;
    private int    fpsFrameCount = 0;
    private float  currentFps    = 0.0f;

    // ── Entry ─────────────────────────────────────────────────────────────────

    public static void main(String[] args) { new Main().run(); }
    private void run() { init(); loop(); shutdown(); }

    // ── Init ─────────────────────────────────────────────────────────────────

    private void init() {
        settings = new RenderSettings();
        initGlfw();
        createWindow();
        initOpenGL();
        initScene();
        initGpuResources();
        initImGui();
        snapshotSettings();

        System.out.println("[RetroCam] Phase 3 ready — path tracer");
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
        displayShader = ShaderProgram.createRender(
            "/shaders/fullscreen.vert", "/shaders/display.frag");
    }

    private void initImGui() {
        imGui = new ImGuiLayer();
        imGui.init(window);
    }

    // ── Loop ─────────────────────────────────────────────────────────────────

    private void loop() {
        while (!glfwWindowShouldClose(window)) {
            long now = System.nanoTime();
            float dt = Math.min((now - lastNanoTime) / 1_000_000_000f, 0.1f);
            lastNanoTime = now;

            glfwPollEvents();
            camera.update(dt, window);
            temporal.update(dt, settings);
            glfwSwapInterval(settings.vSync ? 1 : 0);

            // Reset accumulation if camera moved or lens settings changed
            if (camera.isDirty() || settingsChanged()) {
                renderer.reset();
                camera.clearDirty();
                snapshotSettings();
            }

            // Render one path-trace pass (accumulate)
            renderer.render(sceneUploader, camera, thinLens, temporal, settings);

            // Display result
            glClear(GL_COLOR_BUFFER_BIT);
            drawFullscreen();

            // ImGui overlay
            float samplesPerSec = renderer.getTotalSamples() / Math.max((float) fpsTimer, 1e-3f);
            imGui.beginFrame();
            imGui.setStats(renderer.getTotalSamples(), currentFps, temporal.agcGain);
            imGui.render(settings);
            imGui.endFrame();

            glfwSwapBuffers(window);

            updateStats(dt);
        }
    }

    // ── Display (blit accum texture → screen with tonemapping) ───────────────

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

    // ── Settings change detection (triggers accum reset) ─────────────────────

    private void snapshotSettings() {
        prevFocalLength = settings.focalLengthMm;
        prevFStop       = settings.apertureFStop;
        prevFocusDist   = settings.focusDistM;
    }

    private boolean settingsChanged() {
        return settings.focalLengthMm != prevFocalLength
            || settings.apertureFStop != prevFStop
            || settings.focusDistM    != prevFocusDist;
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