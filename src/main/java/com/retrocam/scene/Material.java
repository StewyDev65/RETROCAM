package com.retrocam.scene;

/**
 * CPU-side material matching the GPU struct:
 *
 *   struct Material {
 *     vec3  albedo;        float metallic;
 *     vec3  emission;      float emissionStrength;
 *     float roughness;     float ior;  float transmission;  int albedoTex;
 *     float pad[2];
 *   };  // 48 bytes, std430
 */
public final class Material {

    public float[] albedo           = {0.8f, 0.8f, 0.8f};
    public float   metallic         = 0.0f;
    public float[] emission         = {0.0f, 0.0f, 0.0f};
    public float   emissionStrength = 0.0f;
    public float   roughness        = 0.5f;
    public float   ior              = 1.5f;
    public float   transmission     = 0.0f;
    public int     albedoTex        = -1;   // -1 = no texture

    // ── Factory helpers ──────────────────────────────────────────────────────

    public static Material diffuse(float r, float g, float b) {
        Material m = new Material();
        m.albedo = new float[]{r, g, b};
        return m;
    }

    public static Material emissive(float r, float g, float b, float strength) {
        Material m = new Material();
        m.emission         = new float[]{r, g, b};
        m.emissionStrength = strength;
        m.albedo           = new float[]{r, g, b};
        return m;
    }

    public static Material metal(float r, float g, float b, float roughness) {
        Material m = new Material();
        m.albedo    = new float[]{r, g, b};
        m.metallic  = 1.0f;
        m.roughness = roughness;
        return m;
    }

    public static Material glass(float ior) {
        Material m = new Material();
        m.transmission = 1.0f;
        m.roughness    = 0.0f;
        m.ior          = ior;
        m.albedo       = new float[]{1f, 1f, 1f};
        return m;
    }

    /**
     * Writes this material into {@code buf} at the current position.
     * Advances position by {@link #FLOATS_PER_MATERIAL} floats.
     */
    public void write(java.nio.FloatBuffer buf) {
        buf.put(albedo[0]).put(albedo[1]).put(albedo[2]).put(metallic);
        buf.put(emission[0]).put(emission[1]).put(emission[2]).put(emissionStrength);
        buf.put(roughness).put(ior).put(transmission).put(Float.intBitsToFloat(albedoTex));
        buf.put(0f).put(0f).put(0f).put(0f); // pad to 16 floats (64 bytes, aligned)
    }

    /** Number of floats per material in the SSBO. */
    public static final int FLOATS_PER_MATERIAL = 16;
}