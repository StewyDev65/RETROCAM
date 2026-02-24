package com.retrocam.export;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Assembles a directory of sequentially-numbered PNG frames into a video file
 * using an FFmpeg subprocess.
 *
 * <p>FFmpeg is invoked via {@link ProcessBuilder}. If FFmpeg is not found on
 * the system PATH, {@link #isFFmpegAvailable()} returns {@code false} and the
 * caller should fall back to keeping the image sequence.</p>
 *
 * <h3>Pipeline contract</h3>
 * <ol>
 *   <li>Frames are written to the temp directory by {@link FrameExporter} with
 *       zero-padded names: {@code frame_00000.png}, {@code frame_00001.png}, …</li>
 *   <li>After all frames are on disk, call {@link #encode(RenderJob)} to run
 *       FFmpeg and produce the final container file.</li>
 *   <li>Optionally call {@link #cleanupTempFrames(RenderJob)} to delete the
 *       temp directory after successful encoding.</li>
 * </ol>
 */
public final class VideoExporter {

    // ── FFmpeg availability check ─────────────────────────────────────────────

    private static Boolean ffmpegAvailable = null; // lazily cached

    /**
     * Returns {@code true} if {@code ffmpeg} can be found on the system PATH.
     * Result is cached after the first call.
     */
    public static boolean isFFmpegAvailable() {
        if (ffmpegAvailable != null) return ffmpegAvailable;
        try {
            Process p = new ProcessBuilder("ffmpeg", "-version")
                .redirectErrorStream(true)
                .start();
            p.waitFor();
            ffmpegAvailable = (p.exitValue() == 0);
        } catch (Exception e) {
            ffmpegAvailable = false;
        }
        return ffmpegAvailable;
    }

    // ── Encoding ──────────────────────────────────────────────────────────────

    /**
     * Runs FFmpeg to encode all frames in {@code job.getTempFrameDir()} into
     * the final output file described by the job.
     *
     * @throws VideoEncodeException if FFmpeg is unavailable or exits non-zero
     */
    public void encode(RenderJob job) {
        if (!isFFmpegAvailable()) {
            throw new VideoEncodeException(
                "FFmpeg not found on PATH. The image sequence has been saved to: "
                + job.getTempFrameDir());
        }

        String framePattern = job.getTempFrameDir() + File.separator + "frame_%05d.png";
        String outputFile   = job.getFullOutputPath();
        ensureParentDirs(outputFile);

        List<String> cmd = buildCommand(job, framePattern, outputFile);
        System.out.println("[VideoExporter] Running: " + String.join(" ", cmd));

        try {
            ProcessBuilder pb = new ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .inheritIO(); // stream FFmpeg output to our stdout for visibility
            Process process = pb.start();
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new VideoEncodeException(
                    "FFmpeg exited with code " + exitCode + ". Check console for details.");
            }
        } catch (IOException | InterruptedException e) {
            throw new VideoEncodeException("FFmpeg execution failed: " + e.getMessage(), e);
        }

        System.out.println("[VideoExporter] Encoded: " + outputFile);
    }

    /**
     * Deletes the temporary frame directory and all PNG files inside it.
     * Safe to call even if the directory does not exist.
     */
    public void cleanupTempFrames(RenderJob job) {
        File dir = new File(job.getTempFrameDir());
        if (!dir.exists()) return;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) f.delete();
        }
        dir.delete();
        System.out.println("[VideoExporter] Cleaned up temp frames: " + dir.getPath());
    }

    // ── FFmpeg command builder ────────────────────────────────────────────────

    private List<String> buildCommand(RenderJob job, String framePattern, String outputFile) {
        List<String> cmd = new ArrayList<>();
        cmd.add("ffmpeg");
        cmd.add("-y");                            // overwrite without asking
        cmd.add("-framerate"); cmd.add(formatFps(job.fps));
        cmd.add("-i");         cmd.add(framePattern);

        if (job.format == RenderFormat.VIDEO_MP4) {
            cmd.add("-c:v");       cmd.add("libx264");
            cmd.add("-crf");       cmd.add("18");        // high quality
            cmd.add("-pix_fmt");   cmd.add("yuv420p");   // broad compatibility
            cmd.add("-movflags");  cmd.add("+faststart"); // streaming-friendly
        } else if (job.format == RenderFormat.VIDEO_MOV) {
            cmd.add("-c:v");       cmd.add("prores_ks");
            cmd.add("-profile:v"); cmd.add("3");          // ProRes 422 HQ
            cmd.add("-pix_fmt");   cmd.add("yuv422p10le");
        }

        cmd.add(outputFile);
        return cmd;
    }

    /** Formats FPS as a string, using integer form when lossless (e.g. 30000/1001 for 29.97). */
    private String formatFps(float fps) {
        // Common NTSC rates
        if (Math.abs(fps - 29.97f) < 0.01f) return "30000/1001";
        if (Math.abs(fps - 23.976f) < 0.01f) return "24000/1001";
        if (Math.abs(fps - 59.94f) < 0.01f) return "60000/1001";
        // Integer rates
        int rounded = Math.round(fps);
        if (Math.abs(fps - rounded) < 0.01f) return String.valueOf(rounded);
        return String.valueOf(fps);
    }

    private static void ensureParentDirs(String path) {
        File parent = new File(path).getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
    }

    // ── Exception type ────────────────────────────────────────────────────────

    public static final class VideoEncodeException extends RuntimeException {
        public VideoEncodeException(String message) { super(message); }
        public VideoEncodeException(String message, Throwable cause) { super(message, cause); }
    }
}