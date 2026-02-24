#version 430 core
out vec4 fragColor;
in  vec2 v_uv;

uniform sampler2D u_tex;

// Luminance threshold above which CCD charge begins to overflow.
// In a frame-transfer or interline-transfer CCD, pixels above full-well
// capacity (~85% of peak white for consumer camcorders) spill charge into
// adjacent cells in the same vertical shift register column.
uniform float u_smearThreshold;  // [0.5, 1.0], default 0.85

// Smear streak brightness multiplier.
// Controls how much the overflow charge contributes to the streak.
// Real CCD smear is proportional to overexposure magnitude × integration time.
uniform float u_smearIntensity;  // [0.0, 1.0], default 0.30

// Vertical extent of the streak in pixels.
// Consumer CCD cameras typically show a 60–150 px streak at full exposure
// (corresponding to the full frame-shift duration).
uniform int u_smearLength;       // [10, 150], default 80

// ── Physical basis ────────────────────────────────────────────────────────────
//
// During vertical readout (top → bottom), a saturated pixel continues to
// generate photoelectrons that leak into the shift register.  This charge
// is picked up by every subsequent row in that column, creating a uniform-
// intensity streak downward from the bright source.
//
// Additionally, a weaker upward smear occurs in interline-transfer CCDs due
// to the anti-blooming drain structure filling up before overflow begins.
//
// Streak model:
//   Primary (downward):  uniform intensity × overexposure, full u_smearLength
//   Secondary (upward):  25% intensity, 30% of primary length, exponential decay
//
// In FBO UV space (y=0 = bottom, y=1 = top of screen when displayed normally):
//   Downward on screen  →  decrease in UV y  →  look at pixels at y + δ (above)
//   Upward on screen    →  increase in UV y  →  look at pixels at y - δ (below)
//
// The loop limit MAX_TAPS is a compile-time constant; the runtime u_smearLength
// is used as an early-exit guard so the GPU never executes dead work above the
// requested streak height.
// ─────────────────────────────────────────────────────────────────────────────

const int MAX_TAPS = 150;

// BT.601 luma
float luma(vec3 rgb) {
    return 0.299*rgb.r + 0.587*rgb.g + 0.114*rgb.b;
}

void main() {
    vec3  base      = texture(u_tex, v_uv).rgb;
    vec2  texelSize = 1.0 / vec2(textureSize(u_tex, 0));

    // ── Primary smear: downward (dominant) ───────────────────────────────────
    // For pixel at v_uv, accumulate excess charge from bright pixels above it.
    // "Above" in display = higher UV.y → sample at v_uv + (0, +i*dy).
    float primaryAccum = 0.0;
    int   primaryTaps  = 0;

    for (int i = 1; i <= MAX_TAPS; i++) {
        if (i > u_smearLength) break;

        vec2  sUv = v_uv + vec2(0.0, float(i) * texelSize.y);
        if (sUv.y > 1.0) break;

        float srcLuma  = luma(texture(u_tex, sUv).rgb);
        float overflow = max(0.0, srcLuma - u_smearThreshold);
        primaryAccum  += overflow;
        primaryTaps++;
    }

    // Normalise: divide by total taps so longer streaks don't brighten more.
    // This preserves intensity invariance with streak length.
    float primaryContrib = (primaryTaps > 0)
        ? (primaryAccum / float(primaryTaps)) * u_smearIntensity
        : 0.0;

    // ── Secondary smear: upward (anti-blooming drain, 25% strength) ──────────
    // Shorter and exponentially decaying – models pre-saturation upward charge
    // migration in the anti-blooming structure.
    int   secondaryLen   = max(1, int(float(u_smearLength) * 0.30));
    float secondaryAccum = 0.0;
    int   secondaryTaps  = 0;

    for (int i = 1; i <= MAX_TAPS; i++) {
        if (i > secondaryLen) break;

        vec2  sUv = v_uv - vec2(0.0, float(i) * texelSize.y);
        if (sUv.y < 0.0) break;

        float srcLuma  = luma(texture(u_tex, sUv).rgb);
        float overflow = max(0.0, srcLuma - u_smearThreshold);
        // Exponential decay away from source: weight = 0.7^i
        secondaryAccum += overflow * pow(0.70, float(i));
        secondaryTaps++;
    }

    float secondaryContrib = (secondaryTaps > 0)
        ? (secondaryAccum / float(secondaryTaps)) * u_smearIntensity * 0.25
        : 0.0;

    // ── Combine ───────────────────────────────────────────────────────────────
    // CCD smear colour: the charge that overflows is unfiltered (it bypasses
    // the colour filter array in the shift registers), so the streak is white/
    // achromatic.  Add equal amounts to all three channels.
    float totalSmear = primaryContrib + secondaryContrib;
    vec3  result     = base + vec3(totalSmear);

    fragColor = vec4(clamp(result, 0.0, 1.0), 1.0);
}
