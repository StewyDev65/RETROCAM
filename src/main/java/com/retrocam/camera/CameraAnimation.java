package com.retrocam.camera;

/**
 * Immutable, time-sampled camera animation parsed from an HFCS file.
 *
 * <h3>Coordinate system</h3>
 * All data is stored in <em>HitFilm world space</em> exactly as read from the file:
 * <ul>
 *   <li>Y-up, right-handed.</li>
 *   <li>+Z points toward the viewer (camera default looks along −Z).</li>
 *   <li>Positions are in HitFilm pixels at the source composition resolution.</li>
 *   <li>Orientation values are Euler angles in degrees, applied in Y→X→Z order.</li>
 *   <li>Zoom is the focal length in pixels at the source composition height.</li>
 * </ul>
 *
 * Conversion to scene units and re-mapping to our camera's local frame is handled
 * by {@link FreeCamera}, not here.
 *
 * <h3>Interpolation</h3>
 * Linear interpolation between adjacent samples for both position and orientation.
 * The source data typically contains one sample per frame at 60 fps, so linear
 * interpolation is more than sufficient.
 */
public final class CameraAnimation {

    /** Source composition height in pixels — used for zoom→FOV conversion. */
    public final int compHeight;
    /** Source frame rate in frames per second. */
    public final float frameRate;
    /** Total duration in seconds. */
    public final float durationSeconds;

    /** Per-sample time in seconds. */
    public final float[] times;
    /** Per-sample position X (HitFilm world, pixels). */
    public final float[] posX;
    /** Per-sample position Y (HitFilm world, pixels). */
    public final float[] posY;
    /** Per-sample position Z (HitFilm world, pixels). */
    public final float[] posZ;
    /** Per-sample Euler pitch (rotation around X axis, degrees). */
    public final float[] eulerX;
    /** Per-sample Euler yaw (rotation around Y axis, degrees). */
    public final float[] eulerY;
    /** Per-sample Euler roll (rotation around Z axis, degrees). */
    public final float[] eulerZ;
    /** Per-sample zoom (focal length in pixels at compHeight). */
    public final float[] zoom;

    // ── Construction ──────────────────────────────────────────────────────────

    public CameraAnimation(int compHeight, float frameRate,
                           float[] times,
                           float[] posX,  float[] posY,  float[] posZ,
                           float[] eulerX, float[] eulerY, float[] eulerZ,
                           float[] zoom) {
        this.compHeight      = compHeight;
        this.frameRate       = frameRate;
        this.durationSeconds = times.length > 0 ? times[times.length - 1] : 0f;
        this.times  = times;
        this.posX   = posX;  this.posY   = posY;  this.posZ   = posZ;
        this.eulerX = eulerX; this.eulerY = eulerY; this.eulerZ = eulerZ;
        this.zoom   = zoom;
    }

    public int sampleCount() { return times.length; }

    // ── Evaluated frame ───────────────────────────────────────────────────────

    /** Interpolated data at a given time. */
    public static final class Frame {
        public final float posX, posY, posZ;
        public final float eulerX, eulerY, eulerZ;
        public final float zoomPx;

        public Frame(float posX, float posY, float posZ,
                     float eulerX, float eulerY, float eulerZ,
                     float zoomPx) {
            this.posX   = posX;  this.posY   = posY;  this.posZ   = posZ;
            this.eulerX = eulerX; this.eulerY = eulerY; this.eulerZ = eulerZ;
            this.zoomPx = zoomPx;
        }
    }

    /** Returns the interpolated frame at {@code timeSec}, clamped to [0, duration]. */
    public Frame evaluate(float timeSec) {
        int n = times.length;
        if (n == 0) return new Frame(0, 0, 0, 0, 0, 0, 1000);
        if (n == 1 || timeSec <= times[0])
            return frameAt(0);
        if (timeSec >= times[n - 1])
            return frameAt(n - 1);

        // Binary search for bracketing indices
        int lo = 0, hi = n - 1;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (times[mid] <= timeSec) lo = mid; else hi = mid;
        }

        float t = (timeSec - times[lo]) / (times[hi] - times[lo]);
        return new Frame(
            lerp(posX[lo],   posX[hi],   t),
            lerp(posY[lo],   posY[hi],   t),
            lerp(posZ[lo],   posZ[hi],   t),
            lerpAngle(eulerX[lo], eulerX[hi], t),
            lerpAngle(eulerY[lo], eulerY[hi], t),
            lerpAngle(eulerZ[lo], eulerZ[hi], t),
            lerp(zoom[lo],   zoom[hi],   t)
        );
    }

    /** Returns the first (t=0) sample without allocation. */
    public Frame frame0() { return frameAt(0); }

    private Frame frameAt(int i) {
        return new Frame(posX[i], posY[i], posZ[i],
                         eulerX[i], eulerY[i], eulerZ[i], zoom[i]);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static float lerp(float a, float b, float t) { return a + (b - a) * t; }

    /**
     * Interpolates angles in degrees, handling wrap-around so that, e.g.,
     * lerping between -352° and -349° (i.e., 8° and 11°) works correctly.
     */
    private static float lerpAngle(float a, float b, float t) {
        float diff = b - a;
        // Wrap diff to [-180, 180]
        while (diff >  180f) diff -= 360f;
        while (diff < -180f) diff += 360f;
        return a + diff * t;
    }

    // ── Zoom conversion ───────────────────────────────────────────────────────

    /**
     * Converts a HitFilm zoom value (focal length in pixels) to
     * focal length in 35mm-equivalent millimetres.
     *
     * Formula: focalMM = zoomPx × 24 / compHeight
     * (assumes a 24mm tall 35mm-equivalent sensor)
     */
    public float zoomToFocalMm(float zoomPx) {
        return zoomPx * 24f / compHeight;
    }

    /**
     * Converts a HitFilm zoom value to vertical FOV in degrees.
     * fovY = 2 × atan(compHeight / 2 / zoomPx)
     */
    public float zoomToFovYDeg(float zoomPx) {
        return 2f * (float) Math.toDegrees(Math.atan2(compHeight * 0.5, zoomPx));
    }
}
