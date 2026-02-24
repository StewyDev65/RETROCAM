package com.retrocam.scene;

import com.retrocam.gl.SSBO;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds GPU SSBOs from a BVH-built scene.
 * Binding points shared with all shaders:
 *   0 — Triangles
 *   1 — BVH nodes
 *   2 — Materials
 *   3 — Lights   (Phase 5 addition: emissive triangle list for photon emission)
 *
 * Light struct layout (std430, 80 bytes = 20 floats):
 *   vec3 v0;       float area;
 *   vec3 v1;       float pad1;
 *   vec3 v2;       float pad2;
 *   vec3 emission; float power;      // luminance(emission*strength) * area * PI
 *   vec3 normal;   float cdfWeight;  // cumulative CDF [0,1] for importance sampling
 */
public final class SceneUploader {

    // ── SSBO binding points ───────────────────────────────────────────────────
    public static final int BINDING_TRIANGLES = 0;
    public static final int BINDING_BVH       = 1;
    public static final int BINDING_MATERIALS = 2;
    public static final int BINDING_LIGHTS    = 3;   // Phase 5

    /** Number of floats per light entry in the SSBO (5 × vec3+pad = 20). */
    private static final int FLOATS_PER_LIGHT = 20;

    // ── GPU buffers ───────────────────────────────────────────────────────────
    private SSBO triangleSSBO;
    private SSBO bvhSSBO;
    private SSBO materialSSBO;
    private SSBO lightSSBO;

    // ── Counts / totals for external access ───────────────────────────────────
    private int   triangleCount;
    private int   nodeCount;
    private int   lightCount;
    private float totalLightPower;   // luminance-weighted area × PI summed over all lights

    // ── Upload ────────────────────────────────────────────────────────────────

    /**
     * Tessellates, builds BVH, and uploads everything to GPU.
     * Returns {@code this} for chaining.
     */
    public SceneUploader upload(Scene scene) {
        var materials = scene.getMaterials();

        // 1. Build BVH (also reorders triangles)
        BVHBuilder bvh = BVHBuilder.build(scene.getTriangles());
        triangleCount  = bvh.triangleCount();
        nodeCount      = bvh.nodeCount();

        GpuTriangle[] tris = bvh.getTriangles();

        // 2. Upload triangles (reordered to match BVH leaf indices)
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
        FloatBuffer matBuf = MemoryUtil.memAllocFloat(
                materials.size() * Material.FLOATS_PER_MATERIAL);
        for (Material m : materials) m.write(matBuf);
        matBuf.flip();
        destroyIfExists(materialSSBO);
        materialSSBO = SSBO.create(matBuf);
        MemoryUtil.memFree(matBuf);

        // 5. Build and upload light list (Phase 5 — emissive triangles)
        uploadLights(tris, materials);

        System.out.printf(
            "[SceneUploader] Uploaded: %d tris | %d BVH nodes | %d materials | %d lights (power=%.2f)%n",
            triangleCount, nodeCount, materials.size(), lightCount, totalLightPower);
        return this;
    }

    // ── Bind / unbind ─────────────────────────────────────────────────────────

    public void bind() {
        triangleSSBO.bind(BINDING_TRIANGLES);
        bvhSSBO     .bind(BINDING_BVH);
        materialSSBO.bind(BINDING_MATERIALS);
        if (lightSSBO != null) lightSSBO.bind(BINDING_LIGHTS);
    }

    public void unbind() {
        triangleSSBO.unbind(BINDING_TRIANGLES);
        bvhSSBO     .unbind(BINDING_BVH);
        materialSSBO.unbind(BINDING_MATERIALS);
        if (lightSSBO != null) lightSSBO.unbind(BINDING_LIGHTS);
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public int   getTriangleCount()   { return triangleCount;   }
    public int   getNodeCount()       { return nodeCount;       }
    public int   getLightCount()      { return lightCount;      }
    public float getTotalLightPower() { return totalLightPower; }

    // ── Cleanup ───────────────────────────────────────────────────────────────

    public void destroy() {
        destroyIfExists(triangleSSBO);
        destroyIfExists(bvhSSBO);
        destroyIfExists(materialSSBO);
        destroyIfExists(lightSSBO);
    }

    // ── Light SSBO builder ────────────────────────────────────────────────────

    /**
     * Identifies all emissive triangles in the scene, computes their area and
     * emission power, builds a CDF for importance-weighted light selection, and
     * uploads the result as a Light SSBO at binding 3.
     *
     * <p>The CDF allows the photon trace shader to pick a light proportional to
     * its power (area × luminance × π) using a single random number and a linear
     * scan, which is efficient for scenes with small light counts.
     */
    private void uploadLights(GpuTriangle[] tris, List<Material> materials) {
        // Collect raw light entries.
        List<float[]> entries = new ArrayList<>();
        float sumPower = 0f;

        for (GpuTriangle tri : tris) {
            Material mat = materials.get(tri.matIndex);
            if (mat.emissionStrength <= 0f) continue;

            // Triangle area = 0.5 × |e1 × e2|
            float e1x = tri.v1x - tri.v0x, e1y = tri.v1y - tri.v0y, e1z = tri.v1z - tri.v0z;
            float e2x = tri.v2x - tri.v0x, e2y = tri.v2y - tri.v0y, e2z = tri.v2z - tri.v0z;
            float cx  = e1y*e2z - e1z*e2y;
            float cy  = e1z*e2x - e1x*e2z;
            float cz  = e1x*e2y - e1y*e2x;
            float area = 0.5f * (float) Math.sqrt(cx*cx + cy*cy + cz*cz);
            if (area < 1e-8f) continue;

            // Emission color and luminance-weighted power
            float er = mat.emission[0] * mat.emissionStrength;
            float eg = mat.emission[1] * mat.emissionStrength;
            float eb = mat.emission[2] * mat.emissionStrength;
            float lum = 0.299f*er + 0.587f*eg + 0.114f*eb;
            float power = lum * area * (float) Math.PI;
            sumPower += power;

            // Face normal (flat-shaded — all vertices share the same normal)
            float nx = tri.n0x, ny = tri.n0y, nz = tri.n0z;

            // Pack 20 floats: v0(3)+area(1) v1(3)+0(1) v2(3)+0(1) em(3)+pow(1) n(3)+0(1)
            entries.add(new float[]{
                tri.v0x, tri.v0y, tri.v0z, area,
                tri.v1x, tri.v1y, tri.v1z, 0f,
                tri.v2x, tri.v2y, tri.v2z, 0f,
                er,      eg,      eb,      power,
                nx,      ny,      nz,      0f       // cdfWeight filled after CDF pass
            });
        }

        lightCount      = entries.size();
        totalLightPower = sumPower;

        // Allocate a 1-triangle stub when the scene has no lights so bindings
        // remain valid (prevents null pointer / GL errors in the shaders).
        if (lightCount == 0) {
            FloatBuffer stub = MemoryUtil.memAllocFloat(FLOATS_PER_LIGHT);
            for (int i = 0; i < FLOATS_PER_LIGHT; i++) stub.put(0f);
            stub.flip();
            destroyIfExists(lightSSBO);
            lightSSBO = SSBO.create(stub);
            MemoryUtil.memFree(stub);
            return;
        }

        // Build CDF: write cumulative fraction into each entry's cdfWeight slot (index 19).
        float cumulative = 0f;
        for (float[] e : entries) {
            cumulative += e[15]; // power stored at index 15
            e[19] = (sumPower > 0f) ? cumulative / sumPower : 1f;
        }
        // Ensure the last entry reaches exactly 1.0 to avoid floating-point gaps.
        entries.get(entries.size() - 1)[19] = 1.0f;

        // Upload to GPU
        FloatBuffer buf = MemoryUtil.memAllocFloat(lightCount * FLOATS_PER_LIGHT);
        for (float[] e : entries) {
            for (float v : e) buf.put(v);
        }
        buf.flip();
        destroyIfExists(lightSSBO);
        lightSSBO = SSBO.create(buf);
        MemoryUtil.memFree(buf);
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private void destroyIfExists(SSBO ssbo) {
        if (ssbo != null) ssbo.destroy();
    }
}