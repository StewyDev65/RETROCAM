package com.retrocam.post;

import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL30.GL_CLAMP_TO_EDGE;
import static org.lwjgl.stb.STBImage.*;

/**
 * Loads an image from disk (PNG, JPG, BMP, TGA, etc.) via STB_image and
 * uploads it as an OpenGL GL_RGBA8 texture for use as the starting input
 * to the post-process stack in static image test mode.
 *
 * <p>The vertical flip ensures the image matches OpenGL's bottom-left origin
 * convention so UVs produced by {@code fullscreen.vert} sample correctly.</p>
 */
public final class StaticImageLoader {

    private int    texId      = 0;
    private int    imgWidth   = 0;
    private int    imgHeight  = 0;
    private String loadedPath = null;

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Load an image file and upload it as a GL texture.
     * Replaces any previously loaded image.
     *
     * @param path file-system path to the image
     * @throws RuntimeException if STB cannot decode the file
     */
    public void load(String path) {
        destroy(); // free the previous texture if any

        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer widthBuf    = stack.mallocInt(1);
            IntBuffer heightBuf   = stack.mallocInt(1);
            IntBuffer channelsBuf = stack.mallocInt(1);

            stbi_set_flip_vertically_on_load(true);
            ByteBuffer data = stbi_load(path, widthBuf, heightBuf, channelsBuf, 4);
            if (data == null) {
                throw new RuntimeException(
                    "Failed to load test image '" + path + "': " + stbi_failure_reason());
            }

            imgWidth  = widthBuf.get(0);
            imgHeight = heightBuf.get(0);

            texId = glGenTextures();
            glBindTexture(GL_TEXTURE_2D, texId);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8,
                         imgWidth, imgHeight, 0,
                         GL_RGBA, GL_UNSIGNED_BYTE, data);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            glBindTexture(GL_TEXTURE_2D, 0);

            stbi_image_free(data);
            loadedPath = path;

            System.out.printf("[StaticImageLoader] Loaded %dx%d from '%s'%n",
                              imgWidth, imgHeight, path);
        }
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public boolean isLoaded()  { return texId != 0; }
    public int     texId()     { return texId; }
    public int     imgWidth()  { return imgWidth; }
    public int     imgHeight() { return imgHeight; }
    public String  loadedPath(){ return loadedPath; }

    // ── Cleanup ───────────────────────────────────────────────────────────────

    public void destroy() {
        if (texId != 0) {
            glDeleteTextures(texId);
            texId      = 0;
            imgWidth   = 0;
            imgHeight  = 0;
            loadedPath = null;
        }
    }
}