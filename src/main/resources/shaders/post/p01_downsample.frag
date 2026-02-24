#version 430 core
// ─────────────────────────────────────────────────────────────────────────────
// p01_downsample.frag
//
// VHS luma horizontal bandwidth / resolution simulation.
//
// PHYSICAL BASIS:
//   VHS SP records ~3 MHz of luma bandwidth vs. the NTSC system bandwidth of
//   ~4.2 MHz.  At 854 px wide, this yields roughly 240 effective horizontal TV
//   lines — less than 1/3 of HD resolution.  The correct way to simulate this
//   is a one-dimensional low-pass filter along the horizontal axis BEFORE
//   sampling at 854 px, which is exactly what this pass does.
//
//   A Gaussian low-pass (rather than a box or sinc) matches the gentle rolloff
//   characteristic of the VHS head/tape transfer function better than an ideal
//   brick-wall filter would.
//
// IMPLEMENTATION:
//   9-tap horizontal Gaussian blur.  Sigma is configurable (default 1.5 px),
//   which at 854 px width gives effective horizontal resolution of ~240 lines.
//   Increasing sigma further softens the image; decreasing it toward 0 turns
//   the effect off.
// ─────────────────────────────────────────────────────────────────────────────

in  vec2 v_uv;
out vec4 fragColor;

uniform sampler2D u_tex;
uniform float     u_lumaBlurSigma; // default 1.5 px

// Evaluate a normalised Gaussian weight for offset x given sigma.
float gauss(float x, float sigma) {
    return exp(-(x * x) / (2.0 * sigma * sigma));
}

void main() {
    vec2 texelSize = 1.0 / vec2(textureSize(u_tex, 0));
    float sigma    = max(u_lumaBlurSigma, 0.01);

    // 9-tap kernel centred at the current pixel (offsets -4 … +4)
    const int HALF = 4;
    vec3  sum    = vec3(0.0);
    float wTotal = 0.0;

    for (int i = -HALF; i <= HALF; i++) {
        float w  = gauss(float(i), sigma);
        vec2  uv = v_uv + vec2(float(i) * texelSize.x, 0.0);
        sum    += texture(u_tex, uv).rgb * w;
        wTotal += w;
    }

    fragColor = vec4(sum / wTotal, 1.0);
}
