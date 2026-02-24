package com.retrocam.scene;

import com.retrocam.gl.SSBO;
import org.lwjgl.system.MemoryUtil;

import java.nio.IntBuffer;

import static org.lwjgl.opengl.GL43.*;

/**
 * GPU spatial hash photon map for SPPM caustics.
 *
 * Manages four SSBOs:
 *   Binding 4 — photon data  : Photon[MAX_PHOTONS]  (48 bytes each, std430)
 *   Binding 5 — hash heads   : int[HASH_SIZE]        (-1 = empty cell)
 *   Binding 6 — photon next  : int[MAX_PHOTONS]      (linked-list chain)
 *   Binding 7 — atomic counter: int[1]               (photons written so far)
 *
 * Insertion algorithm (lock-free linked list):
 *   uint photonIdx = atomicAdd(counter[0], 1);
 *   int cellIdx = cellHash(photon.position, searchRadius);
 *   photonNext[photonIdx] = atomicExchange(hashHeads[cellIdx], int(photonIdx));
 *
 * Struct layout (std430, matches photon_trace.comp / photon_gather.comp):
 *   struct Photon {
 *       vec3 position; float pad0;   // 16 bytes
 *       vec3 power;    float pad1;   // 16 bytes  (RGB watts)
 *       vec3 dir;      float pad2;   // 16 bytes  (incident direction)
 *   };                               // 48 bytes total
 */
public final class PhotonMap {

    // ── SSBO binding points ───────────────────────────────────────────────────
    public static final int BINDING_PHOTONS      = 4;
    public static final int BINDING_HASH_HEADS   = 5;
    public static final int BINDING_PHOTON_NEXT  = 6;
    public static final int BINDING_COUNTER      = 7;

    // ── Capacity constants ────────────────────────────────────────────────────
    /** 1 << 22 ≈ 4.2 M hash cells as specified. */
    public static final int HASH_SIZE       = 1 << 22;

    /** Maximum photons that can be stored in a single iteration. */
    public static final int MAX_PHOTONS     = 2_000_000;

    /** Floats per photon in the SSBO (3 × vec3+pad = 12 floats = 48 bytes). */
    public static final int FLOATS_PER_PHOTON = 12;

    // ── GPU buffers ───────────────────────────────────────────────────────────
    private final SSBO photonSSBO;
    private final SSBO hashHeadsSSBO;
    private final SSBO photonNextSSBO;
    private final SSBO counterSSBO;

    // ── Construction ──────────────────────────────────────────────────────────

    public PhotonMap() {
        // Photon data  : 12 floats × 4 bytes × 2 M  = 96 MB
        photonSSBO     = SSBO.allocate(MAX_PHOTONS * FLOATS_PER_PHOTON * Float.BYTES);

        // Hash heads   : 4 bytes × 4 M              = 16 MB
        hashHeadsSSBO  = SSBO.allocate(HASH_SIZE * Integer.BYTES);

        // Photon next  : 4 bytes × 2 M              =  8 MB
        photonNextSSBO = SSBO.allocate(MAX_PHOTONS * Integer.BYTES);

        // Counter      : 4 bytes                    =  ~0 MB
        counterSSBO    = SSBO.allocate(Integer.BYTES);

        long totalMB = ((long) MAX_PHOTONS * FLOATS_PER_PHOTON * Float.BYTES
                      + (long) HASH_SIZE * Integer.BYTES
                      + (long) MAX_PHOTONS * Integer.BYTES
                      + Integer.BYTES) / (1024 * 1024);
        System.out.printf("[PhotonMap] Allocated %d MB  (%d photons, %d hash cells)%n",
                          totalMB, MAX_PHOTONS, HASH_SIZE);
    }

    // ── Per-frame clear ───────────────────────────────────────────────────────

    /**
     * Resets the hash heads to −1 (empty) and the atomic counter to 0.
     * Must be called before each photon-trace dispatch.
     */
    public void clear() {
        IntBuffer minusOne = MemoryUtil.memAllocInt(1);
        IntBuffer zero     = MemoryUtil.memAllocInt(1);
        try {
            minusOne.put(0, -1);
            zero.put(0, 0);

            // Fill hash heads with -1 (0xFFFFFFFF)
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, hashHeadsSSBO.id());
            glClearBufferData(GL_SHADER_STORAGE_BUFFER,
                              GL_R32I, GL_RED_INTEGER, GL_INT, minusOne);

            // Reset photon counter to 0
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, counterSSBO.id());
            glClearBufferData(GL_SHADER_STORAGE_BUFFER,
                              GL_R32I, GL_RED_INTEGER, GL_INT, zero);

            glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);
        } finally {
            MemoryUtil.memFree(minusOne);
            MemoryUtil.memFree(zero);
        }
    }

    // ── Bind / unbind ─────────────────────────────────────────────────────────

    public void bind() {
        photonSSBO    .bind(BINDING_PHOTONS);
        hashHeadsSSBO .bind(BINDING_HASH_HEADS);
        photonNextSSBO.bind(BINDING_PHOTON_NEXT);
        counterSSBO   .bind(BINDING_COUNTER);
    }

    public void unbind() {
        photonSSBO    .unbind(BINDING_PHOTONS);
        hashHeadsSSBO .unbind(BINDING_HASH_HEADS);
        photonNextSSBO.unbind(BINDING_PHOTON_NEXT);
        counterSSBO   .unbind(BINDING_COUNTER);
    }

    // ── Cleanup ───────────────────────────────────────────────────────────────

    public void destroy() {
        photonSSBO    .destroy();
        hashHeadsSSBO .destroy();
        photonNextSSBO.destroy();
        counterSSBO   .destroy();
    }
}