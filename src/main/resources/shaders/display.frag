#version 430 core

in  vec2 v_uv;
out vec4 fragColor;

uniform sampler2D u_tex;
uniform int       u_totalSamples;   // divide accum sum by this
uniform float     u_exposure;       // extra EV adjustment (default 1.0)

// ── ACES fitted tonemapping (Krzysztof Narkowicz approximation) ───────────────
vec3 aces(vec3 x) {
    const float a = 2.51;
    const float b = 0.03;
    const float c = 2.43;
    const float d = 0.59;
    const float e = 0.14;
    return clamp((x * (a * x + b)) / (x * (c * x + d) + e), 0.0, 1.0);
}

// ── sRGB gamma encode ─────────────────────────────────────────────────────────
vec3 linearToSrgb(vec3 c) {
    return mix(
        12.92 * c,
        1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055,
        step(0.0031308, c)
    );
}

void main() {
    int n = max(u_totalSamples, 1);

    // Divide running sum → average radiance
    vec3 hdr = texture(u_tex, v_uv).rgb / float(n);

    // Exposure
    hdr *= u_exposure;

    // Tonemapping + gamma
    vec3 ldr = linearToSrgb(aces(hdr));

    fragColor = vec4(ldr, 1.0);
}
