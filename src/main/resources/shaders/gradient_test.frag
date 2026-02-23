#version 430 core

// Phase 1 test shader — confirms the GLFW + OpenGL 4.3 pipeline is working.
// Outputs a retro-styled animated gradient. Replace with the path tracer
// accumulation display in Phase 3.

in  vec2 v_uv;
out vec4 fragColor;

uniform float u_time;
uniform vec2  u_resolution;

// Simple PCG-style hash for subtle noise
float hash(vec2 p) {
    p = fract(p * vec2(443.897, 441.423));
    p += dot(p, p + 19.19);
    return fract(p.x * p.y);
}

void main() {
    vec2 uv = v_uv;
    float aspect = u_resolution.x / u_resolution.y;

    // Slow animated gradient: warm amber → deep purple
    vec3 warm   = vec3(0.95, 0.70, 0.20);
    vec3 purple = vec3(0.10, 0.04, 0.22);
    vec3 teal   = vec3(0.05, 0.35, 0.40);

    float t = uv.y + sin(uv.x * 3.0 + u_time * 0.4) * 0.08;
    vec3  col = mix(purple, warm,  smoothstep(0.0, 0.6, t));
    col       = mix(col,    teal,  smoothstep(0.5, 1.0, t));

    // Scanline hint — very subtle, like a CRT
    float scanline = 0.97 + 0.03 * sin(uv.y * u_resolution.y * 3.14159);
    col *= scanline;

    // Vignette
    vec2  centered = uv - 0.5;
    float vignette = 1.0 - dot(centered, centered) * 2.2;
    col *= clamp(vignette, 0.0, 1.0);

    // Subtle film grain
    col += (hash(uv + u_time) - 0.5) * 0.025;

    fragColor = vec4(col, 1.0);
}
