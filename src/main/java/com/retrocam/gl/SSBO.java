package com.retrocam.gl;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.opengl.GL43.*;

/**
 * Generic Shader Storage Buffer Object (SSBO) wrapper.
 * All GPU data structures (BVH nodes, photon map, scene triangles,
 * materials) are stored in SSBOs and bound to named binding points.
 */
public final class SSBO {

    private final int id;
    private int sizeBytes;

    // ── Construction ──────────────────────────────────────────────────────────

    /** Allocate an empty SSBO with the given byte size. */
    public static SSBO allocate(int sizeBytes) {
        SSBO ssbo = new SSBO();
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, ssbo.id);
        glBufferData(GL_SHADER_STORAGE_BUFFER, sizeBytes, GL_DYNAMIC_DRAW);
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);
        ssbo.sizeBytes = sizeBytes;
        return ssbo;
    }

    /** Allocate and upload float data in one call. */
    public static SSBO create(FloatBuffer data) {
        SSBO ssbo = new SSBO();
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, ssbo.id);
        glBufferData(GL_SHADER_STORAGE_BUFFER, data, GL_STATIC_DRAW);
        ssbo.sizeBytes = data.remaining() * Float.BYTES;
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);
        return ssbo;
    }

    /** Allocate and upload int data in one call. */
    public static SSBO create(IntBuffer data) {
        SSBO ssbo = new SSBO();
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, ssbo.id);
        glBufferData(GL_SHADER_STORAGE_BUFFER, data, GL_STATIC_DRAW);
        ssbo.sizeBytes = data.remaining() * Integer.BYTES;
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);
        return ssbo;
    }

    private SSBO() {
        id = glGenBuffers();
    }

    // ── Update ────────────────────────────────────────────────────────────────

    /** Replace entire buffer contents (re-allocates if size changes). */
    public void upload(FloatBuffer data) {
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, id);
        int needed = data.remaining() * Float.BYTES;
        if (needed != sizeBytes) {
            glBufferData(GL_SHADER_STORAGE_BUFFER, data, GL_DYNAMIC_DRAW);
            sizeBytes = needed;
        } else {
            glBufferSubData(GL_SHADER_STORAGE_BUFFER, 0, data);
        }
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);
    }

    /** Bind to the given binding point index. */
    public void bind(int bindingPoint) {
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, bindingPoint, id);
    }

    public void unbind(int bindingPoint) {
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, bindingPoint, 0);
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public int id()        { return id;        }
    public int sizeBytes() { return sizeBytes; }

    // ── Cleanup ───────────────────────────────────────────────────────────────

    public void destroy() { glDeleteBuffers(id); }
}