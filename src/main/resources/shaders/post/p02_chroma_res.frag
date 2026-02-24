#version 430 core
// ─────────────────────────────────────────────────────────────────────────────
// p02_chroma_res.frag
//
// VHS chroma resolution loss — one of the most distinctive aspects of the look.
//
// PHYSICAL BASIS:
//   VHS records chroma using a down-converted sub-carrier (~629 kHz in HiFi
//   VHS SP) while luma gets the full ~3 MHz.  Chroma horizontal resolution is
//   therefore only ~40 TV lines vs. ~240 for luma — a 6:1 ratio.  Colors bleed
//   and smear horizontally in a way completely decoupled from luminance edges.
//
// IMPLEMENTATION:
//   Convert RGB → YCbCr (BT.601).
//   Apply a wide horizontal Gaussian blur (sigma ~7 px at 854 px) to ONLY the
//   Cb and Cr channels.  Luma Y is left untouched.
//   Convert back to RGB.
//
//   The radius is capped at 32 pixels each side (65 total samples) to keep the
//   shader within instruction budget for any sigma value the UI allows.
// ─────────────────────────────────────────────────────────────────────────────

in  vec2 v_uv;
out vec4 fragColor;

uniform sampler2D u_tex;
uniform float     u_chromaSigma; // default 7.0 px

// ── BT.601 colour space helpers ───────────────────────────────────────────────

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
        y                   + 1.40200 * cr,
        y - 0.34414 * cb    - 0.71414 * cr,
        y + 1.77200 * cb
    );
}

// ── Gaussian helper ───────────────────────────────────────────────────────────

float gauss(float x, float sigma) {
    return exp(-(x * x) / (2.0 * sigma * sigma));
}

// ─────────────────────────────────────────────────────────────────────────────

void main() {
    vec2 texelSize = 1.0 / vec2(textureSize(u_tex, 0));
    float sigma    = max(u_chromaSigma, 0.5);

    // Dynamic radius: cover ~2.5 sigma each side, clamp to budget
    int radius = clamp(int(sigma * 2.5 + 0.5), 1, 32);

    // Luma from the centre pixel (no horizontal blur on luma)
    float lumaY = rgbToYCbCr(texture(u_tex, v_uv).rgb).x;

    // Horizontally blur chroma channels
    vec2  chromaSum = vec2(0.0);
    float wTotal    = 0.0;

    for (int i = -radius; i <= radius; i++) {
        float w    = gauss(float(i), sigma);
        vec3  ycc  = rgbToYCbCr(texture(u_tex, v_uv + vec2(float(i) * texelSize.x, 0.0)).rgb);
        chromaSum += vec2(ycc.y, ycc.z) * w;
        wTotal    += w;
    }
    chromaSum /= wTotal;

    // Reconstruct with blurred chroma + sharp luma
    vec3 rgb = yCbCrToRgb(vec3(lumaY, chromaSum));

    // Allow slight out-of-range values to pass through for subsequent passes
    fragColor = vec4(rgb, 1.0);
}
