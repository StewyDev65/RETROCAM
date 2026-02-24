#version 430 core
// ─────────────────────────────────────────────────────────────────────────────
// p16_interlace.frag  —  480i interlaced field separation + motion comb
//
// PHYSICAL BASIS:
//   NTSC 480i captures two interlaced fields per frame:
//     Field 1 (odd  lines: 1, 3, 5, …, 479) — captured at time T
//     Field 2 (even lines: 2, 4, 6, …, 480) — captured at T + 1/60 s later
//
//   THREE distinct physical phenomena are simulated:
//
//   1. HALF-LINE VERTICAL OFFSET (always present, even in still images):
//      The NTSC standard specifies that Field 2 line centers fall BETWEEN
//      Field 1 line centers — a 0.5-line vertical phase offset. This is a
//      fundamental property of interlaced scanning geometry, not a defect.
//      Field 1 lines sit at y = 0, 2H, 4H, …  Field 2 lines at y = H, 3H, …
//      (H = inter-line spacing).  When both fields are combined in a weave
//      display, the result has twice the vertical sample density of either
//      field alone.  In practice on a CRT, the two fields blended smoothly
//      because the phosphor glowed during both passes.  On a progressive LCD
//      (our output), the half-line offset manifests as a very slight vertical
//      softening on still content that is perceptually characteristic of SD
//      interlaced video.
//
//      Implementation: odd lines are displaced -0.5px vertically (Field 1
//      positions), even lines are displaced +0.5px (Field 2 positions).
//
//   2. MOTION COMB (present only when objects move between fields):
//      The 1/60 s inter-field gap means a moving subject appears at different
//      horizontal positions in Field 1 vs Field 2. When both fields are
//      displayed simultaneously as a weave, the displaced edges produce
//      alternating horizontal lines — the comb artifact.
//
//      Without a velocity buffer, we estimate local motion from the VERTICAL
//      luminance gradient.  A strong vertical edge (large |dY/dy|) indicates
//      a horizontal edge in the image — exactly the kind of edge that produces
//      combing on horizontal motion. A strong horizontal edge in the image
//      (large |dY/dx|) indicates a region likely to comb on vertical motion.
//
//      We apply the horizontal comb displacement only to Field 2 (even lines),
//      since those represent the "later" capture moment. The displacement
//      direction follows the local horizontal luminance gradient sign (bright
//      side displaces away from dark side — realistic for a rightward-moving
//      object). u_combStrength is in pixels and controls how far apart the
//      two fields appear on moving edges.
//
//   3. INTER-FIELD BRIGHTNESS ASYMMETRY (CRT phosphor decay):
//      On a real CRT, the electron beam writes Field 1, then Field 2 sixteen
//      milliseconds later. The phosphor strip that was excited for Field 1
//      has begun to decay by the time Field 2 is written, and vice versa.
//      At 30 fps (frame rate), the eye integrates both fields but the Field 2
//      phosphors are slightly newer / brighter when the eye first sees them.
//      The net perceptual result is a very faint alternating brightness
//      between odd and even lines. We implement this as a small multiplicative
//      dimming on even lines (u_lineWeighting ≈ 0.05 = 5% dimmer).
// ─────────────────────────────────────────────────────────────────────────────

in  vec2 v_uv;
out vec4 fragColor;

uniform sampler2D u_tex;

// Half-line vertical shift in pixels (NTSC spec = 0.5 px exactly).
uniform float u_fieldOffsetPx;   // [0.0, 1.0], default 0.5

// Comb displacement magnitude in pixels.
// Physical range for 1/60 s inter-field gap at walking speed: 0.5–3 px.
// 0 = no combing (still camera/subject); 3 = aggressive 90s-action look.
uniform float u_combStrength;    // [0.0, 6.0] pixels, default 1.5

// Edge threshold for the vertical gradient to trigger combing.
// Prevents combing in flat areas / gradients where it would not appear naturally.
uniform float u_combEdgeThresh;  // [0.01, 0.15], default 0.04

// Brightness dimming applied to even lines (Field 2) to simulate CRT phosphor decay.
// 0 = equal brightness; 0.05 = subtle but authentic.
uniform float u_lineWeighting;   // [0.0, 0.20], default 0.05

// ─────────────────────────────────────────────────────────────────────────────

void main() {
    vec2 texSize = vec2(textureSize(u_tex, 0));
    vec2 px      = 1.0 / texSize;

    // Scanline integer index (y=0 is the top of the frame).
    int fragY  = int(floor(v_uv.y * texSize.y));
    bool isEven = (fragY & 1) == 0;   // true = Field 2 (even line)

    // ── 1. Half-line vertical offset ─────────────────────────────────────────
    // Odd  lines (Field 1): shift UP by fieldOffsetPx * 0.5 (toward prior field).
    // Even lines (Field 2): shift DOWN by fieldOffsetPx * 0.5 (toward next field).
    // The 0.5 factor is because both fields together span one full line pitch.
    float vertShift = isEven
        ? (+u_fieldOffsetPx * 0.5 * px.y)
        : (-u_fieldOffsetPx * 0.5 * px.y);

    // ── 2. Motion comb estimation via vertical gradient proxy ─────────────────
    // Sample luminance above/below and left/right at the UNSHIFTED UV to get
    // gradient components before field offset distorts the neighborhood.
    vec3  cHere  = texture(u_tex, v_uv).rgb;
    vec3  cAbove = texture(u_tex, vec2(v_uv.x, v_uv.y - px.y)).rgb;
    vec3  cBelow = texture(u_tex, vec2(v_uv.x, v_uv.y + px.y)).rgb;
    vec3  cLeft  = texture(u_tex, vec2(v_uv.x - px.x, v_uv.y)).rgb;

    // BT.601 luma coefficients (no full YCbCr conversion needed here).
    const vec3 LUM = vec3(0.299, 0.587, 0.114);
    float yHere  = dot(cHere,  LUM);
    float yAbove = dot(cAbove, LUM);
    float yBelow = dot(cBelow, LUM);
    float yLeft  = dot(cLeft,  LUM);

    // Vertical gradient magnitude: proxy for horizontal edges that would comb.
    float vertGrad = abs(yAbove - yBelow);

    // Horizontal gradient sign: determines which direction the comb displaces.
    // A bright-left / dark-right edge (hDir > 0) corresponds to an object
    // moving rightward between fields — even line displaces to the right.
    float hDir = sign(yHere - yLeft);

    // Comb activates only on Field 2 (even lines) and only above the edge threshold.
    float combOffsetPx = 0.0;
    if (isEven) {
        float edgeWeight = smoothstep(u_combEdgeThresh, u_combEdgeThresh * 3.0, vertGrad);
        combOffsetPx = hDir * edgeWeight * u_combStrength;
    }

    // ── Compose final sample UV ───────────────────────────────────────────────
    vec2 sampleUv = vec2(
        v_uv.x + combOffsetPx * px.x,
        v_uv.y + vertShift
    );
    sampleUv = clamp(sampleUv, vec2(0.0), vec2(1.0));

    vec3 col = texture(u_tex, sampleUv).rgb;

    // ── 3. Inter-field brightness asymmetry (CRT phosphor decay) ─────────────
    // Even lines (Field 2) are fractionally dimmer; odd lines (Field 1) are
    // compensated slightly brighter so the average luminance is preserved.
    col *= isEven ? (1.0 - u_lineWeighting) : (1.0 + u_lineWeighting * 0.5);

    fragColor = vec4(col, 1.0);
}
