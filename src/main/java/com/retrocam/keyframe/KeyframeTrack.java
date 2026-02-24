package com.retrocam.keyframe;

import java.util.ArrayList;
import java.util.List;

/**
 * Holds all keyframes for a single animatable property on a single target object.
 *
 * <p><b>Phase 2 stub.</b> The data structures and interpolation logic are defined
 * here as placeholders so the rest of the system can reference the types.
 * No functional keyframe evaluation occurs yet.</p>
 *
 * <h3>Planned interpolation modes</h3>
 * <ul>
 *   <li>{@code LINEAR} — linear interpolation between adjacent keyframes</li>
 *   <li>{@code STEP} — hold value until next keyframe (snap)</li>
 *   <li>{@code BEZIER} — cubic bezier with per-keyframe tangent handles</li>
 * </ul>
 */
public final class KeyframeTrack {

    /** Supported interpolation modes between keyframes. */
    public enum Interpolation { LINEAR, STEP, BEZIER }

    /**
     * A single timed value on the track.
     *
     * TODO Phase 2: add bezier tangent handles (inTangent, outTangent).
     */
    public static final class Keyframe {
        public float time;          // canonical seconds
        public float value;         // property value at this time
        public Interpolation interp = Interpolation.LINEAR;

        public Keyframe(float time, float value) {
            this.time  = time;
            this.value = value;
        }
    }

    // ── Track identity ────────────────────────────────────────────────────────

    /** The target object (e.g. OrbitCamera, a SceneObject). */
    public final Keyframeable target;
    /** The name of the property being animated (stable string key). */
    public final String       propertyName;

    // ── Track data ────────────────────────────────────────────────────────────
    private final List<Keyframe> keyframes = new ArrayList<>();

    public KeyframeTrack(Keyframeable target, String propertyName) {
        this.target       = target;
        this.propertyName = propertyName;
    }

    // ── Mutation ──────────────────────────────────────────────────────────────

    /** Adds or updates a keyframe at the given time. Keeps list sorted by time. */
    public void addKeyframe(float time, float value) {
        // TODO Phase 2: implement sorted insertion
        keyframes.add(new Keyframe(time, value));
    }

    public void removeKeyframe(int index) {
        // TODO Phase 2: implement
        keyframes.remove(index);
    }

    public List<Keyframe> getKeyframes() {
        return List.copyOf(keyframes);
    }

    // ── Evaluation ───────────────────────────────────────────────────────────

    /**
     * Returns the interpolated value at the given canonical time.
     *
     * <p><b>TODO Phase 2:</b> implement linear, step, and bezier interpolation.
     * Currently returns the first keyframe value, or {@code defaultValue} if empty.</p>
     */
    public float evaluate(float time, float defaultValue) {
        // TODO Phase 2: binary search + interpolate
        if (keyframes.isEmpty()) return defaultValue;
        return keyframes.get(0).value; // stub: always return first keyframe value
    }

    /**
     * Applies the interpolated value at {@code time} back to the target object.
     *
     * TODO Phase 2: call this from {@link KeyframeTimeline#apply}.
     */
    public void applyToTarget(float time) {
        float current = target.getKeyframeableProperty(propertyName);
        float value   = evaluate(time, current);
        target.setKeyframeableProperty(propertyName, value);
    }
}