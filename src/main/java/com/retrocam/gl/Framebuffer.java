package com.retrocam.gl;

import static org.lwjgl.opengl.GL43.*;

/**
 * An OpenGL FBO with a single colour attachment.
 * Default internal format is GL_RGBA32F (HDR).
 */
public final class Framebuffer {

    private int fboId;
    private int textureId;
    private int width;
    private int height;
    private final int internalFormat;

    public Framebuffer(int width, int height) {
        this(width, height, GL_RGBA32F);
    }

    public Framebuffer(int width, int height, int internalFormat) {
        this.internalFormat = internalFormat;
        allocate(width, height);
    }

    // ── Bind / unbind ─────────────────────────────────────────────────────────

    public void bindForWrite() {
        glBindFramebuffer(GL_FRAMEBUFFER, fboId);
        glViewport(0, 0, width, height);
    }

    public void unbind() {
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    public void bindTexture(int unit) {
        glActiveTexture(GL_TEXTURE0 + unit);
        glBindTexture(GL_TEXTURE_2D, textureId);
    }

    // ── Resize ────────────────────────────────────────────────────────────────

    /** Destroys and re-creates GPU objects at the new size. */
    public void resize(int newWidth, int newHeight) {
        destroy();
        allocate(newWidth, newHeight);
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public int fboId()     { return fboId;     }
    public int textureId() { return textureId; }
    public int width()     { return width;     }
    public int height()    { return height;    }

    // ── Cleanup ───────────────────────────────────────────────────────────────

    public void destroy() {
        glDeleteFramebuffers(fboId);
        glDeleteTextures(textureId);
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private void allocate(int w, int h) {
        this.width  = w;
        this.height = h;

        textureId = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, textureId);
        glTexImage2D(GL_TEXTURE_2D, 0, internalFormat, w, h, 0,
                     GL_RGBA, GL_FLOAT, (java.nio.ByteBuffer) null);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glBindTexture(GL_TEXTURE_2D, 0);

        fboId = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, fboId);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                               GL_TEXTURE_2D, textureId, 0);

        int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
        if (status != GL_FRAMEBUFFER_COMPLETE) {
            throw new RuntimeException("Framebuffer incomplete: 0x" +
                                       Integer.toHexString(status));
        }
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }
}
