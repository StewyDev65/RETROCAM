package com.retrocam.camera;

import static org.lwjgl.glfw.GLFW.*;
import com.retrocam.keyframe.Keyframeable;

/**
 * Orbit (turntable) camera for interactive scene preview.
 *
 * Controls:
 *   Left-drag          → orbit (theta / phi)
 *   Middle-drag        → pan (move target laterally)
 *   Right-drag         → zoom (change radius)
 *   Scroll wheel       → zoom
 *   W/S                → move target forward / backward
 *   A/D                → move target left / right
 *   CNTL/SPACE         → move target down / up
 *   R                  → reset to defaults
 *
 * Call {@link #registerCallbacks(long)} once after window creation,
 * then {@link #update(float)} each frame.
 * Matrices are provided via {@link #getViewMatrix()} and
 * {@link #getProjectionMatrix(float)}.
 */
public final class OrbitCamera implements Keyframeable {

    // ── State ─────────────────────────────────────────────────────────────────
    private float theta   = (float)(Math.PI / 2.0); // face along -Z into the scene
    private float phi     = 1.2f;                    // slightly above horizontal
    private float radius  = 10.0f;                   // in front of open face

    private float targetX = 0f;
    private float targetY = 5.0f;        // mid-room height (room is 0-10 in Y)
    private float targetZ = 0f;

    private float fovY    = 60.0f;       // vertical FOV in degrees

    // ── Sensitivity ──────────────────────────────────────────────────────────
    private float orbitSensitivity = 0.005f;
    private float panSensitivity   = 0.008f;
    private float zoomSensitivity  = 0.3f;
    private float scrollZoomStep   = 0.8f;
    private float moveSensitivity  = 5.0f;

    // ── Mouse tracking ────────────────────────────────────────────────────────
    private double lastMouseX, lastMouseY;
    private boolean leftDown, middleDown, rightDown;

    // ── ImGui mouse guard ─────────────────────────────────────────────────────
    private java.util.function.BooleanSupplier imguiWantsMouse = () -> false;

    public void setImguiMouseGuard(java.util.function.BooleanSupplier guard) {
        this.imguiWantsMouse = guard;
    }

    // ── Computed matrices (row-major, uploaded as uniforms) ───────────────────
    private final float[] viewMatrix = new float[16];
    private final float[] projMatrix = new float[16];

    // ── GLFW callbacks ────────────────────────────────────────────────────────

    public void registerCallbacks(long window) {
        glfwSetMouseButtonCallback(window, (win, button, action, mods) -> {
            if (imguiWantsMouse.getAsBoolean()) {
                // release all buttons so dragging doesn't bleed through
                leftDown = middleDown = rightDown = false;
                return;
            }
            boolean pressed = action == GLFW_PRESS;
            if (button == GLFW_MOUSE_BUTTON_LEFT)   leftDown   = pressed;
            if (button == GLFW_MOUSE_BUTTON_MIDDLE) middleDown = pressed;
            if (button == GLFW_MOUSE_BUTTON_RIGHT)  rightDown  = pressed;
            if (pressed) {
                double[] mx = new double[1], my = new double[1];
                glfwGetCursorPos(win, mx, my);
                lastMouseX = mx[0];
                lastMouseY = my[0];
            }
        });

        glfwSetCursorPosCallback(window, (win, x, y) -> {
            double dx = x - lastMouseX;
            double dy = y - lastMouseY;
            lastMouseX = x;
            lastMouseY = y;
            if (imguiWantsMouse.getAsBoolean()) return;

            if (leftDown && !middleDown) {
                theta -= (float)(dx * orbitSensitivity);
                phi   -= (float)(dy * orbitSensitivity);
                phi    = Math.max(0.02f, Math.min((float)Math.PI - 0.02f, phi));
                dirty  = true;
            }

            if (middleDown || (leftDown && rightDown)) {
                float[] right = cameraRight();
                float[] up    = cameraUp();
                float   scale = radius * panSensitivity;
                targetX += (-right[0] * (float)dx + up[0] * (float)dy) * scale;
                targetY += (-right[1] * (float)dx + up[1] * (float)dy) * scale;
                targetZ += (-right[2] * (float)dx + up[2] * (float)dy) * scale;
                dirty   = true;
            }

            if (rightDown && !middleDown && !leftDown) {
                radius += (float)(dy * zoomSensitivity);
                radius  = Math.max(0.5f, radius);
                dirty   = true;
            }
        });

        glfwSetScrollCallback(window, (win, xOff, yOff) -> {
            if (imguiWantsMouse.getAsBoolean()) return;
            radius -= (float)(yOff * scrollZoomStep);
            radius  = Math.max(0.5f, radius);
            dirty   = true;
        });
    }

    // ── Per-frame update ──────────────────────────────────────────────────────

    public void update(float dt, long window) {
        float speed = moveSensitivity * dt;
        float[] fwd   = cameraForwardFlat();
        float[] right = cameraRight();
        boolean moved = false;

        if (glfwGetKey(window, GLFW_KEY_W) == GLFW_PRESS) {
            targetX += fwd[0] * speed; targetY += fwd[1] * speed; targetZ += fwd[2] * speed; moved = true;
        }
        if (glfwGetKey(window, GLFW_KEY_S) == GLFW_PRESS) {
            targetX -= fwd[0] * speed; targetY -= fwd[1] * speed; targetZ -= fwd[2] * speed; moved = true;
        }
        if (glfwGetKey(window, GLFW_KEY_A) == GLFW_PRESS) {
            targetX -= right[0] * speed; targetZ -= right[2] * speed; moved = true;
        }
        if (glfwGetKey(window, GLFW_KEY_D) == GLFW_PRESS) {
            targetX += right[0] * speed; targetZ += right[2] * speed; moved = true;
        }
        if (glfwGetKey(window, GLFW_KEY_LEFT_CONTROL) == GLFW_PRESS) { targetY -= speed; moved = true; }
        if (glfwGetKey(window, GLFW_KEY_SPACE) == GLFW_PRESS) { targetY += speed; moved = true; }
        if (glfwGetKey(window, GLFW_KEY_R) == GLFW_PRESS) { reset(); moved = true; }
        if (moved) dirty = true;

        rebuildView();
    }

    // ── Matrix access ─────────────────────────────────────────────────────────

    public float[] getViewMatrix()                          { return viewMatrix; }
    public float[] getProjectionMatrix(float aspect)       { rebuildProj(aspect); return projMatrix; }
    public float   getFovY()                               { return fovY; }
    public float   getRadius()                             { return radius; }

    /** Eye position in world space. */
    public float[] getEyePosition() {
        float sinPhi = (float) Math.sin(phi);
        return new float[]{
            targetX + radius * sinPhi * (float) Math.cos(theta),
            targetY + radius * (float) Math.cos(phi),
            targetZ + radius * sinPhi * (float) Math.sin(theta)
        };
    }

    // ── Camera basis (world space) ────────────────────────────────────────────

    /** Forward vector (from eye toward target). */
    public float[] getCameraForward() {
        float[] eye = getEyePosition();
        float fx = targetX - eye[0], fy = targetY - eye[1], fz = targetZ - eye[2];
        float len = (float) Math.sqrt(fx*fx + fy*fy + fz*fz);
        return new float[]{ fx/len, fy/len, fz/len };
    }

    /** Right vector (perpendicular to forward and world-up). */
    public float[] getCameraRight() { return cameraRight(); }

    /** Up vector (perpendicular to forward and right). */
    public float[] getCameraUp() { return cameraUp(); }

    // ── Dirty flag (signals accumulation buffer needs reset) ──────────────────

    private boolean dirty = true;

    public boolean isDirty()  { return dirty; }
    public void clearDirty()  { dirty = false; }

    // ── Keyframeable ──────────────────────────────────────────────────────────

    private static final String[] KF_NAMES = {
        "cam.theta", "cam.phi", "cam.radius",
        "cam.targetX", "cam.targetY", "cam.targetZ", "cam.fovY"
    };
    private static final String[] KF_DISPLAY = {
        "Orbit Angle (θ)", "Elevation (φ)", "Distance",
        "Target X", "Target Y", "Target Z", "Field of View"
    };

    @Override public String[] getKeyframeablePropertyNames()        { return KF_NAMES; }
    @Override public String[] getKeyframeablePropertyDisplayNames() { return KF_DISPLAY; }

    @Override
    public float getKeyframeableProperty(String name) {
        return switch (name) {
            case "cam.theta"   -> theta;
            case "cam.phi"     -> phi;
            case "cam.radius"  -> radius;
            case "cam.targetX" -> targetX;
            case "cam.targetY" -> targetY;
            case "cam.targetZ" -> targetZ;
            case "cam.fovY"    -> fovY;
            default -> 0f;
        };
    }

    @Override
    public void setKeyframeableProperty(String name, float value) {
        switch (name) {
            case "cam.theta"   -> { theta   = value; dirty = true; }
            case "cam.phi"     -> { phi     = Math.max(0.02f, Math.min((float)Math.PI - 0.02f, value)); dirty = true; }
            case "cam.radius"  -> { radius  = Math.max(0.5f, value); dirty = true; }
            case "cam.targetX" -> { targetX = value; dirty = true; }
            case "cam.targetY" -> { targetY = value; dirty = true; }
            case "cam.targetZ" -> { targetZ = value; dirty = true; }
            case "cam.fovY"    -> { fovY    = Math.max(5f, Math.min(150f, value)); dirty = true; }
        }
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private void reset() {
        theta = (float)(Math.PI / 2.0); phi = 1.2f; radius = 10f;
        targetX = 0f; targetY = 5f; targetZ = 0f;
    }

    private void rebuildView() {
        float[] eye = getEyePosition();
        float ex = eye[0], ey = eye[1], ez = eye[2];

        // Forward = target − eye, normalised
        float fx = targetX - ex, fy = targetY - ey, fz = targetZ - ez;
        float fl = (float) Math.sqrt(fx*fx + fy*fy + fz*fz);
        fx /= fl; fy /= fl; fz /= fl;

        // Right = forward × world-up
        float rx = fy*0 - fz*1, ry = fz*0 - fx*0, rz = fx*1 - fy*0; // cross(f, [0,1,0])
        float rl = (float) Math.sqrt(rx*rx + ry*ry + rz*rz);
        rx /= rl; ry /= rl; rz /= rl;

        // Up = right × forward
        float ux = ry*fz - rz*fy, uy = rz*fx - rx*fz, uz = rx*fy - ry*fx;

        // Column-major view matrix (OpenGL convention)
        viewMatrix[ 0] = rx; viewMatrix[ 1] = ux; viewMatrix[ 2] = -fx; viewMatrix[ 3] = 0;
        viewMatrix[ 4] = ry; viewMatrix[ 5] = uy; viewMatrix[ 6] = -fy; viewMatrix[ 7] = 0;
        viewMatrix[ 8] = rz; viewMatrix[ 9] = uz; viewMatrix[10] = -fz; viewMatrix[11] = 0;
        viewMatrix[12] = -(rx*ex + ry*ey + rz*ez);
        viewMatrix[13] = -(ux*ex + uy*ey + uz*ez);
        viewMatrix[14] =   fx*ex + fy*ey + fz*ez;
        viewMatrix[15] = 1;
    }

    private void rebuildProj(float aspect) {
        float near = 0.1f, far = 200f;
        float f = 1f / (float) Math.tan(Math.toRadians(fovY) * 0.5f);
        for (int i = 0; i < 16; i++) projMatrix[i] = 0;
        projMatrix[ 0] = f / aspect;
        projMatrix[ 5] = f;
        projMatrix[10] = (far + near) / (near - far);
        projMatrix[11] = -1;
        projMatrix[14] = 2 * far * near / (near - far);
    }

    /** Camera right vector in world space. */
    private float[] cameraRight() {
        float[] eye = getEyePosition();
        float fx = targetX-eye[0], fy = targetY-eye[1], fz = targetZ-eye[2];
        float fl = (float)Math.sqrt(fx*fx+fy*fy+fz*fz);
        fx/=fl; fy/=fl; fz/=fl;
        float rx = fy*0-fz*1, ry = fz*0-fx*0, rz = fx*1-fy*0;
        float rl = (float)Math.sqrt(rx*rx+ry*ry+rz*rz);
        return new float[]{rx/rl, ry/rl, rz/rl};
    }

    /** Camera up vector in world space. */
    private float[] cameraUp() {
        float[] r = cameraRight();
        float[] eye = getEyePosition();
        float fx=targetX-eye[0], fy=targetY-eye[1], fz=targetZ-eye[2];
        float fl=(float)Math.sqrt(fx*fx+fy*fy+fz*fz); fx/=fl;fy/=fl;fz/=fl;
        return new float[]{r[1]*fz-r[2]*fy, r[2]*fx-r[0]*fz, r[0]*fy-r[1]*fx};
    }

    /** Flattened forward on the XZ plane (for WASD movement). */
    private float[] cameraForwardFlat() {
        float fx = (float)Math.sin(phi) * (float)Math.cos(theta);
        float fz = (float)Math.sin(phi) * (float)Math.sin(theta);
        float len = (float)Math.sqrt(fx*fx+fz*fz);
        if (len < 1e-6f) return new float[]{0,0,-1};
        return new float[]{-fx/len, 0, -fz/len};
    }
}