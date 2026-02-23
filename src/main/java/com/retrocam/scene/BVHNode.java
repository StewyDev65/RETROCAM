package com.retrocam.scene;

import java.nio.FloatBuffer;

/**
 * CPU-side representation of one flat BVH node, matching the GPU layout:
 *
 *   struct BVHNode {
 *     vec3 aabbMin; float pad0;
 *     vec3 aabbMax; float pad1;
 *     int leftChild; int rightChild; int primStart; int primCount;
 *   };  // 48 bytes, std430
 *
 * Leaf:   primCount > 0, primStart = index into triangle array
 * Interior: primCount == 0, leftChild / rightChild = node indices
 */
public final class BVHNode {

    public float minX, minY, minZ;
    public float maxX, maxY, maxZ;

    public int leftChild  = -1;
    public int rightChild = -1;
    public int primStart  = 0;
    public int primCount  = 0;   // > 0 → leaf

    public static final int FLOATS_PER_NODE = 12; // 48 bytes / 4

    public boolean isLeaf() { return primCount > 0; }

    /** Writes this node into {@code buf} at the current position. */
    public void write(FloatBuffer buf) {
        buf.put(minX).put(minY).put(minZ).put(0f);
        buf.put(maxX).put(maxY).put(maxZ).put(0f);
        // Pack four ints as float bits (read as floatBitsToInt in GLSL)
        buf.put(Float.intBitsToFloat(leftChild));
        buf.put(Float.intBitsToFloat(rightChild));
        buf.put(Float.intBitsToFloat(primStart));
        buf.put(Float.intBitsToFloat(primCount));
    }

    // ── AABB helpers ─────────────────────────────────────────────────────────

    public float surfaceArea() {
        float dx = maxX - minX;
        float dy = maxY - minY;
        float dz = maxZ - minZ;
        return 2f * (dx * dy + dy * dz + dz * dx);
    }

    public void expandBy(GpuTriangle t) {
        minX = Math.min(minX, t.minX()); minY = Math.min(minY, t.minY()); minZ = Math.min(minZ, t.minZ());
        maxX = Math.max(maxX, t.maxX()); maxY = Math.max(maxY, t.maxY()); maxZ = Math.max(maxZ, t.maxZ());
    }

    public static BVHNode fromTriangles(GpuTriangle[] tris, int start, int count) {
        BVHNode n = new BVHNode();
        n.minX = n.minY = n.minZ =  Float.MAX_VALUE;
        n.maxX = n.maxY = n.maxZ = -Float.MAX_VALUE;
        for (int i = start; i < start + count; i++) n.expandBy(tris[i]);
        return n;
    }
}