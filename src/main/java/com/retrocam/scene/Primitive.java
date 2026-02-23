package com.retrocam.scene;

import java.util.List;

/**
 * Base class for all scene geometry.
 * Subclasses tessellate themselves into {@link GpuTriangle}s for BVH
 * construction and GPU upload. Both {@link Box} and {@link Sphere}
 * implement this contract.
 */
public abstract class Primitive {

    protected int materialIndex;

    protected Primitive(int materialIndex) {
        this.materialIndex = materialIndex;
    }

    /**
     * Returns the set of triangles that approximate this primitive.
     * All normals must be pre-computed (flat or smooth).
     */
    public abstract List<GpuTriangle> toTriangles();

    public int getMaterialIndex() { return materialIndex; }
    public void setMaterialIndex(int idx) { this.materialIndex = idx; }
}