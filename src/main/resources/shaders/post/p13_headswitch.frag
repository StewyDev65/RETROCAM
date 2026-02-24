#version 430 core
// ─────────────────────────────────────────────────────────────────────────────
// p13_headswitch.frag
//
// VHS rotating-head switch artifact at the bottom of the frame.
//
// PHYSICAL BASIS:
//   A two-head VHS drum rotates at 1800 RPM (NTSC), each head reading one
//   helical track per field (~16.7 ms).  The switch between Head A and Head B
//   must occur during vertical blanking.  Consumer decks (especially at end-
//   of-life or with worn drums) allow the switch point to fall inside the
//   active picture — typically the bottom 6–16 scanlines.
//
//   Three simultaneous analog errors occur at the switch point:
//
//   1. TIME-BASE ERROR SPIKE (capstan servo unlocks briefly)
//      The capstan phase-locked loop drives the tape at a speed that keeps the
//      playback head perfectly on-track.  When the head switches, the servo
//      reference changes (Head B's phase detector reads the CTL track at a
//      slightly different timing than Head A's).  Until the PLL re-locks
//      (~4 scanlines), each subsequent line is horizontally displaced with
//      an amplitude 3–6× the normal TBE floor.  The jitter DECREASES as we
//      move further from the switch point (PLL settling).
//
//   2. LUMA RIPPLE (AGC re-settling)
//      Head A and Head B have slightly different sensitivities (worn heads
//      develop unequal oxide contact zones).  The AGC circuit, which uses the
//      FM carrier envelope to maintain constant playback level, must adapt to
//      the new head's sensitivity.  During settling, the demodulated luma
//      oscillates above and below nominal — visible as a brightness "shimmy"
//      that rings at the AGC bandwidth (~40 Hz) for 2–5 scanlines.
//
//   3. CHROMA PHASE JUMP
//      VHS uses a heterodyne chroma system: the chroma sub-carrier is
//      down-converted to ~629 kHz for recording.  Head A and Head B record
//      with a 90° phase rotation between adjacent tracks (to reduce cross-
//      colour from adjacent-track crosstalk).  The decoder applies a
//      compensating 90° rotation on playback.  At the switch point, if the
//      compensation is even slightly mis-timed, the chroma rotates briefly,
//      shifting hue by ±15–40° in the affected scanlines before the AFC
//      re-locks.  This is visible as a brief colour flash / hue jump.
//
// IMPLEMENTATION:
//   For each fragment, compute its scanline distance from the switch point
//   (u_switchLine, measured from the bottom of the frame).  Fragments above
//   the region are unaffected.  Within the region:
//     - Amplified TBE: amplitude decays exponentially from switch line upward.
//     - Luma ripple: a sinusoidal oscillation in luma decaying from switch line.
//     - Chroma shift: hue rotation in YCbCr (rotate Cb/Cr by small angle).
//   All three effects are seeded by frameIndex so they change each frame.
// ─────────────────────────────────────────────────────────────────────────────

in  vec2 v_uv;
out vec4 fragColor;

uniform sampler2D u_tex;

// Number of scanlines above the bottom of the frame that the switch affects.
// Real VHS: 6–16 lines.  Default 12 gives visible but believable shimmy.
uniform int   u_switchLines;     // [4, 24], default 12

// Amplitude multiplier for the TBE spike relative to normal TBE.
// At 3.0 the jitter is clearly visible but not tear-level.
uniform float u_jitterScale;     // [1.0, 8.0], default 4.0

// Luma ripple amplitude (fraction of full scale).
// 0.10 = 10% brightness swing — noticeable but not blinding.
uniform float u_lumaRipple;      // [0.0, 0.35], default 0.10

// Chroma rotation amplitude in radians (max at switch line).
// π/8 ≈ 22.5° gives a visible hue jump without appearing fully corrupt.
uniform float u_chromaRotAmp;    // [0.0, 1.2], default 0.35

uniform int   u_frameIndex;

// ── Hash utilities ────────────────────────────────────────────────────────────

uint uHash(uint x) {
    x = ((x >> 16u) ^ x) * 0x45d9f3bu;
    x = ((x >> 16u) ^ x) * 0x45d9f3bu;
    x = (x >> 16u) ^ x;
    return x;
}

float fHash(uint a, uint b) {
    return float(uHash(a ^ uHash(b) * 2654435761u)) / 4294967295.0;
}

// Smooth value noise in 1D (for scanline-correlated jitter)
float smoothNoise1D(float x, uint seed) {
    float i  = floor(x);
    float f  = fract(x);
    float u  = f * f * (3.0 - 2.0 * f);
    float a  = fHash(uint(i),     seed);
    float b  = fHash(uint(i) + 1u, seed);
    return mix(a, b, u) * 2.0 - 1.0;  // remap to [-1, 1]
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
        y               + 1.40200 * cr,
        y - 0.34414 * cb - 0.71414 * cr,
        y + 1.77200 * cb
    );
}

// Rotate (Cb, Cr) around the achromatic axis by angle theta.
// In YCbCr space this is equivalent to a hue rotation.
vec2 rotateCbCr(vec2 cbcr, float theta) {
    float c = cos(theta);
    float s = sin(theta);
    return vec2(cbcr.x * c - cbcr.y * s,
                cbcr.x * s + cbcr.y * c);
}

// ─────────────────────────────────────────────────────────────────────────────

void main() {
    vec2  texSize   = vec2(textureSize(u_tex, 0));
    float scanlineY = floor(v_uv.y * texSize.y);   // integer scanline (0 = bottom)

    // Distance from the bottom of the frame (0 = very bottom scanline)
    float distFromBottom = scanlineY;

    // Only affect scanlines within the head-switch zone
    float switchZone = float(u_switchLines);
    if (distFromBottom >= switchZone) {
        fragColor = vec4(texture(u_tex, v_uv).rgb, 1.0);
        return;
    }

    // Normalised proximity to the switch line (1.0 right at switch, 0.0 at top of zone)
    // The switch line is at the VERY BOTTOM scanline (distFromBottom = 0).
    // Effects are strongest there and decay upward (exponential settling).
    float proximity = 1.0 - (distFromBottom / switchZone);   // [0, 1], 1 = at switch
    float settle    = proximity * proximity;                   // quadratic decay curve

    // Per-frame seed so the pattern changes every frame
    uint frameSeed = uint(u_frameIndex);

    // ── 1. Amplified time-base error (capstan PLL settling) ──────────────────
    // Base TBE: smoothNoise in scanline coordinate, slow frame drift.
    // We amplify by u_jitterScale * settle.
    float scanNoise  = smoothNoise1D(distFromBottom * 0.8, frameSeed);
    // A fast secondary noise adds high-frequency jitter right at the switch line
    float fastNoise  = smoothNoise1D(distFromBottom * 3.0, frameSeed + 7u);
    float jitterPx   = u_jitterScale * settle * (scanNoise * 0.7 + fastNoise * 0.3);
    float uvOffset   = jitterPx / texSize.x;

    vec2 sampleUv = vec2(clamp(v_uv.x + uvOffset, 0.0, 1.0), v_uv.y);

    // ── 2. Luma ripple (AGC re-settling oscillation) ──────────────────────────
    // Model as a damped sinusoid: sin(ωt) × e^(-αt), t = distance from switch.
    // In scanline units: ω ≈ 2π / 4 (one oscillation over 4 lines ≈ AGC BW).
    // The fHash gives a per-frame phase so the oscillation doesn't look static.
    float phaseRand  = fHash(frameSeed, 0xCAFEBABEu) * 6.283185;
    float rippleSin  = sin(distFromBottom * 1.570796 + phaseRand); // π/2 per line
    float ripple     = u_lumaRipple * settle * rippleSin;

    // ── 3. Chroma rotation (AFC mis-timing) ──────────────────────────────────
    // The phase jump is largest right at the switch line and decays upward.
    // Direction (sign) varies per frame to simulate head A → B vs B → A.
    float chromaSign = (fHash(frameSeed, 0xBEEFu) > 0.5) ? 1.0 : -1.0;
    float chromaAngle = u_chromaRotAmp * settle * chromaSign;

    // ── Compose ───────────────────────────────────────────────────────────────
    vec3 rgb = texture(u_tex, sampleUv).rgb;
    vec3 ycc = rgbToYCbCr(rgb);

    // Apply luma ripple (additive in Y)
    ycc.x += ripple;

    // Apply chroma rotation (rotate Cb/Cr)
    vec2 cbcr = rotateCbCr(vec2(ycc.y - 0.5, ycc.z - 0.5), chromaAngle);
    ycc.y = cbcr.x + 0.5;
    ycc.z = cbcr.y + 0.5;

    fragColor = vec4(yCbCrToRgb(ycc), 1.0);
}
