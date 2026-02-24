#version 430 core
// ─────────────────────────────────────────────────────────────────────────────
// p14_tracking.frag  —  VHS tracking error
//
// PHYSICAL BASIS:
//   VHS helical scan writes diagonal tracks across the tape at ~5.96°. The
//   playback drum rotation is phase-locked to the Control Track (CTL) pulse —
//   a 30 Hz reference recorded on the tape edge. When the CTL servo loses
//   lock (worn tape, head clog, misaligned mechanism), the drum phase drifts
//   relative to the recorded tracks. The head reads across track boundaries,
//   producing several simultaneous analog errors:
//
//   1. WARP / BENDING — As the head drifts across a track, the amount of
//      mis-registration increases continuously from the top of the band to
//      the bottom (or vice versa), creating a smooth bend rather than a
//      simple horizontal shift. The capstan flutter superimposes a sine-like
//      wobble on top of this ramp. The result: the image within the band
//      appears to curve or "S-bend" rather than slide uniformly.
//
//   2. GUARD-BAND RAINBOW FRINGING — At the transition between correct and
//      mis-tracked regions the head passes over the 1.5 µm guard band. Here
//      there is no recorded signal; the deck's AGC circuit responds to the
//      envelope collapse by slamming gain up, amplifying the residual chroma
//      sub-carrier from the adjacent track. Because the adjacent track's
//      chroma is recorded with a 90° phase offset (cross-colour suppression),
//      the demodulated chroma is completely wrong — producing a vivid stripe
//      of saturated colour (often magenta-cyan or red-green) 2–6 px wide
//      right at the band boundary.
//
//   3. INTER-TRACK CHROMA ERROR — Inside the displaced band the head is
//      reading from the adjacent track whose chroma was recorded with a 90°
//      phase rotation. Even with partial comb filter compensation, ~20–40° of
//      residual hue error remains across the whole band. The error reverses
//      sign from one event to the next (Head A→B vs B→A).
//
//   4. LUMA NOISE IN GUARD ZONE — The guard band and adjacent-track cross-
//      talk produce high-frequency luma noise (looks like RF buzz), strongest
//      at the edges and fading toward the centre of the displaced region.
//
//   5. TEMPORAL PERSISTENCE — A tracking error is a servo event, not a frame
//      event. Once the CTL PLL loses lock it stays lost for 0.5–4 seconds.
//      The displacement waveform is consistent across that entire window
//      (same band height, same general direction) though the warp wobble
//      evolves frame-to-frame. This is the most important property for
//      realism: the band must persist across many consecutive frames.
//
// IMPLEMENTATION:
//   Events are generated in fixed 1-second (30-frame) windows. Each window
//   independently decides whether it is an "active" window. Within an active
//   window a continuous event persists, possibly spanning into adjacent active
//   windows. Per-frame, only the warp wobble changes; band geometry and
//   displacement direction are locked to the window seed.
//
//   Band warp is a smooth S-curve: linear ramp across band height + sinusoidal
//   wobble seeded per frame. This gives the characteristic bending look.
//
//   Rainbow fringing at edges is implemented by rotating Cb/Cr by a large
//   angle (60–120°) in a thin stripe at the boundary, producing vivid colour.
// ─────────────────────────────────────────────────────────────────────────────

in  vec2 v_uv;
out vec4 fragColor;

uniform sampler2D u_tex;

// Overall severity.  0 = never; 1 = frequent, wide, severe.
uniform float u_severity;        // [0.0, 1.0], default 0.10

// Maximum displacement at the peak of the warp ramp (pixels).
uniform float u_maxDisplacePx;   // [4.0, 80.0], default 32.0

// Width of the rainbow fringe stripe at band edges (pixels).
uniform float u_fringeWidthPx;   // [1.0, 12.0], default 4.0

uniform int   u_frameIndex;

// ── Constants ─────────────────────────────────────────────────────────────────
#define MAX_EVENTS    2
#define EPOCH_FRAMES  30          // 1 second at 30 fps

// ── Hash utilities ─────────────────────────────────────────────────────────────

uint uHash(uint x) {
    x = ((x >> 16u) ^ x) * 0x45d9f3bu;
    x = ((x >> 16u) ^ x) * 0x45d9f3bu;
    x = (x >> 16u) ^ x;
    return x;
}

float fHash(uint a, uint b) {
    return float(uHash(a ^ uHash(b) * 2654435761u)) / 4294967295.0;
}

// Smooth 1D value noise
float smoothNoise1D(float x, uint seed) {
    float i = floor(x);
    float f = fract(x);
    float u2 = f * f * (3.0 - 2.0 * f);
    return mix(fHash(uint(i), seed), fHash(uint(i) + 1u, seed), u2);
}

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

vec2 rotateCbCr(vec2 cbcr, float theta) {
    float c = cos(theta), s = sin(theta);
    return vec2(cbcr.x*c - cbcr.y*s,
                cbcr.x*s + cbcr.y*c);
}

// ─────────────────────────────────────────────────────────────────────────────

void main() {
    vec2  texSize = vec2(textureSize(u_tex, 0));
    float fragY   = floor(v_uv.y * texSize.y);
    vec3  col     = texture(u_tex, v_uv).rgb;

    if (u_severity < 0.001) {
        fragColor = vec4(col, 1.0);
        return;
    }

    // Current epoch (1-second window index)
    uint curEpoch = uint(u_frameIndex) / uint(EPOCH_FRAMES);

    // Accumulated result from all events
    float totalDisplacePx = 0.0;
    float totalFringeAmt  = 0.0;
    float totalNoiseAmt   = 0.0;
    float totalHueAngle   = 0.0;
    float totalWeight     = 0.0;

    for (int e = 0; e < MAX_EVENTS; e++) {
        uint ei = uint(e);

        // Check current epoch and two prior (events can span epoch boundaries)
        for (int ep = 0; ep < 3; ep++) {
            uint epoch = curEpoch - uint(ep);

            // Spawn probability gated by severity
            float spawnChance = 0.08 + 0.62 * u_severity;
            float spawnRnd    = fHash(epoch * 7919u + ei * 3u, 0xDEADBEEFu);
            if (spawnRnd > spawnChance) continue;

            // Event duration: 15–120 frames (0.5–4 s)
            float durRnd     = fHash(epoch * 1009u + ei, 0xCAFEu);
            uint  evDuration = uint(15.0 + durRnd * 105.0);

            // Event starts at a random frame within its epoch
            float startRnd = fHash(epoch * 503u + ei, 0xBEEFu);
            uint  evStart  = epoch * uint(EPOCH_FRAMES)
                           + uint(startRnd * float(EPOCH_FRAMES));
            uint  evEnd    = evStart + evDuration;

            uint frame = uint(u_frameIndex);
            if (frame < evStart || frame >= evEnd) continue;

            // ── Band geometry (locked to epoch seed) ──────────────────────────
            float yRnd     = fHash(epoch * 2017u + ei, 0x1111u);
            float hRnd     = fHash(epoch * 3001u + ei, 0x2222u);
            float evHeight = 20.0 + hRnd * 60.0 * u_severity;
            float evYStart = yRnd * (texSize.y - evHeight);
            float evYEnd   = evYStart + evHeight;

            if (fragY < evYStart || fragY >= evYEnd) continue;

            // Normalised position within band [0,1]
            float bandT = (fragY - evYStart) / max(evHeight - 1.0, 1.0);

            // ── Warp / displacement ────────────────────────────────────────────
            // Base: linear ramp across the band (the head drifts continuously).
            float dirSign  = (fHash(epoch * 4003u + ei, 0x3333u) > 0.5) ? 1.0 : -1.0;
            float baseDisp = dirSign * u_maxDisplacePx * u_severity * (bandT - 0.5) * 2.0;

            // Per-frame wobble: sinusoidal — models capstan flutter.
            float framePhase = float(frame) * 0.37 + float(ei) * 1.57;
            float wobble     = sin(bandT * 6.28318 + framePhase)
                             * u_maxDisplacePx * 0.20 * u_severity;

            // Per-scanline micro-jitter
            float microJitter = (smoothNoise1D(fragY * 0.25, frame + ei * 17u) * 2.0 - 1.0)
                               * 2.5 * u_severity;

            float totalPx = baseDisp + wobble + microJitter;

            // ── Soft band edges ────────────────────────────────────────────────
            float distTop    = fragY - evYStart;
            float distBottom = evYEnd - fragY;
            float edgeWeight = smoothstep(0.0, 1.5, distTop)
                             * smoothstep(0.0, 4.0, distBottom);

            // Rainbow fringe strongest at band edges
            float edgeDist  = min(distTop, distBottom);
            float fringeAmt = (1.0 - smoothstep(0.0, u_fringeWidthPx, edgeDist))
                             * edgeWeight;

            float noiseAmt  = fringeAmt * 0.7;

            // Inter-track hue error (~25° inside the band)
            float hueAngle  = dirSign * 0.436 * u_severity * (1.0 - fringeAmt);

            if (edgeWeight > totalWeight) {
                totalWeight     = edgeWeight;
                totalDisplacePx = totalPx;
                totalFringeAmt  = fringeAmt;
                totalNoiseAmt   = noiseAmt;
                totalHueAngle   = hueAngle;
            }
        }
    }

    if (totalWeight < 0.001) {
        fragColor = vec4(col, 1.0);
        return;
    }

    // ── Sample displaced pixel ─────────────────────────────────────────────────
    float uvShift = (totalDisplacePx / texSize.x) * totalWeight;
    vec2  dispUv  = vec2(clamp(v_uv.x + uvShift, 0.0, 1.0), v_uv.y);
    vec3  dispCol = texture(u_tex, dispUv).rgb;
    vec3  ycc     = rgbToYCbCr(dispCol);

    // ── Inter-track chroma error (inside the band) ────────────────────────────
    if (abs(totalHueAngle) > 0.001) {
        vec2 cbcr = rotateCbCr(vec2(ycc.y - 0.5, ycc.z - 0.5), totalHueAngle);
        ycc.y = cbcr.x + 0.5;
        ycc.z = cbcr.y + 0.5;
    }

    // ── Rainbow fringe at band edges ──────────────────────────────────────────
    if (totalFringeAmt > 0.001) {
        // Slow, moderately rotating colour stripe — real fringe drifts gradually
        float fringePhase = float(u_frameIndex) * 0.12 + v_uv.y * 8.0;
        float fringeAngle = sin(fringePhase) * 0.9;   // up to ~52°

        vec2 fringeCbCr = rotateCbCr(vec2(ycc.y - 0.5, ycc.z - 0.5), fringeAngle);
        // Moderate saturation boost — vivid but not neon
        fringeCbCr *= 1.0 + totalFringeAmt * 1.2;

        ycc.y = mix(ycc.y, fringeCbCr.x + 0.5, totalFringeAmt);
        ycc.z = mix(ycc.z, fringeCbCr.y + 0.5, totalFringeAmt);

        // Luma RF buzz in the guard zone
        uint nSeed = uHash(uint(fragY) * 1619u ^ uint(u_frameIndex) * 2654435761u);
        float buzz = (float(uHash(nSeed ^ uint(gl_FragCoord.x * 37.0))) / 4294967295.0)
                    * 2.0 - 1.0;
        ycc.x += buzz * 0.15 * totalNoiseAmt;
    }

    vec3 result = mix(col, yCbCrToRgb(ycc), totalWeight);
    fragColor   = vec4(result, 1.0);
}
