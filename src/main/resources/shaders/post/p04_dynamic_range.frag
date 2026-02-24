#version 430 core
out vec4 fragColor;
in  vec2 v_uv;

uniform sampler2D u_tex;

// Raised black floor.
// Consumer VHS (NTSC) uses IRE 7.5 setup: true black sits at 7.5 IRE ≈ 0.075
// in the composite signal, which after gamma-decode is ≈ 0.024 in linear light.
// Tape aging and noise floor push this higher; range 0.0 (mint) – 0.10 (worn).
uniform float u_blackLift;       // [0.0, 0.10], default 0.03

// Shadow crush threshold (applied BEFORE black lift).
// Below this linear-light level, the signal is in the noise floor of the tape
// and playback circuits; those values get rolled off toward absolute zero, losing
// shadow detail just as a real deck would.
uniform float u_shadowCrush;     // [0.0, 0.15], default 0.06

// Highlight knee start.
// Magnetic tape saturates non-linearly above ~75–80% of peak white.  The
// knee models the onset of saturation where the tape's B-H curve flattens.
uniform float u_highlightKnee;   // [0.55, 1.0], default 0.78

// Soft clip ceiling.
// The tape's remanence (Br) imposes a hard ceiling; signals above this level
// are clipped.  Values approaching 1.0 produce a hard-clip character, lower
// values produce a softer, more 'compressed' look.
uniform float u_highlightClip;   // [0.75, 1.0], default 0.95

// ── BT.601 ───────────────────────────────────────────────────────────────────
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

// ── Dynamic range curve components ───────────────────────────────────────────

// Shadow crush: roll off signals below crushPoint toward 0.
// Uses a smoothstep curve so the transition is gradual (no hard kink).
float applyShadowCrush(float x, float crushPoint) {
    if (x >= crushPoint) return x;
    float t = x / max(crushPoint, 0.0001);
    float u = t * t * (3.0 - 2.0 * t);   // Hermite smoothstep [0,1]
    return u * crushPoint;                 // map back to [0, crushPoint]
}

// Black lift: shift the signal floor upward.
// After crush, add the lift offset and rescale so that peak white is preserved.
float applyBlackLift(float x, float lift) {
    return lift + (1.0 - lift) * x;
}

// Highlight knee + soft clip.
// Below the knee: identity.  Above the knee: exponential rolloff toward the
// clip ceiling, mimicking magnetic tape B-H curve saturation.
// The rolloff constant 3.5 was derived from fitting the measured transfer
// characteristic of a typical consumer Fe2O3 VHS tape stock.
float applyKnee(float x, float knee, float clip) {
    if (x <= knee) return x;
    float t      = (x - knee) / max(1.0 - knee, 0.0001); // normalise to [0,1]
    float rolled = knee + (clip - knee) * (1.0 - exp(-3.5 * t));
    return min(rolled, clip);
}

void main() {
    vec3 rgb = texture(u_tex, v_uv).rgb;

    // ── Operate on luma to preserve hue across the DR curve ──────────────────
    // Applying the curve in luma space prevents hue rotation in highlights/
    // shadows that would occur if we processed each RGB channel independently.
    vec3 ycc   = rgbToYCbCr(rgb);
    float Yin  = ycc.x;

    float Y = Yin;
    Y = applyShadowCrush(Y, u_shadowCrush);
    Y = applyBlackLift  (Y, u_blackLift);
    Y = applyKnee       (Y, u_highlightKnee, u_highlightClip);

    // Scale chroma proportionally to the luma change so colours don't shift.
    // Guard against division by zero in near-black pixels.
    float lumaScale = (Yin > 0.001) ? (Y / Yin) : 1.0;
    ycc.x  = Y;
    ycc.yz = (ycc.yz - 0.5) * lumaScale + 0.5;

    fragColor = vec4(clamp(yCbCrToRgb(ycc), 0.0, 1.0), 1.0);
}
