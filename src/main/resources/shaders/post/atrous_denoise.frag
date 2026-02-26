#version 430 core
// ─────────────────────────────────────────────────────────────────────────────
// atrous_denoise.frag  —  SVGF-style à-trous edge-stopping denoiser
//
// Three key improvements over a basic à-trous filter:
//
//  1. Albedo demodulation (SVGF irradiance separation):
//     Filter colour/albedo (irradiance) instead of raw colour. This prevents
//     the filter from blurring across material colour boundaries that share
//     similar luminance values, eliminating the "cartoony painted" look.
//
//  2. Log-luminance colour edge-stopping:
//     Operate in log-luma space so sigma_colour has consistent sensitivity
//     across HDR brightness levels — prevents over-blurring in bright regions.
//
//  3. Variance-adaptive sigma (SVGF):
//     Per-pixel variance = E[L²] − E[L]² from the accumulated luma² buffer.
//     High variance (noisy) → wider filter.  Low variance (converged) → sharp.
//     This decouples noise suppression from edge preservation.
//
// References:
//   Dammertz et al. "Edge-Avoiding À-Trous Wavelet Transform", HPG 2010.
//   Schied et al. "Spatiotemporal Variance-Guided Filtering", HPG 2017.
// ─────────────────────────────────────────────────────────────────────────────

in  vec2 v_uv;
out vec4 fragColor;

uniform sampler2D u_tex;          // normalised linear-HDR colour (output of p00_normalize)
uniform sampler2D u_gBuffer;      // rgba16f: (world-normal.xyz, primary-hit depth)
uniform sampler2D u_gAlbedo;      // rgba16f: first-hit albedo.rgb for irradiance demodulation
uniform sampler2D u_varianceTex;  // r32f: accumulated sum(luma²) — pre-exposure, per sample
uniform int       u_stepWidth;    // sparse-kernel step: 1, 2, 4, 8, 16
uniform float     u_sigmaColor;   // variance multiplier: scales how aggressively noise drives blurring
uniform float     u_sigmaNormal;  // normal power exponent (higher = sharper normal edges)
uniform float     u_sigmaDepth;   // relative depth std-dev (lower = sharper depth edges)
uniform vec2      u_texelSize;    // vec2(1/W, 1/H)
uniform int       u_sampleCount;  // total accumulated samples N (for variance normalisation)
uniform float     u_exposure;     // EV multiplier from p00 (to reconstruct pre-exposure luma)

// B3-spline 5-tap kernel weights (Dammertz et al. 2010)
const float h[5] = float[5](1.0/16.0, 1.0/4.0, 3.0/8.0, 1.0/4.0, 1.0/16.0);

float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }

// Log-luminance: compresses HDR range so sigma has consistent sensitivity
// at all brightness levels. Avoids over-blurring in bright regions.
float logLuma(vec3 c) { return log(luma(c) + 1e-3); }

void main() {
    vec4  cg     = texture(u_gBuffer, v_uv);
    vec3  cNorm  = cg.xyz;
    float cDepth = cg.w;
    vec3  cCol   = texture(u_tex, v_uv).rgb;

    // Sky / miss pixels: no geometry, pass through unchanged.
    if (cDepth > 9000.0) { fragColor = vec4(cCol, 1.0); return; }

    // ── Albedo demodulation ───────────────────────────────────────────────────
    vec3 cAlbedo   = texture(u_gAlbedo, v_uv).rgb;
    bool hasAlbedo = luma(cAlbedo) > 0.02;
    // Irradiance = colour / albedo. Clamped albedo prevents division by zero.
    vec3 cIrrad = hasAlbedo ? cCol / max(cAlbedo, vec3(0.01)) : cCol;

    // ── Per-pixel variance estimation ─────────────────────────────────────────
    // variance = E[L²] − E[L]²
    // E[L²]  = sumLumaSq / N  (from variance accumulation buffer, pre-exposure)
    // E[L]   = luma(cCol / exposure)  (normalised mean, undo the exposure EV)
    float N          = float(max(u_sampleCount, 1));
    float sumLumaSq  = texture(u_varianceTex, v_uv).r;
    float meanLuma   = luma(cCol / max(u_exposure, 1e-4));
    float variance   = max(0.0, sumLumaSq / N - meanLuma * meanLuma);

    // Variance-adaptive denominator for colour edge-stopping.
    // u_sigmaColor acts as a tuning multiplier: higher = more blur at a given noise level.
    float varDenom = u_sigmaColor * u_sigmaColor * variance + 1e-6;

    // ── À-trous filter on irradiance ──────────────────────────────────────────
    vec3  irradSum  = vec3(0.0);
    float wSum      = 0.0;
    float cLogL     = logLuma(cIrrad);

    for (int j = -2; j <= 2; j++) {
        for (int i = -2; i <= 2; i++) {
            vec2  off   = vec2(float(i * u_stepWidth), float(j * u_stepWidth)) * u_texelSize;
            vec2  uv2   = v_uv + off;

            vec4  g2    = texture(u_gBuffer,  uv2);
            vec3  norm2 = g2.xyz;
            float dep2  = g2.w;

            vec3 col2  = texture(u_tex,    uv2).rgb;
            vec3 alb2  = texture(u_gAlbedo, uv2).rgb;
            vec3 irr2  = (luma(alb2) > 0.02) ? col2 / max(alb2, vec3(0.01)) : col2;

            float hw = h[i + 2] * h[j + 2];

            // Normal edge-stopping: pow(dot, exponent) — high exponent = sharp edges
            float wn = pow(max(0.0, dot(cNorm, norm2)), u_sigmaNormal);

            // Depth edge-stopping: relative depth difference
            float dd = (cDepth - dep2) / (cDepth + 0.001);
            float wd = exp(-(dd * dd) / (u_sigmaDepth * u_sigmaDepth + 1e-6));

            // Log-luminance colour edge-stopping with variance-adaptive width.
            // Operates on irradiance (albedo-demodulated), in log space.
            float ld = cLogL - logLuma(irr2);
            float wc = exp(-(ld * ld) / varDenom);

            float w   = hw * wn * wd * wc;
            irradSum += irr2 * w;
            wSum     += w;
        }
    }

    vec3 irradResult = (wSum > 1e-6) ? irradSum / wSum : cIrrad;

    // Re-modulate: multiply filtered irradiance by centre pixel's albedo.
    // Pixels without albedo data (sky, glass first-hits) pass through directly.
    vec3 result = hasAlbedo ? irradResult * cAlbedo : irradResult;
    fragColor   = vec4(result, 1.0);
}