package com.retrocam.keyframe;

/**
 * Marker interface for scene parameters that can be animated via keyframes.
 *
 * <p><b>Phase 2 stub.</b> When implemented, objects implementing this interface
 * expose named, animatable properties that the keyframe editor can add tracks for.
 * The {@link KeyframeTimeline} queries these properties by name and writes
 * interpolated values back at each frame during video rendering.</p>
 *
 * <h3>Planned animatable properties</h3>
 * <ul>
 *   <li><b>Camera:</b> position (orbit: yaw, pitch, radius), focal length,
 *       aperture f-stop, focus distance</li>
 *   <li><b>Scene objects:</b> translation (x, y, z), uniform scale, rotation
 *       (Euler angles)</li>
 *   <li><b>Post-process values:</b> any float parameter exposed in
 *       {@link com.retrocam.core.RenderSettings}</li>
 * </ul>
 *
 * <pre>
 * // TODO Phase 2 example usage:
 * public class OrbitCamera implements Keyframeable {
 *     public Map<String, Float> getKeyframeableProperties() { ... }
 *     public void setKeyframeableProperty(String name, float value) { ... }
 * }
 * </pre>
 */
public interface Keyframeable {

    /**
     * Returns an array of property names this object exposes for keyframing.
     * Names must be stable identifiers (used as keys in the timeline tracks).
     *
     * TODO Phase 2: implement in OrbitCamera, SceneObject, and RenderSettings.
     */
    String[] getKeyframeablePropertyNames();

    /**
     * Returns the current value of the named property.
     *
     * TODO Phase 2: implement in each Keyframeable class.
     */
    float getKeyframeableProperty(String name);

    /**
     * Sets the named property to the given interpolated value.
     * Called by {@link KeyframeTimeline#apply(float, com.retrocam.export.RenderContext)}
     * for each active track at each video frame.
     *
     * TODO Phase 2: implement in each Keyframeable class.
     */
    void setKeyframeableProperty(String name, float value);
}