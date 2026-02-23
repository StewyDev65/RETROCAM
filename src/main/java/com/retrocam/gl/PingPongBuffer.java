package com.retrocam.gl;

/**
 * Holds two {@link Framebuffer}s and alternates between them each swap.
 * Used by the 20-pass post-process stack so each pass reads from one
 * buffer and writes into the other without allocating per-pass.
 */
public final class PingPongBuffer {

    private final Framebuffer[] buffers;
    private int writeIndex = 0;  // index currently being written to

    public PingPongBuffer(int width, int height) {
        this(width, height, org.lwjgl.opengl.GL30.GL_RGBA32F);
    }

    public PingPongBuffer(int width, int height, int internalFormat) {
        buffers = new Framebuffer[]{
            new Framebuffer(width, height, internalFormat),
            new Framebuffer(width, height, internalFormat)
        };
    }

    // ── Access ────────────────────────────────────────────────────────────────

    /** The framebuffer currently designated as the render target. */
    public Framebuffer writeBuffer() { return buffers[writeIndex]; }

    /** The framebuffer that was written in the previous pass (read source). */
    public Framebuffer readBuffer()  { return buffers[1 - writeIndex]; }

    /**
     * Swaps read and write roles.
     * Call once after each pass has finished rendering.
     */
    public void swap() { writeIndex = 1 - writeIndex; }

    // ── Resize ────────────────────────────────────────────────────────────────

    public void resize(int newWidth, int newHeight) {
        for (Framebuffer fb : buffers) fb.resize(newWidth, newHeight);
    }

    // ── Cleanup ───────────────────────────────────────────────────────────────

    public void destroy() {
        for (Framebuffer fb : buffers) fb.destroy();
    }
}
