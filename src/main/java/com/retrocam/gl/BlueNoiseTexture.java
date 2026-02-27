package com.retrocam.gl;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Random;

import org.lwjgl.system.MemoryUtil;

import static org.lwjgl.opengl.GL11.*;

/**
 * Generates and uploads a 64×64 RGBA8 tileable blue-noise texture using the
 * void-and-cluster algorithm (Ulichney 1993).
 *
 * <p>Each channel is independently generated, giving 4 uncorrelated blue-noise
 * sequences in a single texture lookup.  Channels BA are R and G with a
 * toroidal spatial offset rather than a full extra generation pass.</p>
 *
 * <p>Must be sampled with GL_NEAREST — bilinear interpolation destroys the
 * blue-noise spectral distribution.  Tiling is seamless (GL_REPEAT) because
 * the energy function uses toroidal distance throughout.</p>
 *
 * <p>Generation is a one-time startup cost (~300–600 ms on typical hardware).
 * The algorithm complexity is O(N²) for N=64×64=4096 — acceptable given it
 * only runs once per session.</p>
 */
public final class BlueNoiseTexture {

    public static final int SIZE = 64;

    // Gaussian kernel parameters — sigma=1.9 is standard for void-and-cluster
    private static final float SIGMA = 1.9f;
    private static final int   KR    = 5;           // half-radius (covers ~3σ)
    private static final int   KW    = 2 * KR + 1;  // kernel width = 11

    private final int texId;

    // ── Construction ──────────────────────────────────────────────────────────

    public BlueNoiseTexture() {
        long t0 = System.currentTimeMillis();

        float[] chR = generateChannel(0x3A4F1C9BL);
        float[] chG = generateChannel(0x7E2D8A61L);

        // Derive channels B and A by toroidal spatial offset to avoid a second
        // full generation pass while still keeping them decorrelated from R/G.
        int N      = SIZE * SIZE;
        int offset = N / 3;

        ByteBuffer pixels = MemoryUtil.memAlloc(N * 4);
        for (int i = 0; i < N; i++) {
            int j = (i + offset) % N;
            pixels.put((byte) Math.round(chR[i] * 255f));
            pixels.put((byte) Math.round(chG[i] * 255f));
            pixels.put((byte) Math.round(chR[j] * 255f));
            pixels.put((byte) Math.round(chG[j] * 255f));
        }
        pixels.flip();

        texId = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, texId);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, SIZE, SIZE,
                     0, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S,       GL_REPEAT);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T,       GL_REPEAT);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER,   GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER,   GL_NEAREST);
        glBindTexture(GL_TEXTURE_2D, 0);

        MemoryUtil.memFree(pixels);

        System.out.printf("[BlueNoiseTexture] Generated in %d ms.%n",
                          System.currentTimeMillis() - t0);
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public int  texId()   { return texId; }

    public void destroy() {
        if (texId != 0) glDeleteTextures(texId);
    }

    // ── Void-and-cluster generation ───────────────────────────────────────────
    //
    // The algorithm produces a rank map: each pixel receives a rank in [0, N)
    // that describes its position in the "canonical" blue-noise insertion order.
    // Normalising by N gives the final [0,1] blue-noise values.
    //
    // Three phases:
    //   Phase 1: remove ones from the initial random pattern in cluster order,
    //            assigning ranks initial-1 → 0.
    //   Phase 2: from an empty grid, re-insert ones in void order,
    //            assigning ranks 0 → initial-1.  This is the canonical seed.
    //   Phase 3: continue inserting into voids, ranks initial → N-1.

    private static float[] generateChannel(long seed) {
        int    N      = SIZE * SIZE;
        Random rng    = new Random(seed);
        float[] kernel = buildKernel();

        boolean[] mask   = new boolean[N];
        float[]   energy = new float[N];
        int[]     ranks  = new int[N];
        Arrays.fill(ranks, -1);

        // Seed: randomly place N/10 ones
        int initial = N / 10;
        for (int placed = 0; placed < initial; ) {
            int pos = rng.nextInt(N);
            if (!mask[pos]) {
                mask[pos] = true;
                updateEnergy(energy, kernel, pos, +1f);
                placed++;
            }
        }

        // Phase 1: remove from clusters
        for (int r = initial - 1; r >= 0; r--) {
            int cluster = findExtreme(energy, mask, true);
            mask[cluster] = false;
            updateEnergy(energy, kernel, cluster, -1f);
            ranks[cluster] = r;
        }

        // Reset for phase 2
        Arrays.fill(mask,   false);
        Arrays.fill(energy, 0f);

        // Phase 2: canonical re-seed
        for (int r = 0; r < initial; r++) {
            int void_ = findExtreme(energy, mask, false);
            mask[void_] = true;
            updateEnergy(energy, kernel, void_, +1f);
            ranks[void_] = r;
        }

        // Phase 3: fill remaining voids
        for (int r = initial; r < N; r++) {
            int void_ = findExtreme(energy, mask, false);
            mask[void_] = true;
            updateEnergy(energy, kernel, void_, +1f);
            ranks[void_] = r;
        }

        float[] result = new float[N];
        for (int i = 0; i < N; i++)
            result[i] = (ranks[i] + 0.5f) / N;
        return result;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Pre-compute the (KW×KW) toroidal Gaussian kernel. */
    private static float[] buildKernel() {
        float[] k   = new float[KW * KW];
        float   inv = 1f / (2f * SIGMA * SIGMA);
        for (int ky = -KR; ky <= KR; ky++)
            for (int kx = -KR; kx <= KR; kx++)
                k[(ky + KR) * KW + (kx + KR)] =
                    (float) Math.exp(-(kx * kx + ky * ky) * inv);
        return k;
    }

    /**
     * Incrementally add or subtract kernel energy centred on {@code pos}.
     * Toroidal addressing ensures the texture tiles seamlessly.
     */
    private static void updateEnergy(float[] energy, float[] kernel,
                                     int pos, float sign) {
        int px = pos % SIZE, py = pos / SIZE;
        for (int ky = -KR; ky <= KR; ky++) {
            int ny = ((py + ky) % SIZE + SIZE) % SIZE;
            for (int kx = -KR; kx <= KR; kx++) {
                int nx = ((px + kx) % SIZE + SIZE) % SIZE;
                energy[ny * SIZE + nx] +=
                    sign * kernel[(ky + KR) * KW + (kx + KR)];
            }
        }
    }

    /**
     * Find the index of the highest-energy set pixel ({@code findCluster=true})
     * or the lowest-energy unset pixel ({@code findCluster=false}).
     */
    private static int findExtreme(float[] energy, boolean[] mask,
                                   boolean findCluster) {
        int   best    = -1;
        float bestVal = findCluster ? -Float.MAX_VALUE : Float.MAX_VALUE;
        for (int i = 0; i < energy.length; i++) {
            if (mask[i] != findCluster) continue;
            if (findCluster ? energy[i] > bestVal : energy[i] < bestVal) {
                bestVal = energy[i];
                best    = i;
            }
        }
        return best;
    }
}