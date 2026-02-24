package com.retrocam.keyframe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Manages all {@link KeyframeTrack}s for a single render job or live preview session.
 *
 * <h3>Usage in rendering</h3>
 * <p>{@link com.retrocam.export.RenderPipeline} calls {@link #apply(float)} once per
 * video frame before accumulation begins. Each track writes its interpolated value
 * back to its target object. The pipeline then checks {@link #hasSceneObjectTracks()}
 * to determine whether the scene needs to be re-uploaded.</p>
 *
 * <h3>Usage in live preview</h3>
 * <p>{@link com.retrocam.core.ImGuiLayer} calls {@link #apply(float)} when the
 * scrub slider changes and preview is enabled, then checks the same flag to mark
 * {@link com.retrocam.scene.SceneEditor} dirty for the main loop to re-upload.</p>
 *
 * <h3>Track uniqueness</h3>
 * <p>Only one track may exist per (target, property) pair. {@link #addTrack} enforces
 * this — attempting to add a duplicate returns the existing track.</p>
 */
public final class KeyframeTimeline {

    /** Total duration this timeline covers — should match the render job's durationSeconds. */
    public float durationSeconds;

    private final List<KeyframeTrack> tracks = new ArrayList<>();

    public KeyframeTimeline(float durationSeconds) {
        this.durationSeconds = durationSeconds;
    }

    // ── Track management ──────────────────────────────────────────────────────

    /**
     * Adds a track and returns it. If a track for the same (target, property) already
     * exists, the existing track is returned without creating a duplicate.
     */
    public KeyframeTrack addTrack(Keyframeable target, String propertyName,
                                  KeyframeTarget.Type targetType) {
        for (KeyframeTrack t : tracks) {
            if (t.target == target && t.propertyName.equals(propertyName))
                return t; // already exists
        }
        KeyframeTrack track = new KeyframeTrack(target, propertyName, targetType);
        tracks.add(track);
        return track;
    }

    public void removeTrack(KeyframeTrack track) {
        tracks.remove(track);
    }

    public void removeTrackAt(int index) {
        if (index >= 0 && index < tracks.size())
            tracks.remove(index);
    }

    public List<KeyframeTrack> getTracks() {
        return Collections.unmodifiableList(tracks);
    }

    public int trackCount() { return tracks.size(); }

    // ── Evaluation ────────────────────────────────────────────────────────────

    /**
     * Evaluates all tracks at {@code time} and applies the interpolated values
     * to their respective target objects.
     *
     * <p>After this call, callers should check {@link #hasSceneObjectTracks()} and
     * if true, mark the {@link com.retrocam.scene.SceneEditor} dirty so the GPU
     * scene is re-uploaded before the next frame is rendered.</p>
     *
     * @param time canonical time in seconds, clamped to the defined keyframe range per-track
     */
    public void apply(float time) {
        for (KeyframeTrack track : tracks) {
            track.applyToTarget(time);
        }
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    /**
     * Returns true if any track targets a {@link com.retrocam.scene.SceneObject}.
     * When true, callers must re-upload the scene after calling {@link #apply}.
     */
    public boolean hasSceneObjectTracks() {
        for (KeyframeTrack t : tracks)
            if (t.targetType == KeyframeTarget.Type.SCENE_OBJECT) return true;
        return false;
    }

    /** Returns true if any tracks have been defined and contain at least one keyframe. */
    public boolean isEmpty() {
        if (tracks.isEmpty()) return true;
        for (KeyframeTrack t : tracks)
            if (t.keyframeCount() > 0) return false;
        return true;
    }

    /**
     * Finds the track for a given target + property, or null if none exists.
     * Useful for the UI to highlight which properties already have tracks.
     */
    public KeyframeTrack findTrack(Keyframeable target, String propertyName) {
        for (KeyframeTrack t : tracks)
            if (t.target == target && t.propertyName.equals(propertyName)) return t;
        return null;
    }
}
