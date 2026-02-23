package com.retrocam.scene;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Owns all scene geometry and materials.
 * Call {@link #build()} after adding all primitives to produce the flat
 * triangle list consumed by {@link BVHBuilder} and {@link SceneUploader}.
 */
public final class Scene {

    private final List<Material>  materials  = new ArrayList<>();
    private final List<Primitive> primitives = new ArrayList<>();

    /** Cached after build(). */
    private List<GpuTriangle> triangles = null;

    // ── Authoring API ────────────────────────────────────────────────────────

    /** Adds a material and returns its index. */
    public int addMaterial(Material m) {
        materials.add(m);
        return materials.size() - 1;
    }

    public void addPrimitive(Primitive p) {
        primitives.add(p);
        triangles = null;  // invalidate cache
    }

    // ── Build ────────────────────────────────────────────────────────────────

    /**
     * Tessellates all primitives and collects triangles.
     * Must be called before passing the scene to BVHBuilder or SceneUploader.
     */
    public void build() {
        List<GpuTriangle> list = new ArrayList<>();
        for (Primitive p : primitives) list.addAll(p.toTriangles());
        triangles = list;
    }

    // ── Queries ──────────────────────────────────────────────────────────────

    public List<GpuTriangle> getTriangles() {
        if (triangles == null) throw new IllegalStateException("Call build() first.");
        return Collections.unmodifiableList(triangles);
    }

    public List<Material> getMaterials() {
        return Collections.unmodifiableList(materials);
    }

    public int triangleCount() {
        return triangles == null ? 0 : triangles.size();
    }

    // ── Default Cornell-box-ish scene ─────────────────────────────────────────

    /**
     * Populates this scene with a small Cornell-box-style arrangement so
     * Phase 2/3 have something to look at immediately.
     */
    public static Scene createDefault() {
        Scene s = new Scene();

        int white  = s.addMaterial(Material.diffuse(0.73f, 0.73f, 0.73f));
        int red    = s.addMaterial(Material.diffuse(0.65f, 0.05f, 0.05f));
        int green  = s.addMaterial(Material.diffuse(0.12f, 0.45f, 0.15f));
        int light  = s.addMaterial(Material.emissive(1.0f, 0.95f, 0.85f, 15.0f));
        int metal  = s.addMaterial(Material.metal(0.95f, 0.85f, 0.55f, 0.1f));
        int glass  = s.addMaterial(Material.glass(1.5f));

        float H = 5f; // half-size of the room

        // Room walls (5 boxes opened at front)
        s.addPrimitive(new Box( 0,  0,  0,  H, 0.05f, H, white));  // floor
        s.addPrimitive(new Box( 0, 2*H, 0,  H, 0.05f, H, white));  // ceiling
        s.addPrimitive(new Box(-H,  H,  0,  0.05f, H, H, red));    // left wall
        s.addPrimitive(new Box( H,  H,  0,  0.05f, H, H, green));  // right wall
        s.addPrimitive(new Box( 0,  H, -H, H, H, 0.05f, white));   // back wall

        // Area light (emissive box on ceiling)
        s.addPrimitive(new Box(0, 2*H - 0.1f, 0, 1.5f, 0.05f, 1.5f, light));

        // Objects
        s.addPrimitive(new Box(-1.5f, 1.5f, -1.0f,  1.0f, 1.5f, 1.0f, white));  // tall block
        s.addPrimitive(new Box( 1.5f, 1.0f,  0.5f,  1.0f, 1.0f, 1.0f, metal));  // metal block
        s.addPrimitive(new Sphere(0f, 1.2f, 1.5f, 1.2f, glass));                 // glass sphere

        s.build();
        System.out.printf("[Scene] Built: %d triangles, %d materials%n",
                          s.triangleCount(), s.getMaterials().size());
        return s;
    }
}