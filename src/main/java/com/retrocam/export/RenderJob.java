package com.retrocam.export;

import com.retrocam.keyframe.KeyframeTimeline;

/**
 * Immutable descriptor for a single render-to-file job.
 *
 * Use {@link Builder} to construct. All validation happens at {@link Builder#build()}.
 *
 * Keyframe support: the {@link KeyframeTimeline} field is reserved for Phase 2.
 * It is always {@code null} here and the pipeline checks for null before attempting
 * to apply keyframes. When Phase 2 is implemented, a non-null timeline will drive
 * per-frame parameter interpolation over the job's duration.
 */
public final class RenderJob {

    // ── Output ─────────────────────────────────────────────────────────────────
    /** Base output path without extension (extension is appended from format). */
    public final String       outputPath;
    public final RenderFormat format;

    // ── Quality ────────────────────────────────────────────────────────────────
    /** Path-trace sample passes accumulated per rendered frame. */
    public final int samplesPerFrame;
    /** JPEG quality (1–100), ignored for non-JPEG formats. */
    public final int jpegQuality;

    // ── Video ──────────────────────────────────────────────────────────────────
    /** Total video duration in seconds. Ignored for photo formats. */
    public final float durationSeconds;
    /** Frames per second. Ignored for photo formats. */
    public final float fps;

    // ── Keyframes (Phase 2 stub) ───────────────────────────────────────────────
    /**
     * Keyframe timeline for animating camera/object parameters over the video duration.
     * Always {@code null} in Phase 1 — pipeline skips keyframe evaluation when null.
     *
     * TODO Phase 2: populate from the keyframe editor and apply per frame inside
     *               {@link RenderPipeline#applyKeyframes(float, com.retrocam.export.RenderContext)}.
     */
    public final KeyframeTimeline keyframeTimeline;

    // ── Derived ────────────────────────────────────────────────────────────────

    /** Total number of frames for video jobs; always 1 for photo jobs. */
    public int getTotalFrames() {
        if (!format.isVideo) return 1;
        return Math.max(1, Math.round(durationSeconds * fps));
    }

    /** Frame time delta in seconds (1/fps). */
    public float getFrameDt() {
        return fps > 0 ? 1.0f / fps : 1.0f / 29.97f;
    }

    /** Returns the full output path including the file extension. */
    public String getFullOutputPath() {
        return outputPath + format.extension;
    }

    /** Returns the path for a specific frame of an image sequence or temp dir. */
    public String getFramePath(int frameIndex) {
        return String.format("%s_frames/frame_%05d.png", outputPath, frameIndex);
    }

    /** Returns the temp directory path used during video encoding. */
    public String getTempFrameDir() {
        return outputPath + "_frames";
    }

    // ── Construction ──────────────────────────────────────────────────────────

    private RenderJob(Builder b) {
        this.outputPath        = b.outputPath;
        this.format            = b.format;
        this.samplesPerFrame   = b.samplesPerFrame;
        this.jpegQuality       = b.jpegQuality;
        this.durationSeconds   = b.durationSeconds;
        this.fps               = b.fps;
        this.keyframeTimeline  = b.keyframeTimeline;
    }

    public static Builder builder() { return new Builder(); }

    // ── Builder ───────────────────────────────────────────────────────────────

    public static final class Builder {
        private String            outputPath      = "output";
        private RenderFormat      format          = RenderFormat.PHOTO_PNG;
        private int               samplesPerFrame = 128;
        private int               jpegQuality     = 90;
        private float             durationSeconds = 5.0f;
        private float             fps             = 29.97f;
        private KeyframeTimeline  keyframeTimeline = null;  // Phase 2

        public Builder outputPath(String path)          { this.outputPath      = path;      return this; }
        public Builder format(RenderFormat fmt)         { this.format          = fmt;       return this; }
        public Builder samplesPerFrame(int spp)         { this.samplesPerFrame = spp;       return this; }
        public Builder jpegQuality(int q)               { this.jpegQuality     = q;         return this; }
        public Builder durationSeconds(float dur)       { this.durationSeconds = dur;       return this; }
        public Builder fps(float fps)                   { this.fps             = fps;       return this; }
        // Phase 2: public Builder keyframeTimeline(KeyframeTimeline kft) { ... }

        public RenderJob build() {
            if (outputPath == null || outputPath.isBlank())
                throw new IllegalStateException("outputPath must not be blank");
            if (samplesPerFrame < 1)
                throw new IllegalStateException("samplesPerFrame must be >= 1");
            if (format.isVideo && durationSeconds <= 0)
                throw new IllegalStateException("durationSeconds must be > 0 for video");
            if (format.isVideo && fps <= 0)
                throw new IllegalStateException("fps must be > 0 for video");
            return new RenderJob(this);
        }
    }
}