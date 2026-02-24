#version 430 core
// ─────────────────────────────────────────────────────────────────────────────
// p00_normalize.frag
//
// Internal setup pass — NOT one of the 20 physical post-process effects.
//
// The renderer accumulates radiance as a running SUM in an RGBA32F texture.
// This pass converts it to a proper average and applies the exposure EV
// multiplier, producing a normalised linear-HDR buffer as input for p01-p20.
//
// For static image test mode this pass is called with u_totalSamples = 1 and
// u_exposure = 1.0, so it acts as an identity / format-conversion blit.
// ─────────────────────────────────────────────────────────────────────────────

in  vec2 v_uv;
out vec4 fragColor;

uniform sampler2D u_tex;
uniform int       u_totalSamples; // divide running sum by this
uniform float     u_exposure;     // EV multiplier (1.0 = no change)

void main() {
    float n   = float(max(u_totalSamples, 1));
    vec3  hdr = texture(u_tex, v_uv).rgb / n * u_exposure;

    // Keep in linear HDR — tone mapping is p20's job.
    fragColor = vec4(hdr, 1.0);
}
