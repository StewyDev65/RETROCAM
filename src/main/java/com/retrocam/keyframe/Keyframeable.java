package com.retrocam.keyframe;

/**
 * Implemented by any object whose float properties can be animated over time.
 *
 * Two parallel arrays of equal length are exposed:
 *   {@link #getKeyframeablePropertyNames()} — stable string keys (used internally)
 *   {@link #getKeyframeablePropertyDisplayNames()} — human-readable labels (used in UI)
 *
 * Implementors: {@link com.retrocam.camera.OrbitCamera},
 *               {@link com.retrocam.scene.SceneObject},
 *               {@link com.retrocam.core.RenderSettings}
 */
public interface Keyframeable {

    /**
     * Stable internal keys for each animatable property.
     * These are used as map keys in {@link KeyframeTrack} and must never change.
     */
    String[] getKeyframeablePropertyNames();

    /**
     * Human-readable labels shown in the Keyframe Editor UI.
     * Must be the same length and order as {@link #getKeyframeablePropertyNames()}.
     */
    String[] getKeyframeablePropertyDisplayNames();

    /**
     * Returns the current value of the named property.
     * Called when capturing a keyframe from the live scene.
     */
    float getKeyframeableProperty(String name);

    /**
     * Writes an interpolated value back to the named property.
     * Called by {@link KeyframeTimeline#apply(float)} on each video frame.
     * Implementors should set their dirty flag if applicable (e.g. OrbitCamera).
     */
    void setKeyframeableProperty(String name, float value);
}
