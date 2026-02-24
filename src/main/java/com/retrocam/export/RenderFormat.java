package com.retrocam.export;

/**
 * Supported render output formats.
 *
 * {@link #isVideo} indicates whether the format produces a multi-frame sequence
 * requiring the full time-stepped video pipeline (temporal state evolution,
 * per-frame post-process progression, etc.).
 */
public enum RenderFormat {

    PHOTO_PNG       ("PNG Image",          ".png",  false, false),
    PHOTO_JPEG      ("JPEG Image",         ".jpg",  false, false),
    IMAGE_SEQUENCE  ("PNG Image Sequence", ".png",  true,  false),
    VIDEO_MP4       ("H.264 MP4",          ".mp4",  true,  true),
    VIDEO_MOV       ("ProRes MOV",         ".mov",  true,  true);

    /** Human-readable label for ImGui combo. */
    public final String label;
    /** File extension including the leading dot. */
    public final String extension;
    /** True when the job spans multiple frames (image sequence or encoded video). */
    public final boolean isVideo;
    /** True when the job requires FFmpeg to encode the final container file. */
    public final boolean requiresFFmpeg;

    RenderFormat(String label, String extension, boolean isVideo, boolean requiresFFmpeg) {
        this.label          = label;
        this.extension      = extension;
        this.isVideo        = isVideo;
        this.requiresFFmpeg = requiresFFmpeg;
    }

    /** Returns all labels in declaration order — useful for ImGui combos. */
    public static String[] labels() {
        RenderFormat[] values = values();
        String[] out = new String[values.length];
        for (int i = 0; i < values.length; i++) out[i] = values[i].label;
        return out;
    }
}