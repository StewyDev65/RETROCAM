package com.retrocam.scene;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Mutable scene graph used by the real-time editor.
 * Tracks a dirty flag; the main loop re-builds and re-uploads the GPU scene
 * whenever {@link #isDirty()} returns {@code true}.
 *
 * <p>All mutation methods automatically set the dirty flag.  Direct list
 * access (for ImGui iteration) is provided via read-only views; targeted
 * setters are used for all writes to preserve dirty tracking.
 */
public final class SceneEditor {

    private final List<SceneObject> objects   = new ArrayList<>();
    private final List<Material>    materials = new ArrayList<>();
    private final List<String>      matNames  = new ArrayList<>();

    private boolean dirty = true;

    // ── Dirty tracking ────────────────────────────────────────────────────────

    public boolean isDirty()    { return dirty; }
    public void    clearDirty() { dirty = false; }
    public void    markDirty()  { dirty = true; }

    // ── Read-only views ───────────────────────────────────────────────────────

    public List<SceneObject> getObjects()   { return Collections.unmodifiableList(objects);   }
    public List<Material>    getMaterials() { return Collections.unmodifiableList(materials); }
    public List<String>      getMatNames()  { return Collections.unmodifiableList(matNames);  }

    public int objectCount()   { return objects.size();   }
    public int materialCount() { return materials.size(); }

    // ── Material mutation ─────────────────────────────────────────────────────

    public int addMaterial(String name, Material m) {
        materials.add(m);
        matNames.add(name);
        dirty = true;
        return materials.size() - 1;
    }

    public void setMatName(int idx, String name) {
        if (idx >= 0 && idx < matNames.size()) matNames.set(idx, name);
    }

    /** Returns the material for direct in-place editing by the UI. Caller must call {@link #markDirty()} after edits. */
    public Material getMaterial(int idx) {
        return (idx >= 0 && idx < materials.size()) ? materials.get(idx) : null;
    }

    public void removeMaterial(int idx) {
        if (idx < 0 || idx >= materials.size()) return;
        materials.remove(idx);
        matNames.remove(idx);
        // Remap references in all objects to avoid out-of-bounds indices
        for (SceneObject o : objects)
            if (o.materialIndex >= idx)
                o.materialIndex = Math.max(0, o.materialIndex - 1);
        dirty = true;
    }

    // ── Object mutation ───────────────────────────────────────────────────────

    public void addObject(SceneObject obj) {
        objects.add(obj);
        dirty = true;
    }

    /** Returns the object for direct in-place editing by the UI. Caller must call {@link #markDirty()} after edits. */
    public SceneObject getObject(int idx) {
        return (idx >= 0 && idx < objects.size()) ? objects.get(idx) : null;
    }

    public void removeObject(int idx) {
        if (idx >= 0 && idx < objects.size()) {
            objects.remove(idx);
            dirty = true;
        }
    }

    /** Clears all objects and materials without triggering individual dirty callbacks. */
    public void clearAll() {
        objects.clear();
        materials.clear();
        matNames.clear();
        dirty = true;
    }

    // ── Scene build ───────────────────────────────────────────────────────────

    /**
     * Compiles the current mutable state into an immutable {@link Scene} ready
     * for BVH construction and GPU upload.
     */
    public Scene buildScene() {
        Scene scene = new Scene();
        for (Material m : materials) scene.addMaterial(m);
        for (SceneObject o : objects) scene.addPrimitive(o.toPrimitive());
        scene.build();
        return scene;
    }

    // ── Default Cornell box ────────────────────────────────────────────────────

    public static SceneEditor createDefault() {
        SceneEditor e = new SceneEditor();

        int white = e.addMaterial("White", Material.diffuse(0.73f, 0.73f, 0.73f));
        int red   = e.addMaterial("Red",   Material.diffuse(0.65f, 0.05f, 0.05f));
        int green = e.addMaterial("Green", Material.diffuse(0.12f, 0.45f, 0.15f));
        int light = e.addMaterial("Light", Material.emissive(1.0f, 0.95f, 0.85f, 15.0f));
        int metal = e.addMaterial("Metal", Material.metal(0.95f, 0.85f, 0.55f, 0.1f));
        int glass = e.addMaterial("Glass", Material.glass(1.5f));

        float H = 5f;
        e.addObject(new SceneObject("Floor",        SceneObject.Type.BOX,    0f,          0f,     0f,   H,     0.05f, H,     white));
        e.addObject(new SceneObject("Ceiling",      SceneObject.Type.BOX,    0f,       2*H,       0f,   H,     0.05f, H,     white));
        e.addObject(new SceneObject("Left Wall",    SceneObject.Type.BOX,   -H,          H,       0f,   0.05f, H,     H,     red));
        e.addObject(new SceneObject("Right Wall",   SceneObject.Type.BOX,    H,          H,       0f,   0.05f, H,     H,     green));
        e.addObject(new SceneObject("Back Wall",    SceneObject.Type.BOX,    0f,          H,     -H,   H,     H,     0.05f, white));
        e.addObject(new SceneObject("Area Light",   SceneObject.Type.BOX,    0f,       2*H-0.1f,  0f,   1.5f,  0.05f, 1.5f,  light));
        e.addObject(new SceneObject("Tall Block",   SceneObject.Type.BOX,   -1.5f,       1.5f,  -1.0f, 1.0f,  1.5f,  1.0f,  white));
        e.addObject(new SceneObject("Metal Block",  SceneObject.Type.BOX,    1.5f,       1.0f,   0.5f, 1.0f,  1.0f,  1.0f,  metal));
        e.addObject(new SceneObject("Glass Sphere", SceneObject.Type.SPHERE, 0f,         1.2f,   1.5f, 1.2f,  0f,    0f,    glass));

        e.dirty = true;
        return e;
    }
}