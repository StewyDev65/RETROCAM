package com.retrocam.camera;

/**
 * Six-degrees-of-freedom camera driven by a {@link CameraAnimation}.
 *
 * <h3>Design</h3>
 * The user establishes a "base" state (position + orientation) by capturing the
 * current {@link OrbitCamera} at the moment of HFCS import. At each evaluation
 * time the free camera:
 * <ol>
 *   <li>Evaluates the animation to get HitFilm-space position and orientation.</li>
 *   <li>Computes the <em>delta</em> position and <em>delta</em> rotation relative
 *       to frame 0, expressing both in the camera's local frame at frame 0.</li>
 *   <li>Applies those deltas to the base position and base orientation vectors,
 *       scaled by {@link #positionScale}.</li>
 * </ol>
 *
 * <h3>Coordinate mapping</h3>
 * HitFilm 3D uses Y-up, +Z toward viewer (camera default looks along −Z).
 * Our scene uses the same handedness. The mapping from HitFilm camera-local space
 * to our scene space uses the base camera's right/up/forward vectors as basis
 * vectors, so the user simply points their scene camera where they want the
 * recorded movement to "start from".
 *
 * <h3>Thread safety</h3>
 * Not thread-safe. All methods must be called from the GL thread.
 */
public final class FreeCamera implements CameraView {

    // ── Animation data ────────────────────────────────────────────────────────

    private CameraAnimation animation = null;

    /** Current playback time in seconds. Updated by Main each frame. */
    private float currentTime = 0f;

    // ── Base state (captured from OrbitCamera at import time) ─────────────────

    private final float[] basePos     = { 0f, 5f, 10f };
    private final float[] baseForward = { 0f, 0f, -1f };
    private final float[] baseUp      = { 0f, 1f,  0f };
    private final float[] baseRight   = { 1f, 0f,  0f };

    // ── Settings ──────────────────────────────────────────────────────────────

    /** Scales HitFilm pixel units to scene world units. Default 0.02. */
    public float positionScale = 0.02f;

    /** When true, the evaluated zoom is applied to {@code settings.focalLengthMm}. */
    public boolean applyZoomToFocalLength = true;

    // ── Evaluated state (recomputed on each evaluateAtTime call) ──────────────

    private final float[] eyePos  = { 0f, 5f, 10f };
    private final float[] forward = { 0f, 0f, -1f };
    private final float[] up      = { 0f, 1f,  0f };
    private final float[] right   = { 1f, 0f,  0f };

    /** Evaluated focal length in mm (from zoom track). */
    private float evaluatedFocalMm = 35f;

    private boolean dirty = true;

    // ── Base capture ──────────────────────────────────────────────────────────

    /**
     * Captures the current {@link OrbitCamera} state as the base for all
     * subsequent animation evaluations. Call this immediately after loading
     * an HFCS file so the animation plays out from the scene camera's current
     * position and orientation.
     */
    public void setBaseFromOrbit(OrbitCamera orbit) {
        float[] p = orbit.getEyePosition();
        float[] f = orbit.getCameraForward();
        float[] u = orbit.getCameraUp();
        float[] r = orbit.getCameraRight();
        System.arraycopy(p, 0, basePos,     0, 3);
        System.arraycopy(f, 0, baseForward, 0, 3);
        System.arraycopy(u, 0, baseUp,      0, 3);
        System.arraycopy(r, 0, baseRight,   0, 3);
        // Initialise evaluated state to base so the camera starts at the right place
        System.arraycopy(p, 0, eyePos,  0, 3);
        System.arraycopy(f, 0, forward, 0, 3);
        System.arraycopy(u, 0, up,      0, 3);
        System.arraycopy(r, 0, right,   0, 3);
        dirty = true;
    }

    /** Sets the base position directly (for manual UI override). */
    public void setBasePosition(float x, float y, float z) {
        basePos[0] = x; basePos[1] = y; basePos[2] = z;
        dirty = true;
    }

    // ── Animation attachment ──────────────────────────────────────────────────

    public void setAnimation(CameraAnimation anim) {
        this.animation   = anim;
        this.currentTime = 0f;
        dirty = true;
    }

    public CameraAnimation getAnimation() { return animation; }
    public boolean hasAnimation()         { return animation != null; }

    // ── Per-frame update ──────────────────────────────────────────────────────

    /**
     * Evaluates the animation at {@code timeSec} and updates internal vectors.
     * Must be called from the GL thread each frame before rendering.
     *
     * @param timeSec   canonical playback time in seconds
     * @param settings  if {@link #applyZoomToFocalLength} is true, the evaluated
     *                  focal length is written here
     */
    public void evaluateAtTime(float timeSec, com.retrocam.core.RenderSettings settings) {
        if (animation == null) return;

        boolean timeChanged = (timeSec != currentTime);
        currentTime = timeSec;

        CameraAnimation.Frame f0 = animation.frame0();
        CameraAnimation.Frame ft = animation.evaluate(timeSec);

        // ── Orientation delta ────────────────────────────────────────────────
        // Build rotation matrices for frame 0 and frame t (Y→X→Z Euler order,
        // matching HitFilm's convention for 3D camera orientation).
        float[] R0 = eulerYXZtoMatrix(f0.eulerX, f0.eulerY, f0.eulerZ);
        float[] Rt = eulerYXZtoMatrix(ft.eulerX, ft.eulerY, ft.eulerZ);

        // Delta rotation in HitFilm world space: R_delta = Rt * R0^T
        // (for orthogonal matrices, R^-1 = R^T)
        float[] R0t     = transpose3(R0);
        float[] deltaR  = mul3(Rt, R0t);

        // HitFilm camera at identity looks along −Z.
        // The columns of deltaR applied to identity basis vectors give us the
        // new camera axes relative to frame-0 orientation, in HitFilm cam-local space.
        //
        // Mapping from HitFilm cam-local → our scene:
        //   HF local +X  ↔  baseRight
        //   HF local +Y  ↔  baseUp
        //   HF local −Z  ↔  baseForward  (HF camera looks −Z)
        //
        // New forward = deltaR * (0,0,−1) expressed in our scene:
        float hfFwdX = -deltaR[2],  hfFwdY = -deltaR[5],  hfFwdZ = -deltaR[8];
        float hfUpX  =  deltaR[1],  hfUpY  =  deltaR[4],  hfUpZ  =  deltaR[7];

        for (int i = 0; i < 3; i++) {
            forward[i] = hfFwdX * baseRight[i] + hfFwdY * baseUp[i] - hfFwdZ * baseForward[i];
            up[i]      = hfUpX  * baseRight[i] + hfUpY  * baseUp[i] - hfUpZ  * baseForward[i];
        }
        normalize(forward);
        normalize(up);
        cross(forward, up, right);
        normalize(right);
        // Re-orthogonalise up
        cross(right, forward, up);
        normalize(up);

        // ── Position delta ───────────────────────────────────────────────────
        // Express the HitFilm world-space displacement in camera-local frame at t=0
        float dx = ft.posX - f0.posX;
        float dy = ft.posY - f0.posY;
        float dz = ft.posZ - f0.posZ;

        // Project onto HitFilm frame-0 camera axes to get camera-local displacement
        //   R0 rows = [right, up, -forward] axes of HitFilm camera at t=0
        //   d_local.x = dot(d_hf, row0_R0), etc.
        float dlX = R0[0]*dx + R0[1]*dy + R0[2]*dz;  // along HF cam-local right
        float dlY = R0[3]*dx + R0[4]*dy + R0[5]*dz;  // along HF cam-local up
        float dlZ = R0[6]*dx + R0[7]*dy + R0[8]*dz;  // along HF cam-local back (+Z = back, −Z = fwd)

        // Map to our scene space:
        //   cam-local +X → baseRight, +Y → baseUp, −Z → baseForward
        float s = positionScale;
        for (int i = 0; i < 3; i++) {
            eyePos[i] = basePos[i]
                + dlX *  baseRight[i]   * s
                + dlY *  baseUp[i]      * s
                + dlZ * -baseForward[i] * s;  // negate: cam-local +Z is backward
        }

        // ── Focal length ─────────────────────────────────────────────────────
        if (applyZoomToFocalLength) {
            evaluatedFocalMm = animation.zoomToFocalMm(ft.zoomPx);
            settings.focalLengthMm = evaluatedFocalMm;
        }

        if (timeChanged) dirty = true;
    }

    // ── CameraView ────────────────────────────────────────────────────────────

    @Override public float[] getEyePosition()    { return eyePos.clone(); }
    @Override public float[] getCameraForward()  { return forward.clone(); }
    @Override public float[] getCameraRight()    { return right.clone(); }
    @Override public float[] getCameraUp()       { return up.clone(); }
    @Override public boolean isDirty()           { return dirty; }
    @Override public void    clearDirty()        { dirty = false; }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public float   getCurrentTime()     { return currentTime; }
    public float   getEvaluatedFocalMm(){ return evaluatedFocalMm; }
    public float[] getBasePos()         { return basePos.clone(); }

    // ── Math helpers ─────────────────────────────────────────────────────────

    /**
     * Builds a row-major 3×3 rotation matrix from Euler angles in degrees,
     * applied in Y→X→Z order (HitFilm 3D convention).
     *
     * mat[row*3 + col]
     */
    static float[] eulerYXZtoMatrix(float degX, float degY, float degZ) {
        float rx = (float) Math.toRadians(degX);
        float ry = (float) Math.toRadians(degY);
        float rz = (float) Math.toRadians(degZ);

        float cx = (float)Math.cos(rx), sx = (float)Math.sin(rx);
        float cy = (float)Math.cos(ry), sy = (float)Math.sin(ry);
        float cz = (float)Math.cos(rz), sz = (float)Math.sin(rz);

        // Ry
        float[] Ry = { cy, 0, sy,  0, 1, 0,  -sy, 0, cy };
        // Rx
        float[] Rx = { 1, 0, 0,  0, cx, -sx,  0, sx, cx };
        // Rz
        float[] Rz = { cz, -sz, 0,  sz, cz, 0,  0, 0, 1 };

        return mul3(mul3(Ry, Rx), Rz);
    }

    /** Multiplies two row-major 3×3 matrices. */
    static float[] mul3(float[] A, float[] B) {
        float[] C = new float[9];
        for (int r = 0; r < 3; r++)
            for (int c = 0; c < 3; c++)
                for (int k = 0; k < 3; k++)
                    C[r*3+c] += A[r*3+k] * B[k*3+c];
        return C;
    }

    /** Transposes a row-major 3×3 matrix. */
    static float[] transpose3(float[] M) {
        return new float[]{
            M[0], M[3], M[6],
            M[1], M[4], M[7],
            M[2], M[5], M[8]
        };
    }

    /** Normalises a 3-element vector in-place. */
    private static void normalize(float[] v) {
        float len = (float) Math.sqrt(v[0]*v[0] + v[1]*v[1] + v[2]*v[2]);
        if (len > 1e-6f) { v[0] /= len; v[1] /= len; v[2] /= len; }
    }

    /** Writes cross(a, b) into out. */
    private static void cross(float[] a, float[] b, float[] out) {
        out[0] = a[1]*b[2] - a[2]*b[1];
        out[1] = a[2]*b[0] - a[0]*b[2];
        out[2] = a[0]*b[1] - a[1]*b[0];
    }
}
