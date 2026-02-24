#version 430 core
out vec4 fragColor;
in  vec2 v_uv;

uniform sampler2D u_tex;

// Color temperature bias: negative = cool (blue), positive = warm (amber/orange).
// VHS tape aging causes a characteristic warm shift as the ferric oxide layer
// degrades; fresh tapes are slightly cool from the magnetic coating.
uniform float u_colorTempBias;   // [-1, +1], default 0.0

// Chroma hue rotation in degrees.
// VHS I/Q demodulation relies on a 3.58 MHz colour burst reference; playback
// circuitry phase errors rotate the entire Cb/Cr plane.  Typical drift: ±5°.
uniform float u_colorHueRot;     // [-30, +30] degrees, default 0.0

// Saturation multiplier.
// Standard VHS SP records chroma at ~40 TV lines vs luma ~240 TV lines; the
// re-interpolation on playback softens chroma, effectively desaturating ~5%.
uniform float u_colorSaturation; // [0, 2], default 0.95

// Temporal drift speed (rad/s).
// Playback electronics warm up over the first ~2 min of operation, causing a
// slow sinusoidal wander in colour temperature (≈ ±25 K excursion).
uniform float u_colorDriftSpeed; // [0, 0.5], default 0.05

// Playback time in seconds (from TemporalState.time).
uniform float u_time;

// ── BT.601 colour space conversions ──────────────────────────────────────────
//
//   Y  =  0.299 R + 0.587 G + 0.114 B
//   Cb = -0.169 R - 0.331 G + 0.500 B + 0.5
//   Cr =  0.500 R - 0.419 G - 0.081 B + 0.5
//
// The +0.5 offset keeps Cb/Cr in [0,1] (unsigned storage convention).
// -----------------------------------------------------------------------------
vec3 rgbToYCbCr(vec3 rgb) {
    float Y  =  0.299*rgb.r + 0.587*rgb.g + 0.114*rgb.b;
    float Cb = -0.169*rgb.r - 0.331*rgb.g + 0.500*rgb.b + 0.5;
    float Cr =  0.500*rgb.r - 0.419*rgb.g - 0.081*rgb.b + 0.5;
    return vec3(Y, Cb, Cr);
}

vec3 yCbCrToRgb(vec3 ycc) {
    float Y  = ycc.x;
    float Cb = ycc.y - 0.5;
    float Cr = ycc.z - 0.5;
    return vec3(
        Y               + 1.402*Cr,
        Y - 0.344*Cb   - 0.714*Cr,
        Y + 1.772*Cb
    );
}

void main() {
    vec3 rgb = texture(u_tex, v_uv).rgb;
    vec3 ycc = rgbToYCbCr(rgb);

    // Centre chroma around (0,0) for manipulation
    vec2 chroma = ycc.yz - 0.5;

    // ── 1. Saturation ─────────────────────────────────────────────────────────
    chroma *= u_colorSaturation;

    // ── 2. Hue rotation ──────────────────────────────────────────────────────
    // Pure rotation of the Cb/Cr plane by hueRot degrees.
    float rad = radians(u_colorHueRot);
    float cosH = cos(rad);
    float sinH = sin(rad);
    chroma = vec2(cosH * chroma.x - sinH * chroma.y,
                  sinH * chroma.x + cosH * chroma.y);

    // ── 3. Colour temperature bias + slow temporal drift ─────────────────────
    // Moving along the Planckian locus from daylight (6500 K) toward tungsten
    // (3200 K) primarily reduces Cb (less blue-difference) and increases Cr
    // (more red-difference).  The ratio 4:3 (Cb:Cr) approximates the slope of
    // the locus in BT.601 chroma space across that range.
    //
    // Temporal drift: ±0.015 Cr sinusoidal oscillation at u_colorDriftSpeed rad/s
    // models slow cap/PSU temperature rise in VHS deck playback circuitry.
    float drift      = sin(u_time * u_colorDriftSpeed) * 0.015;
    float tempShift  = u_colorTempBias * 0.05 + drift;
    chroma.y += tempShift;           // Cr  — warm pushes red up
    chroma.x -= tempShift * 0.75;   // Cb  — warm pulls blue down (3:4 ratio)

    // Restore offset and reconstruct
    ycc.yz = chroma + 0.5;
    fragColor = vec4(clamp(yCbCrToRgb(ycc), 0.0, 1.0), 1.0);
}
