package com.retrocam.scene;

import com.retrocam.keyframe.Keyframeable;

/**
 * Mutable descriptor for a single scene primitive owned by {@link SceneEditor}.
 * Converts to an immutable {@link Primitive} on-demand for BVH/GPU upload.
 * All positional changes are reflected immediately in the next {@link SceneEditor#buildScene()} call.
 */
public final class SceneObject implements Keyframeable {

    public enum Type { BOX, SPHERE }

    public String name;
    public Type   type;

    // World-space centre
    public float px, py, pz;

    // BOX:    half-extents (sx, sy, sz)
    // SPHERE: sx = radius  (sy, sz ignored during tessellation)
    public float sx = 1f, sy = 1f, sz = 1f;

    // Euler rotation angles in degrees (XYZ order)
    public float rx = 0f, ry = 0f, rz = 0f;

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

    // ── Keyframeable ──────────────────────────────────────────────────────────

    private static final String[] KF_NAMES = {
        "pos.x", "pos.y", "pos.z",
        "scl.x", "scl.y", "scl.z",
        "rot.x", "rot.y", "rot.z"
    };
    private static final String[] KF_DISPLAY = {
        "Position X", "Position Y", "Position Z",
        "Scale X",    "Scale Y",    "Scale Z",
        "Rotation X", "Rotation Y", "Rotation Z"
    };

    @Override public String[] getKeyframeablePropertyNames()        { return KF_NAMES; }
    @Override public String[] getKeyframeablePropertyDisplayNames() { return KF_DISPLAY; }

    @Override
    public float getKeyframeableProperty(String name) {
        return switch (name) {
            case "pos.x" -> px; case "pos.y" -> py; case "pos.z" -> pz;
            case "scl.x" -> sx; case "scl.y" -> sy; case "scl.z" -> sz;
            case "rot.x" -> rx; case "rot.y" -> ry; case "rot.z" -> rz;
            default -> 0f;
        };
    }

    @Override
    public void setKeyframeableProperty(String name, float value) {
        // Note: caller is responsible for marking SceneEditor dirty after mutations.
        switch (name) {
            case "pos.x" -> px = value; case "pos.y" -> py = value; case "pos.z" -> pz = value;
            case "scl.x" -> sx = value; case "scl.y" -> sy = value; case "scl.z" -> sz = value;
            case "rot.x" -> rx = value; case "rot.y" -> ry = value; case "rot.z" -> rz = value;
        }
    }

    /** Instantiates the corresponding immutable {@link Primitive}. */
    public Primitive toPrimitive() {
        Primitive base = switch (type) {
            case BOX    -> new Box(px, py, pz, sx, sy, sz, materialIndex);
            case SPHERE -> new Sphere(px, py, pz, sx, materialIndex, stacks, slices);
        };
        if (rx != 0f || ry != 0f || rz != 0f)
            return new TransformedPrimitive(base, px, py, pz, rx, ry, rz);
        return base;
    }

    // ── Default factories ─────────────────────────────────────────────────────

    public static SceneObject defaultBox(int matIndex) {
        return new SceneObject("New Box", Type.BOX, 0f, 1f, 0f, 1f, 1f, 1f, matIndex);
    }

    public static SceneObject defaultSphere(int matIndex) {
        return new SceneObject("New Sphere", Type.SPHERE, 0f, 1f, 0f, 0.5f, 0.5f, 0.5f, matIndex);
    }
}