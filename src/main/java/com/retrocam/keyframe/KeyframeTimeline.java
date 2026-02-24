package com.retrocam.keyframe;

import com.retrocam.export.RenderContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Manages all {@link KeyframeTrack}s for a single render job.
 *
 * <p><b>Phase 2 stub.</b> The timeline is constructed by the keyframe editor
 * UI and attached to a {@link com.retrocam.export.RenderJob} before the job
 * is started. During rendering, {@link RenderPipeline} calls
 * {@link #apply(float, RenderContext)} once per frame before accumulation begins.</p>
 *
 * <h3>Planned Phase 2 workflow</h3>
 * <ol>
 *   <li>User opens the Keyframe Editor panel in ImGui.</li>
 *   <li>User selects a target object (camera, scene object, or settings)
 *       and a property name.</li>
 *   <li>User scrubs to a time and clicks "Add Keyframe" — creates a
 *       {@link KeyframeTrack.Keyframe} with the current property value.</li>
 *   <li>On render start, the timeline is attached to the job via
 *       {@link com.retrocam.export.RenderJob.Builder#keyframeTimeline} (stub).</li>
 *   <li>Each video frame, {@link #apply} is called to interpolate and write
 *       all track values into the render context.</li>
 * </ol>
 */
public final class KeyframeTimeline {

    private final List<KeyframeTrack> tracks = new ArrayList<>();
    /** Total duration this timeline covers (matches {@link com.retrocam.export.RenderJob#durationSeconds}). */
    public final float durationSeconds;

    public KeyframeTimeline(float durationSeconds) {
        this.durationSeconds = durationSeconds;
    }

    // ── Track management ──────────────────────────────────────────────────────

    public void addTrack(KeyframeTrack track) {
        tracks.add(track);
    }

    public void removeTrack(KeyframeTrack track) {
        tracks.remove(track);
    }

    public List<KeyframeTrack> getTracks() {
        return List.copyOf(tracks);
    }

    // ── Evaluation ───────────────────────────────────────────────────────────

    /**
     * Applies all track values at the given canonical time to the render context.
     *
     * <p><b>Phase 1:</b> Currently a no-op — no tracks exist and {@link RenderPipeline}
     * only calls this when {@link com.retrocam.export.RenderJob#keyframeTimeline}
     * is non-null (which never happens in Phase 1).</p>
     *
     * <p><b>TODO Phase 2:</b> iterate tracks and call
     * {@link KeyframeTrack#applyToTarget(float)} for each. For camera position /
     * settings changes, also mark accumulators dirty so samples are not corrupted
     * by mid-render parameter changes.</p>
     *
     * @param time canonical frame time in seconds
     * @param ctx  render context whose state will be mutated by track evaluation
     */
    public void apply(float time, RenderContext ctx) {
        // TODO Phase 2: for (KeyframeTrack track : tracks) track.applyToTarget(time);
    }
}