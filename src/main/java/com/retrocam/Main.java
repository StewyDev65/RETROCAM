package com.retrocam;

import com.retrocam.camera.OrbitCamera;
import com.retrocam.core.ImGuiLayer;
import com.retrocam.core.RenderSettings;
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

    // ── Scene / BVH ───────────────────────────────────────────────────────────
    private Scene         scene;
    private SceneUploader sceneUploader;

    // ── GPU resources ─────────────────────────────────────────────────────────
    private ShaderProgram heatmapCompute;
    private ShaderProgram displayShader;
    private int           heatmapTexture;
    private int           fullscreenVao;

    // ── Timing ────────────────────────────────────────────────────────────────
    private long   lastNanoTime = System.nanoTime();
    private int    frameCount   = 0;
    private double fpsTimer     = 0.0;
    private float  currentFps   = 0.0f;

    // ── Entry point ───────────────────────────────────────────────────────────

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

        System.out.println("[RetroCam] Phase 2 ready - BVH heatmap");
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
            recreateHeatmapTexture();
        });

        camera = new OrbitCamera();
        camera.registerCallbacks(window);

        glfwSetKeyCallback(window, (w, key, scancode, action, mods) -> {
            if (key == GLFW_KEY_ESCAPE && action == GLFW_PRESS)
                glfwSetWindowShouldClose(w, true);
        });

        glfwShowWindow(window);
        glfwMakeContextCurrent(window);
    }

    private void initOpenGL() {
        GL.createCapabilities();
        glClearColor(0.02f, 0.02f, 0.04f, 1f);
        glDisable(GL_DEPTH_TEST);
        glfwSwapInterval(settings.vSync ? 1 : 0);
    }

    private void initScene() {
        scene         = Scene.createDefault();
        sceneUploader = new SceneUploader();
        sceneUploader.upload(scene);
    }

    private void initGpuResources() {
        fullscreenVao  = glGenVertexArrays();
        heatmapTexture = createHeatmapTexture(
            RenderSettings.RENDER_WIDTH, RenderSettings.RENDER_HEIGHT);
        heatmapCompute = ShaderProgram.createCompute("/shaders/bvh_heatmap.comp");
        displayShader  = ShaderProgram.createRender(
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
            float dt = (now - lastNanoTime) / 1_000_000_000f;
            lastNanoTime = now;
            updateStats(dt);

            camera.update(dt, window);
            glfwSwapInterval(settings.vSync ? 1 : 0);

            glClear(GL_COLOR_BUFFER_BIT);
            dispatchHeatmap();
            drawFullscreen();

            imGui.beginFrame();
            imGui.setStats(frameCount, currentFps, 1.0f);
            imGui.render(settings);
            imGui.endFrame();

            glfwSwapBuffers(window);
            glfwPollEvents();
            frameCount++;
        }
    }

    // ── Heatmap dispatch ──────────────────────────────────────────────────────

    private void dispatchHeatmap() {
        heatmapCompute.bind();

        glBindImageTexture(0, heatmapTexture, 0, false, 0, GL_WRITE_ONLY, GL_RGBA32F);

        float aspect = (float) RenderSettings.RENDER_WIDTH / RenderSettings.RENDER_HEIGHT;
        float[] view    = camera.getViewMatrix();
        float[] proj    = camera.getProjectionMatrix(aspect);
        float[] invView = invertOrthonormal(view);
        float[] invProj = invertPerspective(proj);

        heatmapCompute.setMat4("u_invView", invView);
        heatmapCompute.setMat4("u_invProj", invProj);
        float[] eye = camera.getEyePosition();
        heatmapCompute.setFloat3("u_eyePos", eye[0], eye[1], eye[2]);
        glUniform2i(glGetUniformLocation(heatmapCompute.id(), "u_resolution"),
                    RenderSettings.RENDER_WIDTH, RenderSettings.RENDER_HEIGHT);

        sceneUploader.bind();

        int gx = (RenderSettings.RENDER_WIDTH  + 15) / 16;
        int gy = (RenderSettings.RENDER_HEIGHT + 15) / 16;
        glDispatchCompute(gx, gy, 1);
        glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);

        sceneUploader.unbind();
        heatmapCompute.unbind();
    }

    // ── Fullscreen blit ───────────────────────────────────────────────────────

    private void drawFullscreen() {
        displayShader.bind();
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, heatmapTexture);
        displayShader.setInt("u_tex", 0);
        glBindVertexArray(fullscreenVao);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        glBindVertexArray(0);
        displayShader.unbind();
    }

    // ── Texture management ────────────────────────────────────────────────────

    private int createHeatmapTexture(int w, int h) {
        int tex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, tex);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA32F, w, h, 0,
                     GL_RGBA, GL_FLOAT, (java.nio.ByteBuffer) null);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glBindTexture(GL_TEXTURE_2D, 0);
        return tex;
    }

    private void recreateHeatmapTexture() {
        if (heatmapTexture != 0) glDeleteTextures(heatmapTexture);
        heatmapTexture = createHeatmapTexture(
            RenderSettings.RENDER_WIDTH, RenderSettings.RENDER_HEIGHT);
    }

    // ── Matrix math ───────────────────────────────────────────────────────────

    /** Inverts an orthonormal 4x4 view matrix (column-major). */
    private static float[] invertOrthonormal(float[] m) {
        float[] r = new float[16];
        r[ 0] = m[0]; r[ 1] = m[4]; r[ 2] = m[8];  r[ 3] = 0;
        r[ 4] = m[1]; r[ 5] = m[5]; r[ 6] = m[9];  r[ 7] = 0;
        r[ 8] = m[2]; r[ 9] = m[6]; r[10] = m[10]; r[11] = 0;
        r[12] = -(r[0]*m[12] + r[4]*m[13] + r[8] *m[14]);
        r[13] = -(r[1]*m[12] + r[5]*m[13] + r[9] *m[14]);
        r[14] = -(r[2]*m[12] + r[6]*m[13] + r[10]*m[14]);
        r[15] = 1;
        return r;
    }

    /**
     * Analytically inverts a standard perspective matrix (column-major).
     * P  = [sx,0,0,0, 0,sy,0,0, 0,0,A,-1, 0,0,B,0]
     * P^-1 = [1/sx,0,0,0, 0,1/sy,0,0, 0,0,0,1/B, 0,0,-1,A/B]
     */
    private static float[] invertPerspective(float[] p) {
        float[] r = new float[16];
        float sx = p[0], sy = p[5], A = p[10], B = p[14];
        r[ 0] = 1f / sx;
        r[ 5] = 1f / sy;
        r[11] = 1f / B;
        r[14] = -1f;
        r[15] = A / B;
        return r;
    }

    // ── Stats ─────────────────────────────────────────────────────────────────

    private void updateStats(float dt) {
        fpsTimer += dt;
        if (fpsTimer >= 1.0) {
            currentFps = frameCount / (float) fpsTimer;
            glfwSetWindowTitle(window, String.format(
                "%s  |  %d nodes  |  %.1f fps",
                WINDOW_TITLE, sceneUploader.getNodeCount(), currentFps));
            fpsTimer   = 0;
            frameCount = 0;
        }
    }

    // ── Shutdown ──────────────────────────────────────────────────────────────

    private void shutdown() {
        imGui.destroy();
        heatmapCompute.destroy();
        displayShader.destroy();
        glDeleteTextures(heatmapTexture);
        glDeleteVertexArrays(fullscreenVao);
        sceneUploader.destroy();

        glfwFreeCallbacks(window);
        glfwDestroyWindow(window);
        glfwTerminate();
        glfwSetErrorCallback(null).free();

        System.out.println("[RetroCam] Shutdown clean.");
    }
}