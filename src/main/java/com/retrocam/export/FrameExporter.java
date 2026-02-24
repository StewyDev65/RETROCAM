package com.retrocam.export;

import org.lwjgl.BufferUtils;
import org.lwjgl.stb.STBImageWrite;

import java.io.File;
import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL11.*;

/**
 * Reads a completed GL texture back to CPU memory and writes it to disk.
 *
 * <p>Supports PNG and JPEG output. STB handles all encoding. GL textures have
 * origin at bottom-left, so {@code stbi_flip_vertically_on_write(true)} is
 * applied globally to produce correctly-oriented images.</p>
 *
 * <p>This class is stateless and reusable across jobs — create one instance
 * and call {@link #exportFrame} for each frame.</p>
 */
public final class FrameExporter {

    private static final int CHANNELS = 4; // RGBA

    private final int width;
    private final int height;

    /** Persistent read-back buffer — allocated once and reused per frame. */
    private final ByteBuffer pixelBuffer;

    public FrameExporter(int width, int height) {
        this.width       = width;
        this.height      = height;
        this.pixelBuffer = BufferUtils.createByteBuffer(width * height * CHANNELS);

        // Flip Y globally so all writes produce top-left origin images.
        STBImageWrite.stbi_flip_vertically_on_write(true);
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Reads {@code texId} from the GPU, then writes it to {@code outputPath}.
     * The output format is determined by the file extension in {@code outputPath}.
     *
     * @param texId      GL texture ID of the final post-processed frame (RGBA8 or RGBA32F)
     * @param outputPath Full path including extension (.png / .jpg)
     * @param jpegQuality JPEG quality 1–100 (ignored for PNG)
     * @throws ExportException if the write fails
     */
    public void exportFrame(int texId, String outputPath, int jpegQuality) {
        ensureParentDirs(outputPath);
        pixelBuffer.clear();

        glBindTexture(GL_TEXTURE_2D, texId);
        glGetTexImage(GL_TEXTURE_2D, 0, GL_RGBA, GL_UNSIGNED_BYTE, pixelBuffer);
        glBindTexture(GL_TEXTURE_2D, 0);

        String lower = outputPath.toLowerCase();
        boolean ok;
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            ok = STBImageWrite.stbi_write_jpg(outputPath, width, height, CHANNELS, pixelBuffer, jpegQuality);
        } else {
            ok = STBImageWrite.stbi_write_png(outputPath, width, height, CHANNELS, pixelBuffer, width * CHANNELS);
        }

        if (!ok) {
            throw new ExportException("STB failed to write frame: " + outputPath);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static void ensureParentDirs(String path) {
        File parent = new File(path).getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
    }

    // ── Exception type ────────────────────────────────────────────────────────

    public static final class ExportException extends RuntimeException {
        public ExportException(String message) { super(message); }
        public ExportException(String message, Throwable cause) { super(message, cause); }
    }
}