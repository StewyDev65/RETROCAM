package com.retrocam.scene;

import java.util.ArrayList;
import java.util.List;

/**
 * Sphere tessellated into smooth-shaded triangles via UV sphere subdivision.
 * Smooth normals are derived analytically (vertex position − centre, normalised)
 * so the path tracer receives correct shading even with moderate tessellation.
 *
 * Default: 24 stacks × 24 slices → 1152 triangles, sufficient for clean caustics.
 */
public final class Sphere extends Primitive {

    private final float cx, cy, cz;
    private final float radius;
    private final int   stacks;   // latitude subdivisions
    private final int   slices;   // longitude subdivisions

    public Sphere(float cx, float cy, float cz, float radius, int materialIndex) {
        this(cx, cy, cz, radius, materialIndex, 24, 24);
    }

    public Sphere(float cx, float cy, float cz, float radius,
                  int materialIndex, int stacks, int slices) {
        super(materialIndex);
        this.cx = cx; this.cy = cy; this.cz = cz;
        this.radius = radius;
        this.stacks = stacks;
        this.slices = slices;
    }

    @Override
    public List<GpuTriangle> toTriangles() {
        List<GpuTriangle> tris = new ArrayList<>(stacks * slices * 2);

        for (int i = 0; i < stacks; i++) {
            float phi0 = (float)(Math.PI * i       / stacks);
            float phi1 = (float)(Math.PI * (i + 1) / stacks);

            for (int j = 0; j < slices; j++) {
                float theta0 = (float)(2 * Math.PI * j       / slices);
                float theta1 = (float)(2 * Math.PI * (j + 1) / slices);

                // Four corners of the quad on the sphere surface
                float[] A = spherePt(phi0, theta0);
                float[] B = spherePt(phi0, theta1);
                float[] C = spherePt(phi1, theta1);
                float[] D = spherePt(phi1, theta0);

                float[] uvA = {(float)j / slices,       (float)i / stacks};
                float[] uvB = {(float)(j + 1) / slices, (float)i / stacks};
                float[] uvC = {(float)(j + 1) / slices, (float)(i + 1) / stacks};
                float[] uvD = {(float)j / slices,       (float)(i + 1) / stacks};

                // Skip degenerate triangles at poles
                if (i != 0)           tris.add(tri(A, B, C, uvA, uvB, uvC));
                if (i != stacks - 1)  tris.add(tri(A, C, D, uvA, uvC, uvD));
            }
        }
        return tris;
    }

    /** Cartesian point on the unit sphere, scaled and offset. */
    private float[] spherePt(float phi, float theta) {
        float sinP = (float) Math.sin(phi);
        return new float[]{
            cx + radius * sinP * (float) Math.cos(theta),
            cy + radius * (float) Math.cos(phi),
            cz + radius * sinP * (float) Math.sin(theta)
        };
    }

    private GpuTriangle tri(float[] p0, float[] p1, float[] p2,
                             float[] uv0, float[] uv1, float[] uv2) {
        GpuTriangle t = new GpuTriangle();
        t.v0x = p0[0]; t.v0y = p0[1]; t.v0z = p0[2];
        t.v1x = p1[0]; t.v1y = p1[1]; t.v1z = p1[2];
        t.v2x = p2[0]; t.v2y = p2[1]; t.v2z = p2[2];

        // Smooth normal = normalised (vertex - centre)
        setNormal(t, 0, p0);
        setNormal(t, 1, p1);
        setNormal(t, 2, p2);

        t.uv0s = uv0[0]; t.uv0t = uv0[1];
        t.uv1s = uv1[0]; t.uv1t = uv1[1];
        t.uv2s = uv2[0]; t.uv2t = uv2[1];
        t.matIndex = materialIndex;
        return t;
    }

    private void setNormal(GpuTriangle t, int vtx, float[] p) {
        float nx = p[0] - cx, ny = p[1] - cy, nz = p[2] - cz;
        float len = (float) Math.sqrt(nx*nx + ny*ny + nz*nz);
        if (len > 1e-8f) { nx /= len; ny /= len; nz /= len; }
        switch (vtx) {
            case 0 -> { t.n0x = nx; t.n0y = ny; t.n0z = nz; }
            case 1 -> { t.n1x = nx; t.n1y = ny; t.n1z = nz; }
            case 2 -> { t.n2x = nx; t.n2y = ny; t.n2z = nz; }
        }
    }
}