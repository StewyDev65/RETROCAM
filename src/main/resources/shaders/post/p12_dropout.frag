#version 430 core
// ─────────────────────────────────────────────────────────────────────────────
// p12_dropout.frag
//
// Analog VHS tape dropout — horizontal segments of warped/sampled nearby pixels.
//
// PHYSICAL BASIS:
//   When the VHS playback head loses contact with the tape (oxide gap, debris,
//   crease) the RF carrier drops below the FM threshold detector.  The deck's
//   Dropout Compensator (DOC) detects the envelope collapse and switches to
//   a 1H delay line, substituting the previous scanline's signal.  However
//   the DOC has a reaction time (~200 ns), so at the very start of a dropout
//   the head continues reading a signal — but from an adjacent, slightly
//   misregistered track.  This produces a horizontal segment where the pixels
//   are pulled from the wrong position in the image: shifted horizontally,
//   vertically displaced, or stretched — a warped but image-based replacement.
//
//   TEMPORAL PATTERN — Critical:
//   A worn tape has clustered defects (scratches, crease zones, shedding
//   patches) separated by long sections of intact oxide.  During playback
//   this produces BURSTS of dropout activity lasting 0.5–3 seconds, separated
//   by clean periods of 3–15 seconds.  The tape must physically advance past
//   the defective zone before dropouts cease.
//
//   VISUAL PROPERTIES:
//   • Strictly horizontal segments, 1 scanline tall (rarely 2).
//   • Width: 8–200 pixels; most are short (pinhole = 8–30 px).
//   • Replacement content: pixels sampled from a nearby UV — shifted left/right
//     by 2–20 px and/or up/down by 1–2 lines.  The color is recognizably
//     image-derived but clearly wrong (stretched or smeared).
//   • A faint brightness delta at the leading edge (DOC switching transient).
//   • During bursts: 3–12 events visible per frame across different scanlines.
//   • Outside bursts: zero or at most 1 stray event per several seconds.
//
// IMPLEMENTATION:
//   1. A slow burst envelope (seeded per ~1-second windows) determines whether
//      we are in an ACTIVE or QUIET period.  Active windows cluster.
//   2. Within active windows, per-video-field event seeds spawn dropout events.
//   3. Each covered fragment samples a UV displaced by a random (dx, dy) —
//      no white fill, always image-derived color.
// ─────────────────────────────────────────────────────────────────────────────

in  vec2 v_uv;
out vec4 fragColor;

uniform sampler2D u_tex;
uniform float     u_tapeAge;    // 0 = mint, 1 = heavily worn
uniform float     u_dropoutBright; // leading-edge transient brightness boost [0,1]
uniform int       u_frameIndex;

uniform float     u_burstRate;      // 0=very infrequent bursts, 1=every field has dropouts

#define MAX_DROPOUTS 14
// Render fps assumed ~60; video field rate ~30
#define VIDEO_FPS 30

// ── Hash utilities ─────────────────────────────────────────────────────────────

uint uHash(uint x) {
    x ^= x >> 16u;
    x *= 0x45d9f3bu;
    x ^= x >> 16u;
    return x;
}

float fHash(uint a, uint b) {
    return float(uHash(a ^ uHash(b) * 2654435761u)) / 4294967295.0;
}

// ── Burst envelope ─────────────────────────────────────────────────────────────
// Returns 0..1 activity multiplier for the current second-window.
// Produces clusters of active seconds separated by quiet gaps.
float burstActivity(uint videoFrame, float tapeAge, float burstRate) {
    // Window size in video frames (~1 second each)
    const uint WINDOW = uint(VIDEO_FPS);

    uint window    = videoFrame / WINDOW;
    uint prevWin   = window - 1u;

    // Each window independently decides if it is "active".
    // u_burstRate directly sets the fraction of windows that are active,
    // independent of tape age (age only governs events-per-burst, not gap length).
    float activationThreshold = 1.0 - u_burstRate;

    float thisActive = step(activationThreshold, fHash(window,  0xDEADBEEFu));
    float prevActive = step(activationThreshold, fHash(prevWin, 0xDEADBEEFu));

    // Bleed a bit from the previous window so bursts feel continuous,
    // not hard-cut.  The sub-frame position within the window drives the blend.
    float windowPos  = float(videoFrame % WINDOW) / float(WINDOW);
    float bleed      = prevActive * smoothstep(0.7, 1.0, 1.0 - windowPos); // tail of prev
    float activity   = max(thisActive, bleed);

    return activity;
}

void main() {
    vec2  res    = vec2(textureSize(u_tex, 0));
    vec3  col    = texture(u_tex, v_uv).rgb;

    float fragX  = v_uv.x * res.x;
    float fragY  = floor(v_uv.y * res.y);

    uint  videoFrame = uint(u_frameIndex / 2);

    // ── Burst gate ────────────────────────────────────────────────────────────
    float activity = burstActivity(videoFrame, u_tapeAge, u_burstRate);
    if (activity < 0.001) {
        fragColor = vec4(col, 1.0);
        return;
    }

    // Effective event count: burst activity × age × MAX
    // Even at age=0 allow 1 rare stray event so it never looks completely immune
    float ageFactor = 0.05 + u_tapeAge * 0.95;
    int   nEvents   = 1 + int(round(activity * ageFactor * float(MAX_DROPOUTS - 1)));

    // ── Evaluate dropout events ───────────────────────────────────────────────
    float bestWeight = 0.0;
    vec2  bestUv     = v_uv;
    float bestBurst  = 0.0;

    for (int i = 0; i < nEvents; i++) {
        uint si = uint(i);

        // Scanline for this event (random Y per field)
        float evY  = floor(fHash(videoFrame * 31u + si, si * 13u + 1u) * res.y);

        // Rarely span 2 scanlines (clustered pinhole or small crease)
        int evH = (fHash(videoFrame + si * 3u, si + 77u) > 0.88) ? 2 : 1;

        if (fragY < evY || fragY >= evY + float(evH)) continue;

        // ── Width: short pinholes (8–40 px) dominant; rare long (80–220 px) ──
        float wr = fHash(videoFrame * 7u + si, si * 7u + 2u);
        float evWidth;
        if (fHash(videoFrame * 2u + si, si + 55u) > 0.88) {
            evWidth = 80.0 + wr * 140.0;   // long (crease / head clog edge)
        } else {
            evWidth = 8.0  + wr * wr * 32.0; // short pinhole, biased short
        }

        float maxStart = max(0.0, res.x - evWidth);
        float evXStart = fHash(videoFrame * 5u + si, si * 7u + 3u) * maxStart;

        if (fragX < evXStart || fragX >= evXStart + evWidth) continue;

        // ── Soft edges (abrupt left = head hits gap; gradual right = DOC lag) ──
        float dLeft  = fragX - evXStart;
        float dRight = (evXStart + evWidth) - fragX;
        float weight = smoothstep(0.0, 1.5, dLeft) * smoothstep(0.0, 5.0, dRight);

        // ── Displacement for sampled replacement ─────────────────────────────
        // X shift: up to ±20 px — models misread from adjacent track position
        float dxNorm = fHash(videoFrame * 11u + si, si * 7u + 4u) * 2.0 - 1.0;
        float dx     = dxNorm * (4.0 + u_tapeAge * 16.0);

        // Y shift: 0, +1, or -1 scanlines — DOC may insert wrong line
        float dyRnd  = fHash(videoFrame * 13u + si, si * 7u + 5u);
        float dy     = (dyRnd > 0.6) ? (dyRnd > 0.8 ? -1.0 : 1.0) : 0.0;

        // Horizontal stretch smear: on severe dropouts the track misread
        // causes a pixel-stretch, sample at a scaled x within the segment
        float stretchR = fHash(videoFrame * 17u + si, si * 7u + 6u);
        float stretch  = (stretchR > 0.7) ? (1.0 + stretchR * u_tapeAge * 0.5) : 1.0;
        float centreX  = evXStart + evWidth * 0.5;
        float stretchedX = centreX + (fragX - centreX) * stretch + dx;

        vec2 dispUv = vec2(
            clamp(stretchedX / res.x, 0.0, 1.0),
            clamp((fragY + dy) / res.y, 0.0, 1.0)
        );

        // Leading-edge brightness transient (DOC switching artifact)
        float burstFade = smoothstep(3.0, 0.0, dLeft) * u_dropoutBright;

        if (weight > bestWeight) {
            bestWeight = weight;
            bestUv     = dispUv;
            bestBurst  = burstFade;
        }
        break;
    }

    if (bestWeight < 0.001) {
        fragColor = vec4(col, 1.0);
        return;
    }

    // ── Composite: image-based replacement + leading-edge transient ───────────
    vec3 replaced = texture(u_tex, bestUv).rgb;

    // Slight luma lift on the replaced segment (AGC ringing during DOC switch)
    replaced *= 1.0 + bestBurst * 0.3;
    // Subtle desaturation — the misread track often has colour phase error
    float lumR  = dot(replaced, vec3(0.2126, 0.7152, 0.0722));
    replaced    = mix(replaced, vec3(lumR), bestBurst * 0.4);

    vec3 result = mix(col, replaced, bestWeight);
    fragColor   = vec4(result, 1.0);
}