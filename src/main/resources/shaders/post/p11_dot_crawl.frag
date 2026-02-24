#version 430 core
// ─────────────────────────────────────────────────────────────────────────────
// p11_dot_crawl.frag
//
// NTSC composite dot crawl — chroma subcarrier bleed into luma at sharp edges.
//
// PHYSICAL BASIS:
//   NTSC composite encodes color as a 3.579545 MHz subcarrier (I/Q modulated)
//   summed with the luminance signal on the same wire.  VHS consumer decks
//   separate them with a simple notch filter centered at 3.58 MHz rather than
//   a proper 1H comb filter.  The notch is only ~6 dB deep in the transition
//   band, so at high-contrast horizontal luminance edges — where the luma
//   signal has significant energy near the subcarrier frequency — the chroma
//   decoder misinterprets luma energy as chroma, producing visible colored
//   fringes that oscillate at the subcarrier rate.
//
//   The NTSC subcarrier is designed with 180° phase inversion between adjacent
//   scanlines (subcarrier offset = 1/2 cycle per line).  This means the dots
//   on line N and line N+1 are complementary colors.  Each frame the pattern
//   also shifts: because 525 lines × 1/2 cycle = 262.5 cycles, the subcarrier
//   arrives at field 2 with a 180° phase shift vs field 1, causing the pattern
//   to invert every field and appear to "crawl" upward or downward.
//
//   VHS's reduced chroma bandwidth (≈630 kHz downconverted subcarrier) means
//   the visible dot pattern frequency is lower than broadcast NTSC — roughly
//   one dot per 6–10 pixels at simulated 854-wide VHS resolution.
//
// IMPLEMENTATION:
//   1. Compute horizontal luma gradient to detect edges.
//   2. At each edge pixel, inject I/Q chroma contamination modulated by the
//      subcarrier sinusoid: sin(x·kX + line·π + frame·kF)
//   3. The I/Q contamination is converted to RGB using the standard NTSC
//      I/Q → RGB matrix, producing the characteristic yellow/cyan fringe.
//   4. Edge strength gates the effect (no dot crawl in flat regions).
// ─────────────────────────────────────────────────────────────────────────────

in  vec2 v_uv;
out vec4 fragColor;

uniform sampler2D u_tex;
uniform float     u_intensity;      // overall dot-crawl amplitude  [0, 1], default 0.25
uniform float     u_subcarrierFreq; // normalized subcarrier freq (cycles/px), default 0.14
uniform float     u_edgeThresh;     // luma gradient threshold to trigger effect, default 0.04
uniform int       u_frameIndex;     // for per-frame phase advance (the "crawl")

// ── NTSC I/Q to RGB (standard D65 whitepoint, broadcast primaries) ────────────
// I-axis: orange–cyan    Q-axis: green–magenta
vec3 iqToRgb(float I, float Q) {
    // From ITU-R BT.601 / NTSC I-Q colour space
    return vec3(
         1.0000 * I + 0.9562 * Q,   // R component from I,Q
        -0.2721 * I - 0.6474 * Q,   // G component (negative, so edges are complementary)
        -1.1069 * I + 1.7046 * Q    // B component
    );
}

// ── Luma from linear RGB ───────────────────────────────────────────────────────
float luma(vec3 c) {
    return dot(c, vec3(0.2126, 0.7152, 0.0722));
}

void main() {
    vec2  res      = vec2(textureSize(u_tex, 0));
    vec3  col      = texture(u_tex, v_uv).rgb;
    float pixX     = v_uv.x * res.x;        // fractional pixel x
    float lineY    = floor(v_uv.y * res.y); // integer scanline index

    // ── Edge detection: horizontal luma gradient ──────────────────────────────
    // Sample left and right neighbours (1-pixel gap for sensitivity to
    // single-pixel transitions typical of VHS chroma smear edges).
    float pxW  = 1.0 / res.x;
    float lumC = luma(col);
    float lumL = luma(texture(u_tex, vec2(v_uv.x - pxW, v_uv.y)).rgb);
    float lumR = luma(texture(u_tex, vec2(v_uv.x + pxW, v_uv.y)).rgb);

    // Gradient magnitude — use forward and backward diffs and take the max
    float grad = max(abs(lumC - lumL), abs(lumR - lumC));

    // Soft edge gate: smoothly clamp so small gradients are suppressed,
    // strong gradients are fully active.
    float edgeGate = smoothstep(u_edgeThresh, u_edgeThresh * 3.0, grad);

    // ── Subcarrier phase ──────────────────────────────────────────────────────
    // Horizontal phase: u_subcarrierFreq cycles per pixel (2π scaling)
    // Line phase: π per scanline (NTSC 180° per-line inversion)
    // Frame phase: advances each frame producing the "crawl" motion.
    //   Real crawl rate ≈ 1 line per field = 60 lines/s upward drift.
    //   We simulate with a small per-frame offset (0.5π per frame ≈ 1 line shift).
    float linePhase  = lineY * 3.14159265;
    float framePhase = float(u_frameIndex) * (3.14159265 * 0.5);
    float hPhase     = pixX * u_subcarrierFreq * 2.0 * 3.14159265;

    float subcarrier = sin(hPhase + linePhase + framePhase);

    // ── I/Q contamination ────────────────────────────────────────────────────
    // In-phase (I) and quadrature (Q) are 90° apart.
    // The luma→chroma crosstalk affects primarily the I channel
    // (I has wider bandwidth than Q in NTSC).
    float I = subcarrier * u_intensity * edgeGate;
    float Q = cos(hPhase + linePhase + framePhase) * u_intensity * edgeGate * 0.5;

    vec3 contamination = iqToRgb(I, Q);

    // ── Composite and clamp ───────────────────────────────────────────────────
    fragColor = vec4(clamp(col + contamination, 0.0, 1.0), 1.0);
}