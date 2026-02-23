package com.retrocam.scene;

import com.retrocam.gl.SSBO;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;

/**
 * Builds GPU SSBOs from a BVH-built scene.
 * Binding points are fixed and shared with all shaders via common.glsl:
 *   0 — Triangles
 *   1 — BVH nodes
 *   2 — Materials
 */
public final class SceneUploader {

    public static final int BINDING_TRIANGLES = 0;
    public static final int BINDING_BVH       = 1;
    public static final int BINDING_MATERIALS = 2;

    private SSBO triangleSSBO;
    private SSBO bvhSSBO;
    private SSBO materialSSBO;

    private int triangleCount;
    private int nodeCount;

    // ── Upload ────────────────────────────────────────────────────────────────

    /**
     * Tessellates, builds BVH, and uploads everything to GPU.
     * Returns {@code this} for chaining.
     */
    public SceneUploader upload(Scene scene) {
        // 1. Build BVH (also reorders triangles)
        BVHBuilder bvh = BVHBuilder.build(scene.getTriangles());
        triangleCount  = bvh.triangleCount();
        nodeCount      = bvh.nodeCount();

        // 2. Upload triangles (reordered to match BVH leaf indices)
        GpuTriangle[] tris = bvh.getTriangles();
        FloatBuffer triBuf = MemoryUtil.memAllocFloat(
                triangleCount * GpuTriangle.FLOATS_PER_TRIANGLE);
        for (GpuTriangle t : tris) t.write(triBuf);
        triBuf.flip();
        destroyIfExists(triangleSSBO);
        triangleSSBO = SSBO.create(triBuf);
        MemoryUtil.memFree(triBuf);

        // 3. Upload BVH nodes
        BVHNode[] nodes = bvh.getNodes();
        FloatBuffer bvhBuf = MemoryUtil.memAllocFloat(
                nodeCount * BVHNode.FLOATS_PER_NODE);
        for (BVHNode n : nodes) n.write(bvhBuf);
        bvhBuf.flip();
        destroyIfExists(bvhSSBO);
        bvhSSBO = SSBO.create(bvhBuf);
        MemoryUtil.memFree(bvhBuf);

        // 4. Upload materials
        var materials = scene.getMaterials();
        FloatBuffer matBuf = MemoryUtil.memAllocFloat(
                materials.size() * Material.FLOATS_PER_MATERIAL);
        for (Material m : materials) m.write(matBuf);
        matBuf.flip();
        destroyIfExists(materialSSBO);
        materialSSBO = SSBO.create(matBuf);
        MemoryUtil.memFree(matBuf);

        System.out.printf("[SceneUploader] Uploaded: %d tris | %d BVH nodes | %d materials%n",
                          triangleCount, nodeCount, materials.size());
        return this;
    }

    // ── Bind / unbind ─────────────────────────────────────────────────────────

    public void bind() {
        triangleSSBO.bind(BINDING_TRIANGLES);
        bvhSSBO.bind(BINDING_BVH);
        materialSSBO.bind(BINDING_MATERIALS);
    }

    public void unbind() {
        triangleSSBO.unbind(BINDING_TRIANGLES);
        bvhSSBO.unbind(BINDING_BVH);
        materialSSBO.unbind(BINDING_MATERIALS);
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public int getTriangleCount() { return triangleCount; }
    public int getNodeCount()     { return nodeCount;     }

    // ── Cleanup ───────────────────────────────────────────────────────────────

    public void destroy() {
        destroyIfExists(triangleSSBO);
        destroyIfExists(bvhSSBO);
        destroyIfExists(materialSSBO);
    }

    private void destroyIfExists(SSBO ssbo) {
        if (ssbo != null) ssbo.destroy();
    }
}