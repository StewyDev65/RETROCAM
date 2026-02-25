package com.retrocam.camera;

/**
 * Common contract for all camera implementations.
 *
 * {@link OrbitCamera} and {@link FreeCamera} both implement this interface so
 * that {@link com.retrocam.camera.ThinLensCamera}, {@link com.retrocam.core.Renderer},
 * and {@link com.retrocam.export.RenderPipeline} can work with either without
 * knowing the concrete type.
 *
 * All returned float[] arrays are freshly allocated on each call — callers
 * should not cache the references.
 */
public interface CameraView {

    /** Eye position in world space. */
    float[] getEyePosition();

    /** Unit forward vector (from eye toward look target). */
    float[] getCameraForward();

    /** Unit right vector (perpendicular to forward and world-up). */
    float[] getCameraRight();

    /** Unit up vector (perpendicular to forward and right). */
    float[] getCameraUp();

    /**
     * Returns true if camera state changed this frame and the path-trace
     * accumulation buffer must be discarded.
     */
    boolean isDirty();

    /** Clears the dirty flag after the accumulation buffer has been reset. */
    void clearDirty();
}
