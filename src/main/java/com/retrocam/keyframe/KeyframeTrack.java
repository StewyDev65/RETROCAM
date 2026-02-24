package com.retrocam.keyframe;

import java.util.ArrayList;
import java.util.List;

/**
 * Holds all time-value keyframes for a single property on a single {@link Keyframeable} target.
 *
 * <h3>Interpolation modes</h3>
 * <ul>
 *   <li>{@link Interpolation#STEP}   — holds the value of the previous keyframe (snap).</li>
 *   <li>{@link Interpolation#LINEAR} — linearly interpolates between surrounding keyframes.</li>
 *   <li>{@link Interpolation#BEZIER} — Catmull-Rom spline with auto-computed tangents.
 *       Produces smooth curves without requiring manual handle placement.</li>
 * </ul>
 *
 * <p>Keyframes are always kept sorted by ascending time. Adding a keyframe at an
 * existing time overwrites the old value rather than inserting a duplicate.</p>
 */
public final class KeyframeTrack {

    // ── Interpolation mode ────────────────────────────────────────────────────

    public enum Interpolation {
        STEP, LINEAR, BEZIER;

        public static final String[] LABELS = { "Step", "Linear", "Bezier" };

        public static Interpolation fromIndex(int i) {
            return values()[Math.max(0, Math.min(values().length - 1, i))];
        }
        public int toIndex() { return ordinal(); }
    }

    // ── Keyframe data ─────────────────────────────────────────────────────────

    public static final class Keyframe {
        public float         time;
        public float         value;
        public Interpolation interp;

        public Keyframe(float time, float value, Interpolation interp) {
            this.time   = time;
            this.value  = value;
            this.interp = interp;
        }
    }

    // ── Track identity ────────────────────────────────────────────────────────

    public final Keyframeable          target;
    public final String                propertyName;
    public final KeyframeTarget.Type   targetType;   // used by timeline to flag scene dirty

    private final List<Keyframe> keyframes = new ArrayList<>();

    // ── Construction ──────────────────────────────────────────────────────────

    public KeyframeTrack(Keyframeable target, String propertyName, KeyframeTarget.Type targetType) {
        this.target       = target;
        this.propertyName = propertyName;
        this.targetType   = targetType;
    }

    // ── Keyframe mutation ─────────────────────────────────────────────────────

    /**
     * Adds a keyframe at {@code time} with {@code value} and {@code interp}.
     * If a keyframe already exists within 0.001 s of {@code time}, it is overwritten.
     */
    public void addKeyframe(float time, float value, Interpolation interp) {
        for (Keyframe kf : keyframes) {
            if (Math.abs(kf.time - time) < 0.001f) {
                kf.value  = value;
                kf.interp = interp;
                return;
            }
        }
        // Sorted insert
        int i = 0;
        while (i < keyframes.size() && keyframes.get(i).time < time) i++;
        keyframes.add(i, new Keyframe(time, value, interp));
    }

    public void removeKeyframe(int index) {
        if (index >= 0 && index < keyframes.size())
            keyframes.remove(index);
    }

    public List<Keyframe> getKeyframes() { return keyframes; } // mutable for UI editing

    public int keyframeCount() { return keyframes.size(); }

    // ── Evaluation ────────────────────────────────────────────────────────────

    /**
     * Returns the interpolated value at {@code time}.
     * Clamps to the value of the first/last keyframe outside the defined range.
     *
     * @param defaultValue returned if the track has no keyframes
     */
    public float evaluate(float time, float defaultValue) {
        int n = keyframes.size();
        if (n == 0) return defaultValue;
        if (n == 1) return keyframes.get(0).value;

        // Clamp extrapolation
        if (time <= keyframes.get(0).time)     return keyframes.get(0).value;
        if (time >= keyframes.get(n - 1).time) return keyframes.get(n - 1).value;

        // Find bracketing pair via linear scan (lists are typically small)
        int hi = 1;
        while (hi < n && keyframes.get(hi).time < time) hi++;
        int lo = hi - 1;

        Keyframe k0 = keyframes.get(lo);
        Keyframe k1 = keyframes.get(hi);
        float localT = (time - k0.time) / (k1.time - k0.time);

        return switch (k0.interp) {
            case STEP   -> k0.value;
            case LINEAR -> lerp(k0.value, k1.value, localT);
            case BEZIER -> catmullRom(lo, hi, localT);
        };
    }

    /**
     * Evaluates the track at {@code time} and writes the result back into the target.
     */
    public void applyToTarget(float time) {
        float current = target.getKeyframeableProperty(propertyName);
        target.setKeyframeableProperty(propertyName, evaluate(time, current));
    }

    // ── Interpolation helpers ─────────────────────────────────────────────────

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    /**
     * Catmull-Rom spline evaluation between keyframes[lo] and keyframes[hi].
     * Tangents are auto-computed from neighbours; endpoint tangents use
     * one-sided finite differences so the curve remains well-behaved at extremes.
     */
    private float catmullRom(int lo, int hi, float t) {
        Keyframe k0 = keyframes.get(lo);
        Keyframe k1 = keyframes.get(hi);
        float dt = k1.time - k0.time;

        // Incoming tangent at k0
        float m0;
        if (lo > 0) {
            Keyframe kp = keyframes.get(lo - 1);
            m0 = (k1.value - kp.value) / (k1.time - kp.time);
        } else {
            m0 = (k1.value - k0.value) / dt;
        }

        // Outgoing tangent at k1
        float m1;
        if (hi < keyframes.size() - 1) {
            Keyframe kn = keyframes.get(hi + 1);
            m1 = (kn.value - k0.value) / (kn.time - k0.time);
        } else {
            m1 = (k1.value - k0.value) / dt;
        }

        // Cubic Hermite basis
        float t2 = t * t, t3 = t2 * t;
        float h00 =  2*t3 - 3*t2 + 1;
        float h10 =    t3 - 2*t2 + t;
        float h01 = -2*t3 + 3*t2;
        float h11 =    t3 -   t2;

        return h00 * k0.value + h10 * dt * m0
             + h01 * k1.value + h11 * dt * m1;
    }
}
