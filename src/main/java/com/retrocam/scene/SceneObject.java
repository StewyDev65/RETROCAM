package com.retrocam.scene;

/**
 * Mutable descriptor for a single scene primitive owned by {@link SceneEditor}.
 * Converts to an immutable {@link Primitive} on-demand for BVH/GPU upload.
 * All positional changes are reflected immediately in the next {@link SceneEditor#buildScene()} call.
 */
public final class SceneObject {

    public enum Type { BOX, SPHERE }

    public String name;
    public Type   type;

    // World-space centre
    public float px, py, pz;

    // BOX:    half-extents (sx, sy, sz)
    // SPHERE: sx = radius  (sy, sz ignored during tessellation)
    public float sx = 1f, sy = 1f, sz = 1f;

    // Sphere tessellation quality (ignored for BOX)
    public int stacks = 24, slices = 24;

    public int materialIndex;

    public SceneObject(String name, Type type,
                       float px, float py, float pz,
                       float sx, float sy, float sz,
                       int materialIndex) {
        this.name = name;
        this.type = type;
        this.px = px; this.py = py; this.pz = pz;
        this.sx = sx; this.sy = sy; this.sz = sz;
        this.materialIndex = materialIndex;
    }

    /** Instantiates the corresponding immutable {@link Primitive}. */
    public Primitive toPrimitive() {
        return switch (type) {
            case BOX    -> new Box(px, py, pz, sx, sy, sz, materialIndex);
            case SPHERE -> new Sphere(px, py, pz, sx, materialIndex, stacks, slices);
        };
    }

    // ── Default factories ─────────────────────────────────────────────────────

    public static SceneObject defaultBox(int matIndex) {
        return new SceneObject("New Box", Type.BOX, 0f, 1f, 0f, 1f, 1f, 1f, matIndex);
    }

    public static SceneObject defaultSphere(int matIndex) {
        return new SceneObject("New Sphere", Type.SPHERE, 0f, 1f, 0f, 0.5f, 0.5f, 0.5f, matIndex);
    }
}