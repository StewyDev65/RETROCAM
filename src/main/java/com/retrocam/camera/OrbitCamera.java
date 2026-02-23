package com.retrocam.camera;

import static org.lwjgl.glfw.GLFW.*;

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
 *   Q/E                → move target down / up
 *   R                  → reset to defaults
 *
 * Call {@link #registerCallbacks(long)} once after window creation,
 * then {@link #update(float)} each frame.
 * Matrices are provided via {@link #getViewMatrix()} and
 * {@link #getProjectionMatrix(float)}.
 */
public final class OrbitCamera {

    // ── State ─────────────────────────────────────────────────────────────────
    private float theta   = 0.3f;        // horizontal angle (radians)
    private float phi     = 0.4f;        // vertical angle (radians, clamped)
    private float radius  = 12.0f;       // distance from target

    private float targetX = 0f;
    private float targetY = 3.0f;        // look at mid-room height
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

    // ── Computed matrices (row-major, uploaded as uniforms) ───────────────────
    private final float[] viewMatrix = new float[16];
    private final float[] projMatrix = new float[16];

    // ── GLFW callbacks ────────────────────────────────────────────────────────

    public void registerCallbacks(long window) {
        glfwSetMouseButtonCallback(window, (win, button, action, mods) -> {
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

            if (leftDown && !middleDown) {
                // Orbit
                theta -= (float)(dx * orbitSensitivity);
                phi   -= (float)(dy * orbitSensitivity);
                phi    = Math.max(0.02f, Math.min((float)Math.PI - 0.02f, phi));
            }

            if (middleDown || (leftDown && rightDown)) {
                // Pan (move target in camera's right/up plane)
                float[] right = cameraRight();
                float[] up    = cameraUp();
                float   scale = radius * panSensitivity;
                targetX += (-right[0] * (float)dx + up[0] * (float)dy) * scale;
                targetY += (-right[1] * (float)dx + up[1] * (float)dy) * scale;
                targetZ += (-right[2] * (float)dx + up[2] * (float)dy) * scale;
            }

            if (rightDown && !middleDown && !leftDown) {
                // Right-drag zoom
                radius += (float)(dy * zoomSensitivity);
                radius  = Math.max(0.5f, radius);
            }
        });

        glfwSetScrollCallback(window, (win, xOff, yOff) -> {
            radius -= (float)(yOff * scrollZoomStep);
            radius  = Math.max(0.5f, radius);
        });
    }

    // ── Per-frame update ──────────────────────────────────────────────────────

    public void update(float dt, long window) {
        float speed = moveSensitivity * dt;
        float[] fwd   = cameraForwardFlat();
        float[] right = cameraRight();

        if (glfwGetKey(window, GLFW_KEY_W) == GLFW_PRESS) {
            targetX += fwd[0] * speed; targetY += fwd[1] * speed; targetZ += fwd[2] * speed;
        }
        if (glfwGetKey(window, GLFW_KEY_S) == GLFW_PRESS) {
            targetX -= fwd[0] * speed; targetY -= fwd[1] * speed; targetZ -= fwd[2] * speed;
        }
        if (glfwGetKey(window, GLFW_KEY_A) == GLFW_PRESS) {
            targetX -= right[0] * speed; targetZ -= right[2] * speed;
        }
        if (glfwGetKey(window, GLFW_KEY_D) == GLFW_PRESS) {
            targetX += right[0] * speed; targetZ += right[2] * speed;
        }
        if (glfwGetKey(window, GLFW_KEY_Q) == GLFW_PRESS) targetY -= speed;
        if (glfwGetKey(window, GLFW_KEY_E) == GLFW_PRESS) targetY += speed;
        if (glfwGetKey(window, GLFW_KEY_R) == GLFW_PRESS) reset();

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

    // ── Internals ─────────────────────────────────────────────────────────────

    private void reset() {
        theta = 0.3f; phi = 0.4f; radius = 12f;
        targetX = 0f; targetY = 3f; targetZ = 0f;
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