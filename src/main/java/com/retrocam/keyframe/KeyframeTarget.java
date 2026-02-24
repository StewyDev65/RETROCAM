package com.retrocam.keyframe;

/**
 * Associates a {@link Keyframeable} with a display name and category type.
 * Used by the Keyframe Editor UI to populate the target selection combo.
 *
 * The list of targets is rebuilt each ImGui frame from the live scene so
 * newly added or removed scene objects are always reflected.
 */
public final class KeyframeTarget {

    /** Broad category used by consumers to decide post-apply side effects. */
    public enum Type {
        /** {@link com.retrocam.camera.OrbitCamera} — sets camera.dirty on mutation. */
        CAMERA,
        /** {@link com.retrocam.scene.SceneObject} — requires scene re-upload after mutation. */
        SCENE_OBJECT,
        /** {@link com.retrocam.core.RenderSettings} — settings are read each frame, no extra work. */
        RENDER_SETTINGS
    }

    public final String       displayName;
    public final Type         type;
    public final Keyframeable target;

    public KeyframeTarget(String displayName, Type type, Keyframeable target) {
        this.displayName = displayName;
        this.type        = type;
        this.target      = target;
    }
}
