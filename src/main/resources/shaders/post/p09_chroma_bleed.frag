#version 430 core
// ─────────────────────────────────────────────────────────────────────────────
// p09_chroma_bleed.frag
//
// Horizontal chroma bleed / colour smear.
//
// PHYSICAL BASIS:
//   In composite video the chroma sub-carrier bleeds into the luma signal and
//   vice versa.  At sharp horizontal colour transitions the chroma smears
//   RIGHTWARD — the colour of a region continues past the actual geometry edge
//   for several pixels to the right.  This is caused by the bandlimited nature
//   of the composite demodulator — a causal first-order IIR (leaky integrator)
//   tracks the chroma amplitude, so it takes several samples to settle.
//
//   The smear is strictly rightward and asymmetric: there is no leftward bleed.
//   It is distinct from p02 chroma_res (which was a uniform bilateral blur);
//   this pass smears specifically at transitions.
//
// IMPLEMENTATION:
//   A causal first-order IIR of the form:
//     out[x] = (1-k) * in[x] + k * out[x-1]
//   is equivalent to a convolution with a one-sided exponential kernel:
//     out[x] = sum_{i=0}^{∞} in[x-i] * (1-k) * k^i
//
//   Since we cannot process pixels sequentially in a fragment shader we
//   approximate this with a fixed 24-tap geometric series convolution.
//   For k=0.35: k^24 ≈ 5e-5, so the error from truncation is negligible.
//
//   Sampling pixels to the LEFT of the current position (x - i) recreates
//   the rightward smear: the colour from position x-i contributes to x with
//   exponentially decaying weight.
//
//   Only Cb and Cr are smeared; Y is taken from the unmodified centre pixel.
// ─────────────────────────────────────────────────────────────────────────────

in  vec2 v_uv;
out vec4 fragColor;

uniform sampler2D u_tex;
uniform float     u_bleedFactor; // k — decay per pixel, default 0.35

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
    vec2 texelSize = 1.0 / vec2(textureSize(u_tex, 0));
    float k = clamp(u_bleedFactor, 0.0, 0.98);

    // Luma: take the unsmeared centre value
    float lumaY = rgbToYCbCr(texture(u_tex, v_uv).rgb).x;

    // Approximate causal IIR with 24-tap geometric series.
    // w_i = (1-k) * k^i  →  sum_0^∞ w_i = 1
    const int TAPS = 24;
    vec2  chromaSum = vec2(0.0);
    float wTotal    = 0.0;
    float kpow      = 1.0;           // k^i, starts at k^0 = 1
    float scale     = 1.0 - k;      // (1-k) normalisation factor

    for (int i = 0; i < TAPS; i++) {
        // Sample i pixels to the LEFT → that pixel's chroma smears rightward to us
        vec2 sampleUv = vec2(v_uv.x - float(i) * texelSize.x, v_uv.y);
        vec3 ycc      = rgbToYCbCr(texture(u_tex, sampleUv).rgb);
        float w       = scale * kpow;
        chromaSum    += vec2(ycc.y, ycc.z) * w;
        wTotal       += w;
        kpow         *= k;
    }

    // Normalise to account for truncation of the infinite series
    chromaSum /= wTotal;

    fragColor = vec4(yCbCrToRgb(vec3(lumaY, chromaSum)), 1.0);
}
