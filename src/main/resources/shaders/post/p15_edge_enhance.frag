#version 430 core
// ─────────────────────────────────────────────────────────────────────────────
// p15_edge_enhance.frag  —  Camcorder aperture correction / edge sharpening
//
// PHYSICAL BASIS:
//   Consumer camcorders included a dedicated "aperture correction" IC in the
//   analog signal chain between the CCD and the tape transport. This circuit
//   operated EXCLUSIVELY on the Y (luminance) channel — chroma was handled by
//   a completely separate path and was never sharpened. Sharpening composite
//   chroma would amplify dot-crawl and chroma noise, which real camcorders
//   specifically avoided.
//
//   The circuit computed the Laplacian (highpass) of Y by subtracting a
//   lowpass-filtered Y from the original:  detail = Y - LPF(Y)
//   It then amplified and added this back:  Y_out = Y + GAIN * detail
//
//   The LPF kernel corresponded to ~1–1.5 horizontal pixels at SD resolution
//   (the horizontal aperture period of the CCD readout clock), so halos are
//   roughly 1–2 px wide — tight and "crackling" rather than photographic.
//
//   CORING:
//   Every camcorder sharpening circuit included a coring threshold — small
//   detail signals below the sensor noise floor were suppressed to zero so
//   the enhancement would not amplify CCD grain. Above the threshold the
//   gain ramps in smoothly. This is why camcorder sharpening looks harsh on
//   strong edges but doesn't amplify uniform-area noise into a sparkling mess.
//
//   OUTPUT NOT CLAMPED:
//   The sharpened signal lived in the analog domain before the tape — halos
//   that overshot above reference white or undershot below black existed as
//   voltage excursions. They were clipped only at the display. We preserve
//   this: output is NOT clamped so subsequent passes can see the ringing.
//
//   GAIN RANGE:
//   Consumer camcorders used GAIN ≈ 1.2–2.5 — far stronger than broadcast
//   (~0.4) or photographic (~0.6) unsharp masking. This is what creates the
//   characteristic "outlined" look: every edge has a bright halo on the bright
//   side and a dark halo on the dark side, making the image look almost like
//   a cartoon.
// ─────────────────────────────────────────────────────────────────────────────

in  vec2 v_uv;
out vec4 fragColor;

uniform sampler2D u_tex;

// Enhancement gain multiplier.
// 1.0 = identity (no effect), 1.5 = spec default, 3.0 = extreme 90s look.
uniform float u_amount;         // [0.5, 3.5], default 1.5

// Coring threshold.
// Detail signals |detail| < threshold are suppressed to avoid amplifying noise.
// 0.0 = no coring (sharpen everything, including noise).
// 0.025 = realistic consumer camcorder default.
uniform float u_coreThreshold;  // [0.0, 0.10], default 0.025

// ── BT.601 helpers ─────────────────────────────────────────────────────────────

vec3 rgbToYCbCr(vec3 c) {
    return vec3(
         0.29900*c.r + 0.58700*c.g + 0.11400*c.b,
        -0.16874*c.r - 0.33126*c.g + 0.50000*c.b + 0.5,
         0.50000*c.r - 0.41869*c.g - 0.08131*c.b + 0.5
    );
}

vec3 yCbCrToRgb(vec3 ycc) {
    float y  = ycc.x;
    float cb = ycc.y - 0.5;
    float cr = ycc.z - 0.5;
    return vec3(
        y               + 1.40200*cr,
        y - 0.34414*cb  - 0.71414*cr,
        y + 1.77200*cb
    );
}

// ─────────────────────────────────────────────────────────────────────────────

void main() {
    vec2 texSize = vec2(textureSize(u_tex, 0));
    vec2 px      = 1.0 / texSize;

    vec3  col = texture(u_tex, v_uv).rgb;
    vec3  ycc = rgbToYCbCr(col);
    float Y   = ycc.x;

    // ── 5×5 Gaussian blur of Y channel only ──────────────────────────────────
    // Kernel: separable binomial approximation of sigma ≈ 1.0 px.
    // Row/column weights: [1, 4, 6, 4, 1] / 16
    // This blurs at the scale of the CCD aperture period (~1–1.5 px at SD res).
    // Only Y is sampled and blurred; Cb/Cr channels are never touched.
    const float w[5] = float[5](
        0.0625, 0.25, 0.375, 0.25, 0.0625   // [1,4,6,4,1]/16
    );

    float Yblur = 0.0;
    for (int dy = -2; dy <= 2; dy++) {
        for (int dx = -2; dx <= 2; dx++) {
            vec2  sUv  = v_uv + vec2(float(dx), float(dy)) * px;
            float sY   = rgbToYCbCr(texture(u_tex, sUv).rgb).x;
            Yblur     += sY * w[dx + 2] * w[dy + 2];
        }
    }

    // ── Detail signal (luma Laplacian) ────────────────────────────────────────
    float detail = Y - Yblur;

    // ── Coring: suppress sub-threshold detail to protect against noise ────────
    // Soft threshold ramps from 0 at half-threshold to 1 at full threshold.
    // This mirrors the analog coring clipper in real aperture-correction ICs.
    float coreWeight = smoothstep(u_coreThreshold * 0.5, u_coreThreshold, abs(detail));
    detail *= coreWeight;

    // ── Apply gain to Y only — chroma channels are left completely untouched ──
    // Y_out = Y + GAIN * (Y - LPF(Y))
    // Do NOT clamp: overshoot/undershoot must propagate to subsequent passes.
    ycc.x = Y + u_amount * detail;

    fragColor = vec4(yCbCrToRgb(ycc), 1.0);
}
