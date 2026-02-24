#version 430 core
// ─────────────────────────────────────────────────────────────────────────────
// p07_ccd_noise.frag
//
// CCD sensor noise simulation.
//
// PHYSICAL BASIS:
//   CCD sensors produce several superimposed noise types:
//
//   1. Temporal shot/read noise — random, frame-to-frame, independent per pixel.
//      Amplitude is INVERSE to luminance: dark regions have more noise (shot
//      noise is Poisson; low signal → high relative variance).
//
//   2. Chroma noise — CCD colour demosaicing and the YC recording chain add
//      more chroma than luma noise.  Visually similar to a film grain that
//      shifts colours.
//
//   3. Fixed-pattern noise (FPN) — from row-level amplifier offsets.  In CCD
//      camcorders this appears as very subtle horizontal banding that changes
//      slowly (amplifier temperature drift).
//
//   4. AGC amplification — in low light the camera boosts all of the above by
//      the AGC gain multiplier (up to 6×), making noise the dominant visual in
//      dark scenes.
//
// IMPLEMENTATION:
//   PCG hash seeded with (pixelX, pixelY, frameIndex) for temporal noise.
//   Fixed-pattern noise seeded with (row, frameIndex/8) — changes every 8
//   frames to simulate slow amplifier drift.
//   Noise is added in YCbCr space so luma and chroma contributions are
//   independent.
// ─────────────────────────────────────────────────────────────────────────────

in  vec2 v_uv;
out vec4 fragColor;

uniform sampler2D u_tex;
uniform int       u_frameIndex;
uniform float     u_agcGain;         // 1.0 – 6.0 from TemporalState
uniform float     u_lumaNoiseBase;   // default 0.015
uniform float     u_chromaNoiseScale;// default 1.6 × luma amplitude

// ── PCG hash ─────────────────────────────────────────────────────────────────

uint pcgHash(uint v) {
    v = v * 747796405u + 2891336453u;
    uint w = ((v >> ((v >> 28u) + 4u)) ^ v) * 277803737u;
    return (w >> 22u) ^ w;
}

// Maps a hash to a float in [0, 1)
float hashToFloat(uint h) {
    return float(h) / 4294967295.0;
}

// Return a uniform random float in [-1, 1) for a given pixel + seed
float rand(ivec2 px, int seed) {
    uint h = pcgHash(
        uint(px.x) ^ (uint(px.y) * 1619u) ^ (uint(seed) * 2654435761u)
    );
    return hashToFloat(h) * 2.0 - 1.0;
}

// ── BT.601 helpers ────────────────────────────────────────────────────────────

vec3 rgbToYCbCr(vec3 c) {
    return vec3(
         0.29900 * c.r + 0.58700 * c.g + 0.11400 * c.b,
        -0.16874 * c.r - 0.33126 * c.g + 0.50000 * c.b + 0.5,
         0.50000 * c.r - 0.41869 * c.g - 0.08131 * c.b + 0.5
    );
}

vec3 yCbCrToRgb(vec3 ycc) {
    float y  = ycc.x;
    float cb = ycc.y - 0.5;
    float cr = ycc.z - 0.5;
    return vec3(
        y                + 1.40200 * cr,
        y - 0.34414 * cb - 0.71414 * cr,
        y + 1.77200 * cb
    );
}

// ─────────────────────────────────────────────────────────────────────────────

void main() {
    ivec2 pix  = ivec2(v_uv * vec2(textureSize(u_tex, 0)));
    vec3  col  = texture(u_tex, v_uv).rgb;
    vec3  ycc  = rgbToYCbCr(col);
    float lum  = clamp(ycc.x, 0.0, 1.0);

    // ── 1. Luma noise (shot noise: stronger in shadows) ──────────────────────
    // amplitude = base + extra * (1 - lum), then scale by AGC gain
    float lumaAmp  = (u_lumaNoiseBase + 0.04 * (1.0 - lum)) * u_agcGain;
    float chromaAmp = lumaAmp * u_chromaNoiseScale;

    float nY  = rand(pix, u_frameIndex)       * lumaAmp;
    float nCb = rand(pix, u_frameIndex + 137) * chromaAmp;
    float nCr = rand(pix, u_frameIndex + 271) * chromaAmp;

    // ── 2. Fixed-pattern / row-level amplifier noise (banding) ───────────────
    // Only varies per row and changes slowly (every 8 frames).
    // Uses a different seed dimension (x = 0 collapses horizontal variation so
    // all pixels in a row get the same base offset).
    int rowSeed = pix.y * 7 + (u_frameIndex / 8);
    float band  = rand(ivec2(0, rowSeed), 0) * 0.005 * u_agcGain;

    // ── Apply noise in YCbCr space ────────────────────────────────────────────
    ycc.x += nY + band;
    ycc.y += nCb;
    ycc.z += nCr;

    // Allow slight over/undershoot — subsequent passes will handle clamp
    fragColor = vec4(yCbCrToRgb(ycc), 1.0);
}
