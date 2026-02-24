package com.retrocam.export;

import com.retrocam.core.RenderSettings;
import com.retrocam.core.TemporalState;
import com.retrocam.keyframe.KeyframeTimeline;

/**
 * Drives render-to-file jobs as a state machine ticked from the main GL loop.
 *
 * <h3>Photo pipeline</h3>
 * <ol>
 *   <li>Reset accumulator.</li>
 *   <li>Accumulate {@link RenderJob#samplesPerFrame} path-trace passes.</li>
 *   <li>Run the full post-process stack.</li>
 *   <li>Export the final texture to disk.</li>
 * </ol>
 *
 * <h3>Video pipeline</h3>
 * <p>For each frame at canonical time {@code t = frameIndex / fps}:</p>
 * <ol>
 *   <li>Apply keyframes for time {@code t} (Phase 2 stub — no-op now).</li>
 *   <li>Advance the video-mode {@link TemporalState} by one frame period.</li>
 *   <li>Reset accumulator and SPPM.</li>
 *   <li>Accumulate {@link RenderJob#samplesPerFrame} passes (with SPPM if enabled).</li>
 *   <li>Run the post-process stack — post effects driven by canonical frame time,
 *       ensuring time-based effects (noise seeds, chroma drift, etc.) evolve
 *       deterministically regardless of wall-clock speed.</li>
 *   <li>Write the frame to the temp directory as PNG.</li>
 *   <li>After all frames: run FFmpeg if needed; clean up temp frames.</li>
 * </ol>
 *
 * <h3>Threading</h3>
 * <p>All work happens on the GL thread inside {@link #tick}. Each tick call
 * blocks for the duration of one complete rendered frame. The UI remains
 * responsive between frames (between tick calls), allowing cancellation and
 * title-bar progress updates.</p>
 *
 * <h3>Keyframe stub</h3>
 * <p>{@link #applyKeyframes(float, RenderContext)} is defined but empty in
 * Phase 1. When Phase 2 is implemented, it will sample the job's
 * {@link KeyframeTimeline} and write interpolated values into
 * {@code RenderContext.settings} and the scene objects before each frame is
 * rendered.</p>
 */
public final class RenderPipeline {

    // ── State ─────────────────────────────────────────────────────────────────

    public enum State { IDLE, RENDERING_PHOTO, RENDERING_VIDEO, FINALIZING, COMPLETE, ERROR, CANCELLED }

    private State  state   = State.IDLE;
    private RenderJob job  = null;

    // ── Progress tracking ─────────────────────────────────────────────────────
    private int    currentFrame  = 0;
    private int    totalFrames   = 1;
    private String statusMessage = "";
    private String errorMessage  = "";

    // ── Output texture ID from the most recent rendered frame ─────────────────
    private int lastOutputTexId  = -1;

    // ── Per-job workers ───────────────────────────────────────────────────────
    private FrameExporter frameExporter;
    private VideoExporter videoExporter;

    /**
     * Dedicated temporal state for video rendering. Evolved deterministically
     * from frame 0 so that AGC, white-balance drift, and auto-focus tracking
     * are temporally consistent regardless of wall-clock rendering speed.
     */
    private TemporalState videoTemporal;

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Starts a new render job. Ignored if a job is already running.
     *
     * @throws IllegalStateException if a job is already active
     */
    public void startJob(RenderJob newJob) {
        if (state != State.IDLE && state != State.COMPLETE
                && state != State.ERROR && state != State.CANCELLED) {
            throw new IllegalStateException("A render job is already in progress.");
        }

        this.job          = newJob;
        this.currentFrame = 0;
        this.totalFrames  = newJob.getTotalFrames();
        this.errorMessage = "";
        this.lastOutputTexId = -1;

        this.frameExporter = new FrameExporter(
            RenderSettings.RENDER_WIDTH, RenderSettings.RENDER_HEIGHT);
        this.videoExporter = new VideoExporter();

        if (newJob.format.isVideo) {
            this.videoTemporal = new TemporalState();
            this.state         = State.RENDERING_VIDEO;
        } else {
            this.videoTemporal = null;
            this.state         = State.RENDERING_PHOTO;
        }

        statusMessage = "Starting render…";
        System.out.println("[RenderPipeline] Job started: " + newJob.format.label
            + " | " + totalFrames + " frame(s) | " + newJob.samplesPerFrame + " spp");
    }

    /**
     * Cancels the active job. Safe to call when idle.
     * Any partially-written frames are left on disk.
     */
    public void cancel() {
        if (state == State.RENDERING_PHOTO || state == State.RENDERING_VIDEO
                || state == State.FINALIZING) {
            state         = State.CANCELLED;
            statusMessage = "Cancelled.";
            System.out.println("[RenderPipeline] Job cancelled at frame " + currentFrame);
        }
    }

    /**
     * Ticks the pipeline by one step (one complete rendered frame for video, or
     * the full photo render). Must be called from the GL thread each main-loop
     * iteration while {@link #isRunning()} is true.
     *
     * @param ctx  live render dependencies
     * @param wallDt wall-clock delta-time (seconds) — used only for interactive temporal
     *               updates; the pipeline uses canonical frame time for all video state
     * @return GL texture ID of the last rendered frame (pass to display blit), or -1
     */
    public int tick(RenderContext ctx, float wallDt) {
        if (!isRunning()) return lastOutputTexId;

        try {
            switch (state) {
                case RENDERING_PHOTO -> tickPhoto(ctx);
                case RENDERING_VIDEO -> tickVideoFrame(ctx);
                case FINALIZING      -> tickFinalize();
                default              -> { /* no-op */ }
            }
        } catch (Exception e) {
            state         = State.ERROR;
            errorMessage  = e.getMessage();
            statusMessage = "Error: " + e.getMessage();
            System.err.println("[RenderPipeline] Error: " + e.getMessage());
            e.printStackTrace();
        }

        return lastOutputTexId;
    }

    // ── State: Photo ──────────────────────────────────────────────────────────

    private void tickPhoto(RenderContext ctx) {
        statusMessage = "Rendering photo…";

        // Phase 2: applyKeyframes(0f, ctx)
        applyKeyframes(0f, ctx);

        accumulateFrame(ctx, ctx.temporal);

        String outPath = job.getFullOutputPath();
        frameExporter.exportFrame(lastOutputTexId, outPath, job.jpegQuality);

        statusMessage = "Done → " + outPath;
        state         = State.COMPLETE;
        System.out.println("[RenderPipeline] Photo exported: " + outPath);
    }

    // ── State: Video frame ────────────────────────────────────────────────────

    private void tickVideoFrame(RenderContext ctx) {
        float canonicalTime = currentFrame * job.getFrameDt();
        float frameDt       = job.getFrameDt();

        statusMessage = String.format("Rendering frame %d / %d  (t=%.3fs)…",
            currentFrame + 1, totalFrames, canonicalTime);

        // Phase 2: applyKeyframes(canonicalTime, ctx)
        applyKeyframes(canonicalTime, ctx);

        // Advance video-mode temporal state by one canonical frame period.
        // This evolves AGC, WB drift, and AF tracking deterministically.
        ctx.settings.focusDistM = ctx.settings.focusDistM; // target unchanged unless keyframed
        videoTemporal.setFocalDistTarget(ctx.settings.focusDistM);
        videoTemporal.updateForVideoFrame(canonicalTime, frameDt, ctx.settings);

        accumulateFrame(ctx, videoTemporal);

        // Write frame to temp directory
        String framePath = job.getFramePath(currentFrame);
        frameExporter.exportFrame(lastOutputTexId, framePath, job.jpegQuality);

        currentFrame++;
        if (currentFrame >= totalFrames) {
            if (job.format.requiresFFmpeg) {
                state         = State.FINALIZING;
                statusMessage = "Encoding video…";
            } else {
                // Image sequence — done
                state         = State.COMPLETE;
                statusMessage = "Done → " + job.getTempFrameDir();
                System.out.println("[RenderPipeline] Image sequence complete: " + job.getTempFrameDir());
            }
        }
    }

    // ── State: Finalize (FFmpeg encode) ───────────────────────────────────────

    private void tickFinalize() {
        statusMessage = "Encoding video with FFmpeg…";

        try {
            videoExporter.encode(job);
            videoExporter.cleanupTempFrames(job);
            state         = State.COMPLETE;
            statusMessage = "Done → " + job.getFullOutputPath();
            System.out.println("[RenderPipeline] Video complete: " + job.getFullOutputPath());
        } catch (VideoExporter.VideoEncodeException e) {
            // FFmpeg missing or failed — keep image sequence, report as soft warning
            state         = State.COMPLETE; // treat as success (frames are still there)
            statusMessage = "Warning: " + e.getMessage();
            System.err.println("[RenderPipeline] Encode warning: " + e.getMessage());
        }
    }

    // ── Core accumulation ─────────────────────────────────────────────────────

    /**
     * Resets the accumulator and runs {@link RenderJob#samplesPerFrame} path-trace
     * passes (plus SPPM if enabled), then runs the post-process stack.
     * Sets {@link #lastOutputTexId} to the result.
     *
     * @param temporal  the temporal state to use for this frame (live or video-mode)
     */
    private void accumulateFrame(RenderContext ctx, TemporalState temporal) {
        ctx.renderer.reset();
        if (ctx.settings.sppmEnabled && ctx.sceneUploader.getLightCount() > 0) {
            ctx.sppmManager.reset(ctx.settings);
        }

        for (int i = 0; i < job.samplesPerFrame; i++) {
            // SPPM photon trace pass
            if (ctx.settings.sppmEnabled && ctx.sceneUploader.getLightCount() > 0) {
                ctx.sppmManager.tracePhotons(ctx.sceneUploader, ctx.settings);
            }

            // Path trace accumulation
            ctx.renderer.render(ctx.sceneUploader, ctx.camera, ctx.thinLens, temporal, ctx.settings);

            // SPPM gather + radius update
            if (ctx.settings.sppmEnabled && ctx.sceneUploader.getLightCount() > 0) {
                ctx.sppmManager.gatherRadiance(
                    ctx.sceneUploader, ctx.camera,
                    ctx.renderer.getAccumTexture(), ctx.settings, ctx.renderer.getTotalSamples());
                ctx.sppmManager.updateRadius(ctx.settings.sppmAlpha);
            }
        }

        // Run the post-process chain.
        // Temporal state's `time` field drives all time-based shader effects
        // (noise seeds, chroma drift, timebase errors, etc.) so passing the
        // video-mode temporal ensures deterministic per-frame effect progression.
        lastOutputTexId = ctx.postStack.runOnAccum(
            ctx.renderer.getAccumTexture(),
            ctx.renderer.getGBufferTexture(),
            ctx.renderer.getTotalSamples(),
            ctx.settings.exposure,
            ctx.settings,
            temporal);
    }

    // ── Keyframe stub (Phase 2) ───────────────────────────────────────────────

    /**
     * Applies keyframe-interpolated values to the render context for the given time.
     *
     * <p><b>Phase 1 stub</b> — currently a no-op. When Phase 2 is implemented:</p>
     * <ul>
     *   <li>Sample {@link RenderJob#keyframeTimeline} at {@code time}.</li>
     *   <li>Write interpolated camera parameters to {@code ctx.settings} and
     *       {@code ctx.camera} (position, FOV, aperture, focus distance, etc.).</li>
     *   <li>Write interpolated object transforms to the scene objects in
     *       {@code ctx.sceneUploader}, then re-upload the scene.</li>
     * </ul>
     *
     * @param time canonical frame time in seconds
     * @param ctx  render context whose settings/scene will be mutated
     */
    private void applyKeyframes(float time, RenderContext ctx) {
        // TODO Phase 2: if (job.keyframeTimeline != null) job.keyframeTimeline.apply(time, ctx);
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    public boolean isRunning() {
        return state == State.RENDERING_PHOTO
            || state == State.RENDERING_VIDEO
            || state == State.FINALIZING;
    }

    public boolean isIdle() {
        return state == State.IDLE || state == State.COMPLETE
            || state == State.ERROR || state == State.CANCELLED;
    }

    /** Returns 0.0–1.0 indicating overall job progress. */
    public float getProgress() {
        if (totalFrames <= 0) return 0f;
        return (float) currentFrame / totalFrames;
    }

    public int    getCurrentFrame()   { return currentFrame; }
    public int    getTotalFrames()    { return totalFrames; }
    public String getStatusMessage()  { return statusMessage; }
    public String getErrorMessage()   { return errorMessage; }
    public State  getState()          { return state; }
    public int    getLastOutputTexId(){ return lastOutputTexId; }

    /** Resets to IDLE so a new job can be started. */
    public void reset() {
        state         = State.IDLE;
        job           = null;
        currentFrame  = 0;
        totalFrames   = 1;
        statusMessage = "";
        errorMessage  = "";
        lastOutputTexId = -1;
    }
}