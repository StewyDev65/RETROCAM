package com.retrocam.export;

import com.retrocam.core.RenderSettings;
import com.retrocam.core.TemporalState;
import com.retrocam.gl.Framebuffer;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * Drives render-to-file jobs as a state machine ticked from the main GL loop.
 *
 * <h3>Photo pipeline (per job)</h3>
 * <ol>
 *   <li>Apply keyframes at t=0 (photo always uses a single time point).</li>
 *   <li>Reset accumulator. Accumulate {@link RenderJob#samplesPerFrame} passes.</li>
 *   <li>Run post-process stack, then display shader (ACES + gamma).</li>
 *   <li>Export texture to disk.</li>
 * </ol>
 *
 * <h3>Video pipeline (per frame)</h3>
 * <ol>
 *   <li>Apply keyframes at canonical time {@code t = frameIndex / fps}.</li>
 *   <li>Re-upload scene if any scene-object keyframes were applied.</li>
 *   <li>Advance video-mode {@link TemporalState} by one frame period (deterministic).</li>
 *   <li>Reset accumulator + SPPM. Accumulate {@link RenderJob#samplesPerFrame} passes.</li>
 *   <li>Run post-process stack + display shader. Write frame PNG to temp directory.</li>
 *   <li>After all frames: run FFmpeg if required; clean up temp frames.</li>
 * </ol>
 */
public final class RenderPipeline {

    // ── State machine ─────────────────────────────────────────────────────────

    public enum State { IDLE, RENDERING_PHOTO, RENDERING_VIDEO, FINALIZING, COMPLETE, ERROR, CANCELLED }

    private State     state   = State.IDLE;
    private RenderJob job     = null;

    // ── Progress ──────────────────────────────────────────────────────────────
    private int    currentFrame  = 0;
    private int    totalFrames   = 1;
    private String statusMessage = "";
    private String errorMessage  = "";

    // ── GPU resources ─────────────────────────────────────────────────────────
    private int         lastOutputTexId  = -1;
    private Framebuffer exportDisplayFbo = null;

    // ── Per-job workers ───────────────────────────────────────────────────────
    private FrameExporter frameExporter;
    private VideoExporter videoExporter;

    /**
     * Dedicated temporal state for video rendering.
     * Evolved deterministically from frame 0 using canonical frame time so that
     * AGC, white-balance drift, and AF tracking are reproducible across re-renders.
     */
    private TemporalState videoTemporal;

    // ── Public API ────────────────────────────────────────────────────────────

    public void startJob(RenderJob newJob) {
        if (isRunning())
            throw new IllegalStateException("A render job is already in progress.");

        this.job          = newJob;
        this.currentFrame = 0;
        this.totalFrames  = newJob.getTotalFrames();
        this.errorMessage = "";
        this.lastOutputTexId = -1;

        this.frameExporter = new FrameExporter(
            RenderSettings.RENDER_WIDTH, RenderSettings.RENDER_HEIGHT);
        this.videoExporter = new VideoExporter();

        if (exportDisplayFbo == null) {
            exportDisplayFbo = new Framebuffer(
                RenderSettings.RENDER_WIDTH, RenderSettings.RENDER_HEIGHT, GL_RGBA8);
        }

        if (newJob.format.isVideo) {
            videoTemporal = new TemporalState();
            state         = State.RENDERING_VIDEO;
        } else {
            videoTemporal = null;
            state         = State.RENDERING_PHOTO;
        }

        statusMessage = "Starting render…";
        System.out.printf("[RenderPipeline] Job started: %s | %d frame(s) | %d spp%n",
            newJob.format.label, totalFrames, newJob.samplesPerFrame);
    }

    public void cancel() {
        if (isRunning()) {
            state         = State.CANCELLED;
            statusMessage = "Cancelled.";
            System.out.println("[RenderPipeline] Job cancelled at frame " + currentFrame);
        }
    }

    /**
     * Ticks the pipeline by one step. Must be called from the GL thread each
     * main-loop iteration while {@link #isRunning()} is true.
     *
     * @return GL texture ID of the last rendered frame, or -1
     */
    public int tick(RenderContext ctx, float wallDt) {
        if (!isRunning()) return lastOutputTexId;

        try {
            switch (state) {
                case RENDERING_PHOTO -> tickPhoto(ctx);
                case RENDERING_VIDEO -> tickVideoFrame(ctx);
                case FINALIZING      -> tickFinalize();
                default              -> { }
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

        applyKeyframes(canonicalTime, ctx);

        // Evaluate free camera at canonical frame time if active
        if (ctx.settings.freeCamActive && ctx.freeCamera.hasAnimation()) {
            float animTime = ctx.settings.freeCamStartTime
                + canonicalTime * ctx.settings.freeCamPlaybackSpeed;
            ctx.freeCamera.positionScale          = ctx.settings.freeCamPositionScale;
            ctx.freeCamera.applyZoomToFocalLength = ctx.settings.freeCamApplyZoom;
            ctx.freeCamera.evaluateAtTime(animTime, ctx.settings);
            ctx.activeCamera = ctx.freeCamera;
        } else {
            ctx.activeCamera = ctx.orbitCamera;
        }

        videoTemporal.setFocalDistTarget(ctx.settings.focusDistM);
        videoTemporal.updateForVideoFrame(canonicalTime, frameDt, ctx.settings);

        accumulateFrame(ctx, videoTemporal);

        String framePath = job.getFramePath(currentFrame);
        frameExporter.exportFrame(lastOutputTexId, framePath, job.jpegQuality);

        currentFrame++;
        if (currentFrame >= totalFrames) {
            if (job.format.requiresFFmpeg) {
                state         = State.FINALIZING;
                statusMessage = "Encoding video…";
            } else {
                state         = State.COMPLETE;
                statusMessage = "Done → " + job.getTempFrameDir();
                System.out.println("[RenderPipeline] Image sequence complete: " + job.getTempFrameDir());
            }
        }
    }

    // ── State: Finalize ───────────────────────────────────────────────────────

    private void tickFinalize() {
        try {
            videoExporter.encode(job);
            videoExporter.cleanupTempFrames(job);
            state         = State.COMPLETE;
            statusMessage = "Done → " + job.getFullOutputPath();
            System.out.println("[RenderPipeline] Video complete: " + job.getFullOutputPath());
        } catch (VideoExporter.VideoEncodeException e) {
            state         = State.COMPLETE;
            statusMessage = "Warning: " + e.getMessage();
            System.err.println("[RenderPipeline] Encode warning: " + e.getMessage());
        }
    }

    // ── Core accumulation ─────────────────────────────────────────────────────

    private void accumulateFrame(RenderContext ctx, TemporalState temporal) {
        ctx.renderer.reset();
        if (ctx.settings.sppmEnabled && ctx.sceneUploader.getLightCount() > 0)
            ctx.sppmManager.reset(ctx.settings);

        for (int i = 0; i < job.samplesPerFrame; i++) {
            if (ctx.settings.sppmEnabled && ctx.sceneUploader.getLightCount() > 0)
                ctx.sppmManager.tracePhotons(ctx.sceneUploader, ctx.settings);

            ctx.renderer.render(ctx.sceneUploader, ctx.activeCamera, ctx.thinLens, temporal, ctx.settings);

            if (ctx.settings.sppmEnabled && ctx.sceneUploader.getLightCount() > 0) {
                ctx.sppmManager.gatherRadiance(
                    ctx.sceneUploader, ctx.activeCamera,
                    ctx.renderer.getAccumTexture(), ctx.settings, ctx.renderer.getTotalSamples());
                ctx.sppmManager.updateRadius(ctx.settings.sppmAlpha);
            }
        }

        int postTexId = ctx.postStack.runOnAccum(
            ctx.renderer.getAccumTexture(),
            ctx.renderer.getGBufferTexture(),
            ctx.renderer.getGAlbedoTexture(),
            ctx.renderer.getVarianceTexture(),
            ctx.renderer.getTotalSamples(),
            ctx.settings.exposure,
            ctx.settings,
            temporal,
            true);

        lastOutputTexId = applyDisplayPass(postTexId, ctx);
    }

    // ── Display pass ──────────────────────────────────────────────────────────

    private int applyDisplayPass(int postTexId, RenderContext ctx) {
        exportDisplayFbo.bindForWrite();

        ctx.displayShader.bind();
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, postTexId);
        ctx.displayShader.setInt("u_tex",          0);
        ctx.displayShader.setInt("u_totalSamples", 1);
        ctx.displayShader.setFloat("u_exposure",   1.0f);

        glBindVertexArray(ctx.fullscreenVao);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        glBindVertexArray(0);

        ctx.displayShader.unbind();
        exportDisplayFbo.unbind();

        return exportDisplayFbo.textureId();
    }

    // ── Keyframe application ──────────────────────────────────────────────────

    private void applyKeyframes(float time, RenderContext ctx) {
        if (job.keyframeTimeline == null) return;

        job.keyframeTimeline.apply(time);

        if (job.keyframeTimeline.hasSceneObjectTracks()) {
            ctx.sceneEditor.markDirty();
            ctx.sceneUploader.upload(ctx.sceneEditor.buildScene());
            ctx.sceneEditor.clearDirty();
        }
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    public boolean isRunning() {
        return state == State.RENDERING_PHOTO
            || state == State.RENDERING_VIDEO
            || state == State.FINALIZING;
    }

    public boolean isIdle() {
        return state == State.IDLE  || state == State.COMPLETE
            || state == State.ERROR || state == State.CANCELLED;
    }

    public float  getProgress()        { return totalFrames <= 0 ? 0f : (float) currentFrame / totalFrames; }
    public int    getCurrentFrame()    { return currentFrame; }
    public int    getTotalFrames()     { return totalFrames; }
    public String getStatusMessage()   { return statusMessage; }
    public String getErrorMessage()    { return errorMessage; }
    public State  getState()           { return state; }
    public int    getLastOutputTexId() { return lastOutputTexId; }

    public void reset() {
        state           = State.IDLE;
        job             = null;
        currentFrame    = 0;
        totalFrames     = 1;
        statusMessage   = "";
        errorMessage    = "";
        lastOutputTexId = -1;
    }

    public void destroy() {
        if (exportDisplayFbo != null) {
            exportDisplayFbo.destroy();
            exportDisplayFbo = null;
        }
    }
}