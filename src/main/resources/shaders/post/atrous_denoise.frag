#version 430 core

in  vec2 v_uv;
out vec4 fragColor;

uniform sampler2D u_tex;          // HDR colour input (already normalised by p00)
uniform sampler2D u_gBuffer;      // rgba16f: (world-normal.xyz, primary-hit depth)
uniform int       u_stepWidth;    // sparse-kernel step: 1, 2, 4, 8, 16
uniform float     u_sigmaColor;   // colour edge-stopping std-dev  (e.g. 0.08)
uniform float     u_sigmaNormal;  // normal power exponent          (e.g. 32.0)
uniform float     u_sigmaDepth;   // depth relative std-dev         (e.g. 0.5)
uniform vec2      u_texelSize;    // vec2(1/W, 1/H)

// B3-spline 5-tap kernel weights (Dammertz et al. 2010)
const float h[5] = float[5](1.0/16.0, 1.0/4.0, 3.0/8.0, 1.0/4.0, 1.0/16.0);

float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }

void main() {
    vec4  cg     = texture(u_gBuffer, v_uv);
    vec3  cNorm  = cg.xyz;
    float cDepth = cg.w;
    vec3  cCol   = texture(u_tex, v_uv).rgb;

    // Sky / miss pixels: no geometry, pass through unchanged
    if (cDepth > 9000.0) { fragColor = vec4(cCol, 1.0); return; }

    vec3  colSum = vec3(0.0);
    float wSum   = 0.0;

    for (int j = -2; j <= 2; j++) {
        for (int i = -2; i <= 2; i++) {
            vec2  off  = vec2(float(i * u_stepWidth), float(j * u_stepWidth)) * u_texelSize;
            vec2  uv2  = v_uv + off;

            vec4  g2    = texture(u_gBuffer, uv2);
            vec3  norm2 = g2.xyz;
            float dep2  = g2.w;
            vec3  col2  = texture(u_tex, uv2).rgb;

            // Kernel weight
            float hw = h[i + 2] * h[j + 2];

            // Normal edge-stopping: pow(max(0, dot), exponent)
            float wn = pow(max(0.0, dot(cNorm, norm2)), u_sigmaNormal);

            // Depth edge-stopping: relative difference
            float dd  = (cDepth - dep2) / (cDepth + 0.001);
            float wd  = exp(-(dd * dd) / (u_sigmaDepth * u_sigmaDepth + 1e-6));

            // Colour edge-stopping: luminance difference
            float ld  = luma(cCol) - luma(col2);
            float wc  = exp(-(ld * ld) / (u_sigmaColor * u_sigmaColor + 1e-6));

            float w   = hw * wn * wd * wc;
            colSum   += col2 * w;
            wSum     += w;
        }
    }

    vec3 result = (wSum > 1e-6) ? colSum / wSum : cCol;
    fragColor   = vec4(result, 1.0);
}