package com.retrocam.scene;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Surface Area Heuristic (SAH) BVH builder.
 *
 * Constructs a binary BVH on the CPU using SAH with 8 buckets per axis.
 * The result is a flat {@code BVHNode[]} and a reordered {@code GpuTriangle[]}
 * uploaded to GPU SSBOs by {@link SceneUploader}.
 *
 * Traversal cost constants follow the standard:
 *   C_trav = 1.0,  C_isect = 1.5
 * Leaf threshold: ≤ 4 triangles always a leaf regardless of SAH.
 */
public final class BVHBuilder {

    private static final int   BUCKETS        = 8;
    private static final int   LEAF_MAX       = 4;
    private static final float C_TRAV         = 1.0f;
    private static final float C_ISECT        = 1.5f;

    private final List<BVHNode>    nodes;
    private final GpuTriangle[]    tris;      // reordered in-place

    private BVHBuilder(GpuTriangle[] tris) {
        this.tris  = tris;
        this.nodes = new ArrayList<>();
    }

    // ── Public entry point ───────────────────────────────────────────────────

    public static BVHBuilder build(List<GpuTriangle> triangleList) {
        GpuTriangle[] tris = triangleList.toArray(new GpuTriangle[0]);
        BVHBuilder builder = new BVHBuilder(tris);
        builder.subdivide(0, tris.length);
        System.out.printf("[BVH] Built: %d nodes for %d triangles%n",
                          builder.nodes.size(), tris.length);
        return builder;
    }

    // ── Accessors ────────────────────────────────────────────────────────────

    /** Reordered triangle array (matches BVH leaf primStart indices). */
    public GpuTriangle[] getTriangles() { return tris; }

    /** Flat BVH node array in depth-first order. */
    public BVHNode[] getNodes() { return nodes.toArray(new BVHNode[0]); }

    public int nodeCount()     { return nodes.size(); }
    public int triangleCount() { return tris.length;  }

    // ── Core recursion ───────────────────────────────────────────────────────

    /**
     * Recursively subdivides [start, start+count).
     * Returns the index of the newly created node.
     */
    private int subdivide(int start, int count) {
        int nodeIdx = nodes.size();
        BVHNode node = BVHNode.fromTriangles(tris, start, count);
        nodes.add(node);

        if (count <= LEAF_MAX) {
            node.primStart = start;
            node.primCount = count;
            return nodeIdx;
        }

        // SAH: try all three axes, pick the cheapest split
        SplitResult best = findBestSplit(node, start, count);

        // If splitting is more expensive than a leaf, make a leaf
        float leafCost = C_ISECT * count;
        if (best == null || best.cost >= leafCost) {
            node.primStart = start;
            node.primCount = count;
            return nodeIdx;
        }

        // Partition triangles around the split
        int mid = partition(start, count, best.axis, best.splitPos);

        // Both halves must have at least one triangle
        if (mid == start || mid == start + count) {
            node.primStart = start;
            node.primCount = count;
            return nodeIdx;
        }

        node.leftChild  = subdivide(start, mid - start);
        node.rightChild = subdivide(mid,   start + count - mid);
        return nodeIdx;
    }

    // ── SAH split search ─────────────────────────────────────────────────────

    private SplitResult findBestSplit(BVHNode parent, int start, int count) {
        float parentSA = parent.surfaceArea();
        if (parentSA < 1e-8f) return null;

        SplitResult best = null;

        for (int axis = 0; axis < 3; axis++) {
            float lo = axisMin(parent, axis);
            float hi = axisMax(parent, axis);
            if (hi - lo < 1e-6f) continue;

            // Build buckets
            Bucket[] buckets = new Bucket[BUCKETS];
            for (int i = 0; i < BUCKETS; i++) buckets[i] = new Bucket();

            for (int i = start; i < start + count; i++) {
                float c = centroid(tris[i], axis);
                int b = Math.min(BUCKETS - 1,
                        (int)(BUCKETS * (c - lo) / (hi - lo)));
                buckets[b].count++;
                buckets[b].expand(tris[i]);
            }

            // Evaluate BUCKETS-1 possible splits
            for (int s = 0; s < BUCKETS - 1; s++) {
                // Left side: buckets 0..s
                Bucket left = new Bucket();
                for (int i = 0; i <= s; i++) left.merge(buckets[i]);
                // Right side: buckets s+1..BUCKETS-1
                Bucket right = new Bucket();
                for (int i = s + 1; i < BUCKETS; i++) right.merge(buckets[i]);

                if (left.count == 0 || right.count == 0) continue;

                float cost = C_TRAV
                    + (left.sa()  * left.count  * C_ISECT) / parentSA
                    + (right.sa() * right.count * C_ISECT) / parentSA;

                float splitPos = lo + (hi - lo) * (s + 1) / BUCKETS;
                if (best == null || cost < best.cost) {
                    best = new SplitResult(axis, splitPos, cost);
                }
            }
        }
        return best;
    }

    // ── Partition ────────────────────────────────────────────────────────────

    /** In-place partition; returns the mid index. */
    private int partition(int start, int count, int axis, float splitPos) {
        int lo = start;
        int hi = start + count - 1;
        while (lo <= hi) {
            if (centroid(tris[lo], axis) < splitPos) {
                lo++;
            } else {
                GpuTriangle tmp = tris[lo];
                tris[lo] = tris[hi];
                tris[hi] = tmp;
                hi--;
            }
        }
        return lo;
    }

    // ── Axis helpers ─────────────────────────────────────────────────────────

    private static float centroid(GpuTriangle t, int axis) {
        return switch (axis) { case 0 -> t.cx(); case 1 -> t.cy(); default -> t.cz(); };
    }
    private static float axisMin(BVHNode n, int axis) {
        return switch (axis) { case 0 -> n.minX; case 1 -> n.minY; default -> n.minZ; };
    }
    private static float axisMax(BVHNode n, int axis) {
        return switch (axis) { case 0 -> n.maxX; case 1 -> n.maxY; default -> n.maxZ; };
    }

    // ── Inner types ───────────────────────────────────────────────────────────

    private record SplitResult(int axis, float splitPos, float cost) {}

    private static final class Bucket {
        int   count = 0;
        float minX =  Float.MAX_VALUE, minY =  Float.MAX_VALUE, minZ =  Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;

        void expand(GpuTriangle t) {
            minX = Math.min(minX, t.minX()); minY = Math.min(minY, t.minY()); minZ = Math.min(minZ, t.minZ());
            maxX = Math.max(maxX, t.maxX()); maxY = Math.max(maxY, t.maxY()); maxZ = Math.max(maxZ, t.maxZ());
        }
        void merge(Bucket o) {
            count += o.count;
            if (o.count == 0) return;
            minX = Math.min(minX, o.minX); minY = Math.min(minY, o.minY); minZ = Math.min(minZ, o.minZ);
            maxX = Math.max(maxX, o.maxX); maxY = Math.max(maxY, o.maxY); maxZ = Math.max(maxZ, o.maxZ);
        }
        float sa() {
            if (count == 0) return 0f;
            float dx = maxX-minX, dy = maxY-minY, dz = maxZ-minZ;
            return 2f*(dx*dy + dy*dz + dz*dx);
        }
    }
}