package com.retrocam.scene;

import java.nio.FloatBuffer;

/**
 * CPU-side triangle matching the GPU struct (common.glsl):
 *
 *   struct Triangle {
 *     vec3 v0; float pad0;   vec3 v1; float pad1;   vec3 v2; float pad2;  // 48 bytes
 *     vec3 n0; float pad3;   vec3 n1; float pad4;   vec3 n2; float pad5;  // 48 bytes
 *     vec2 uv0; vec2 uv1;   vec2 uv2; int matIndex; float pad6;           // 32 bytes
 *   };  // 128 bytes total, std430
 */
public final class GpuTriangle {

    // Positions
    public float v0x, v0y, v0z;
    public float v1x, v1y, v1z;
    public float v2x, v2y, v2z;

    // Normals
    public float n0x, n0y, n0z;
    public float n1x, n1y, n1z;
    public float n2x, n2y, n2z;

    // UVs
    public float uv0s, uv0t;
    public float uv1s, uv1t;
    public float uv2s, uv2t;

    public int matIndex;

    public static final int FLOATS_PER_TRIANGLE = 32; // 128 bytes / 4

    // ── Geometry helpers ─────────────────────────────────────────────────────

    /** Computes flat face normal and assigns it to all 3 vertices. */
    public GpuTriangle withFlatNormal() {
        float ax = v1x - v0x, ay = v1y - v0y, az = v1z - v0z;
        float bx = v2x - v0x, by = v2y - v0y, bz = v2z - v0z;
        float nx = ay * bz - az * by;
        float ny = az * bx - ax * bz;
        float nz = ax * by - ay * bx;
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len > 1e-8f) { nx /= len; ny /= len; nz /= len; }
        n0x = n1x = n2x = nx;
        n0y = n1y = n2y = ny;
        n0z = n1z = n2z = nz;
        return this;
    }

    /** AABB min corner (used by BVH builder). */
    public float minX() { return Math.min(v0x, Math.min(v1x, v2x)); }
    public float minY() { return Math.min(v0y, Math.min(v1y, v2y)); }
    public float minZ() { return Math.min(v0z, Math.min(v1z, v2z)); }

    /** AABB max corner (used by BVH builder). */
    public float maxX() { return Math.max(v0x, Math.max(v1x, v2x)); }
    public float maxY() { return Math.max(v0y, Math.max(v1y, v2y)); }
    public float maxZ() { return Math.max(v0z, Math.max(v1z, v2z)); }

    /** Centroid x/y/z (used for SAH bucket sorting). */
    public float cx() { return (v0x + v1x + v2x) / 3f; }
    public float cy() { return (v0y + v1y + v2y) / 3f; }
    public float cz() { return (v0z + v1z + v2z) / 3f; }

    /** Writes this triangle into {@code buf} at the current position. */
    public void write(FloatBuffer buf) {
        buf.put(v0x).put(v0y).put(v0z).put(0f);
        buf.put(v1x).put(v1y).put(v1z).put(0f);
        buf.put(v2x).put(v2y).put(v2z).put(0f);
        buf.put(n0x).put(n0y).put(n0z).put(0f);
        buf.put(n1x).put(n1y).put(n1z).put(0f);
        buf.put(n2x).put(n2y).put(n2z).put(0f);
        buf.put(uv0s).put(uv0t);
        buf.put(uv1s).put(uv1t);
        buf.put(uv2s).put(uv2t);
        buf.put(Float.intBitsToFloat(matIndex)).put(0f);
    }
}