package com.retrocam.scene;

import java.util.ArrayList;
import java.util.List;

/**
 * Wraps any {@link Primitive} and applies an XYZ Euler rotation (degrees)
 * around the supplied world-space centre before returning triangles.
 *
 * <p>Vertices are translated to origin, rotated via Rx·Ry·Rz, then translated
 * back.  Normals receive only the rotation (no translation).  All arithmetic
 * is done at build-time on the CPU so there is zero per-frame GPU cost.
 */
public final class TransformedPrimitive extends Primitive {

    private final Primitive base;
    private final float     cx, cy, cz;      // rotation pivot (shape centre)
    private final float[]   rot;             // pre-built 3×3 column-major rotation matrix

    public TransformedPrimitive(Primitive base,
                                float cx, float cy, float cz,
                                float degX, float degY, float degZ) {
        super(base.getMaterialIndex());
        this.base = base;
        this.cx   = cx;
        this.cy   = cy;
        this.cz   = cz;
        this.rot  = buildRotation(degX, degY, degZ);
    }

    @Override
    public List<GpuTriangle> toTriangles() {
        List<GpuTriangle> src = base.toTriangles();
        List<GpuTriangle> out = new ArrayList<>(src.size());
        for (GpuTriangle t : src) {
            GpuTriangle r = new GpuTriangle();

            // Positions (rotate around centre)
            float[] v0 = rotVec(t.v0x - cx, t.v0y - cy, t.v0z - cz);
            float[] v1 = rotVec(t.v1x - cx, t.v1y - cy, t.v1z - cz);
            float[] v2 = rotVec(t.v2x - cx, t.v2y - cy, t.v2z - cz);
            r.v0x = v0[0] + cx; r.v0y = v0[1] + cy; r.v0z = v0[2] + cz;
            r.v1x = v1[0] + cx; r.v1y = v1[1] + cy; r.v1z = v1[2] + cz;
            r.v2x = v2[0] + cx; r.v2y = v2[1] + cy; r.v2z = v2[2] + cz;

            // Normals (rotation only, no translation)
            float[] n0 = rotVec(t.n0x, t.n0y, t.n0z);
            float[] n1 = rotVec(t.n1x, t.n1y, t.n1z);
            float[] n2 = rotVec(t.n2x, t.n2y, t.n2z);
            r.n0x = n0[0]; r.n0y = n0[1]; r.n0z = n0[2];
            r.n1x = n1[0]; r.n1y = n1[1]; r.n1z = n1[2];
            r.n2x = n2[0]; r.n2y = n2[1]; r.n2z = n2[2];

            // UVs and material pass through unchanged
            r.uv0s = t.uv0s; r.uv0t = t.uv0t;
            r.uv1s = t.uv1s; r.uv1t = t.uv1t;
            r.uv2s = t.uv2s; r.uv2t = t.uv2t;
            r.matIndex = t.matIndex;

            out.add(r);
        }
        return out;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Multiplies a 3-vector by the 3×3 rotation matrix. */
    private float[] rotVec(float x, float y, float z) {
        return new float[]{
            rot[0]*x + rot[3]*y + rot[6]*z,
            rot[1]*x + rot[4]*y + rot[7]*z,
            rot[2]*x + rot[5]*y + rot[8]*z
        };
    }

    /**
     * Builds a 3×3 column-major rotation matrix from XYZ Euler angles (degrees).
     * Composition order: R = Rz · Ry · Rx  (applied X first, then Y, then Z).
     */
    private static float[] buildRotation(float degX, float degY, float degZ) {
        double rx = Math.toRadians(degX);
        double ry = Math.toRadians(degY);
        double rz = Math.toRadians(degZ);

        float cx = (float) Math.cos(rx), sx = (float) Math.sin(rx);
        float cy = (float) Math.cos(ry), sy = (float) Math.sin(ry);
        float cz = (float) Math.cos(rz), sz = (float) Math.sin(rz);

        // Rz · Ry · Rx  (column-major, 3×3)
        return new float[]{
             cy*cz,              cy*sz,             -sy,
             sx*sy*cz - cx*sz,   sx*sy*sz + cx*cz,  sx*cy,
             cx*sy*cz + sx*sz,   cx*sy*sz - sx*cz,  cx*cy
        };
    }
}
