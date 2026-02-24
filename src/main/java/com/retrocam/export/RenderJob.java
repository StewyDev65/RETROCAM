package com.retrocam.export;

import com.retrocam.keyframe.KeyframeTimeline;

/**
 * Immutable descriptor for a single render-to-file job.
 * Use {@link Builder} to construct. All validation happens at {@link Builder#build()}.
 */
public final class RenderJob {

    // ── Output ─────────────────────────────────────────────────────────────────
    public final String       outputPath;
    public final RenderFormat format;

    // ── Quality ────────────────────────────────────────────────────────────────
    public final int samplesPerFrame;
    public final int jpegQuality;

    // ── Video ──────────────────────────────────────────────────────────────────
    public final float durationSeconds;
    public final float fps;

    // ── Keyframes ─────────────────────────────────────────────────────────────
    /**
     * Keyframe timeline for animating parameters over video duration.
     * Null for photo jobs or video jobs with no keyframes defined.
     * When non-null, {@link RenderPipeline} calls
     * {@link KeyframeTimeline#apply(float)} before each frame's accumulation.
     */
    public final KeyframeTimeline keyframeTimeline;

    // ── Derived ────────────────────────────────────────────────────────────────

    public int getTotalFrames() {
        if (!format.isVideo) return 1;
        return Math.max(1, Math.round(durationSeconds * fps));
    }

    public float getFrameDt() {
        return fps > 0 ? 1.0f / fps : 1.0f / 29.97f;
    }

    public String getFullOutputPath() {
        return outputPath + format.extension;
    }

    public String getFramePath(int frameIndex) {
        return String.format("%s_frames/frame_%05d.png", outputPath, frameIndex);
    }

    public String getTempFrameDir() {
        return outputPath + "_frames";
    }

    // ── Construction ──────────────────────────────────────────────────────────

    private RenderJob(Builder b) {
        this.outputPath       = b.outputPath;
        this.format           = b.format;
        this.samplesPerFrame  = b.samplesPerFrame;
        this.jpegQuality      = b.jpegQuality;
        this.durationSeconds  = b.durationSeconds;
        this.fps              = b.fps;
        this.keyframeTimeline = b.keyframeTimeline;
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String           outputPath       = "output";
        private RenderFormat     format           = RenderFormat.PHOTO_PNG;
        private int              samplesPerFrame  = 128;
        private int              jpegQuality      = 90;
        private float            durationSeconds  = 5.0f;
        private float            fps              = 29.97f;
        private KeyframeTimeline keyframeTimeline = null;

        public Builder outputPath(String path)              { this.outputPath       = path;  return this; }
        public Builder format(RenderFormat fmt)             { this.format           = fmt;   return this; }
        public Builder samplesPerFrame(int spp)             { this.samplesPerFrame  = spp;   return this; }
        public Builder jpegQuality(int q)                   { this.jpegQuality      = q;     return this; }
        public Builder durationSeconds(float dur)           { this.durationSeconds  = dur;   return this; }
        public Builder fps(float fps)                       { this.fps              = fps;   return this; }
        public Builder keyframeTimeline(KeyframeTimeline t) { this.keyframeTimeline = t;     return this; }

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
