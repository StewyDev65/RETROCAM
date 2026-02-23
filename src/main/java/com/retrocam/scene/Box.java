package com.retrocam.scene;

import java.util.ArrayList;
import java.util.List;

/**
 * Axis-aligned rectangular prism defined by a centre point and half-extents.
 * Tessellates into 12 triangles (2 per face × 6 faces) with per-face flat
 * normals and UV-mapped 0-1 per face.
 */
public final class Box extends Primitive {

    private final float cx, cy, cz;   // centre
    private final float hx, hy, hz;   // half-extents

    public Box(float cx, float cy, float cz,
               float hx, float hy, float hz,
               int materialIndex) {
        super(materialIndex);
        this.cx = cx; this.cy = cy; this.cz = cz;
        this.hx = hx; this.hy = hy; this.hz = hz;
    }

    @Override
    public List<GpuTriangle> toTriangles() {
        List<GpuTriangle> tris = new ArrayList<>(12);

        float x0 = cx - hx, x1 = cx + hx;
        float y0 = cy - hy, y1 = cy + hy;
        float z0 = cz - hz, z1 = cz + hz;

        // +X face
        addQuad(tris, x1,y0,z1, x1,y1,z1, x1,y1,z0, x1,y0,z0);
        // -X face
        addQuad(tris, x0,y0,z0, x0,y1,z0, x0,y1,z1, x0,y0,z1);
        // +Y face
        addQuad(tris, x0,y1,z1, x0,y1,z0, x1,y1,z0, x1,y1,z1);
        // -Y face
        addQuad(tris, x0,y0,z0, x0,y0,z1, x1,y0,z1, x1,y0,z0);
        // +Z face
        addQuad(tris, x0,y0,z1, x0,y1,z1, x1,y1,z1, x1,y0,z1);
        // -Z face
        addQuad(tris, x1,y0,z0, x1,y1,z0, x0,y1,z0, x0,y0,z0);

        return tris;
    }

    /** Two triangles from four coplanar CCW vertices with auto flat normal. */
    private void addQuad(List<GpuTriangle> out,
                         float ax, float ay, float az,
                         float bx, float by, float bz,
                         float cx, float cy, float cz,
                         float dx, float dy, float dz) {
        GpuTriangle t1 = new GpuTriangle();
        t1.v0x = ax; t1.v0y = ay; t1.v0z = az;
        t1.v1x = bx; t1.v1y = by; t1.v1z = bz;
        t1.v2x = cx; t1.v2y = cy; t1.v2z = cz;
        t1.uv0s = 0; t1.uv0t = 0;
        t1.uv1s = 0; t1.uv1t = 1;
        t1.uv2s = 1; t1.uv2t = 1;
        t1.matIndex = materialIndex;
        t1.withFlatNormal();

        GpuTriangle t2 = new GpuTriangle();
        t2.v0x = ax; t2.v0y = ay; t2.v0z = az;
        t2.v1x = cx; t2.v1y = cy; t2.v1z = cz;
        t2.v2x = dx; t2.v2y = dy; t2.v2z = dz;
        t2.uv0s = 0; t2.uv0t = 0;
        t2.uv1s = 1; t2.uv1t = 1;
        t2.uv2s = 1; t2.uv2t = 0;
        t2.matIndex = materialIndex;
        t2.withFlatNormal();

        out.add(t1);
        out.add(t2);
    }
}