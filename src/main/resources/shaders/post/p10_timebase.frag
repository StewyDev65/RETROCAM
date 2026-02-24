#version 430 core
// ─────────────────────────────────────────────────────────────────────────────
// p10_timebase.frag
//
// Time base error — per-scanline horizontal jitter.
//
// PHYSICAL BASIS:
//   The VHS tape transport is a mechanical system with inherent instability.
//   Each horizontal scanline is written/read at a slightly different horizontal
//   position due to capstan motor flutter and tape tension variation.  The
//   result is that each scanline shifts horizontally by a small, time-varying
//   amount independent of its neighbours — producing a subtle vertical ripple
//   when viewing motion.
//
//   Characteristics:
//   • Low-frequency spatial component: neighbouring scanlines are correlated
//     (the mechanism causing the error is a slow mechanical oscillation).
//   • Frame-to-frame drift: the wobble pattern slowly evolves over time.
//   • Small sinusoidal modulation superimposed on the noise floor.
//   • Amplitude is typically 0.5–2.5 px at 854 px width; severe TBE can be
//     4–8 px and is associated with tape damage or tracking problems.
//
// IMPLEMENTATION:
//   For each scanline y (in screen pixels):
//     offset = A * sin(y * FREQ + time * SPEED)   ← sinusoidal component
//            + A * 0.6 * smoothNoise(y * 0.05 + slowFrame)  ← noise component
//   The noise uses a smooth 1D gradient noise so adjacent scanlines are
//   correlated.  The UV x-coordinate is displaced by offset / screenWidth.
//   A clamp keeps the UV within [0,1] to avoid border artifacts.
// ─────────────────────────────────────────────────────────────────────────────

in  vec2 v_uv;
out vec4 fragColor;

uniform sampler2D u_tex;
uniform float     u_amplitude;  // max displacement in pixels, default 1.5
uniform float     u_freq;       // spatial frequency (rad/px), default 0.02
uniform float     u_speed;      // temporal evolution rate (rad/s), default 0.7
uniform float     u_time;       // elapsed seconds (TemporalState.time)
uniform int       u_frameIndex;

// ── Simple 1D smooth gradient noise ──────────────────────────────────────────

float hash1(float x) {
    return fract(sin(x * 127.1 + 311.7) * 43758.5453);
}

// Value noise with smooth Hermite interpolation
float smoothNoise1D(float x) {
    float i = floor(x);
    float f = fract(x);
    float u = f * f * (3.0 - 2.0 * f);       // smoothstep
    return mix(hash1(i), hash1(i + 1.0), u);
}

// ─────────────────────────────────────────────────────────────────────────────

void main() {
    vec2  texSize    = vec2(textureSize(u_tex, 0));
    float scanlineY  = v_uv.y * texSize.y;   // fractional scanline index

    // Slow-drift frame bucket — changes every 4 frames so the noise pattern
    // evolves gradually rather than randomly jumping each frame
    float frameBucket = float(u_frameIndex / 4) * 0.137;

    // Sinusoidal component (deterministic, smooth across scanlines)
    float sinPart   = sin(scanlineY * u_freq + u_time * u_speed);

    // Noise component (smooth random, slow temporal drift)
    float noisePart = smoothNoise1D(scanlineY * 0.05 + frameBucket) * 2.0 - 1.0;

    // Combined offset in pixels — sinusoidal and noise weighted roughly equally
    float offsetPx  = u_amplitude * (sinPart * 0.5 + noisePart * 0.5);

    // Convert to UV offset and clamp so we don't read outside the texture
    float uvOffset  = offsetPx / texSize.x;
    vec2  sampleUv  = vec2(clamp(v_uv.x + uvOffset, 0.0, 1.0), v_uv.y);

    fragColor = vec4(texture(u_tex, sampleUv).rgb, 1.0);
}
