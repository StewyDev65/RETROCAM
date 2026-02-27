package com.retrocam.post;

import com.retrocam.core.RenderSettings;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.GL_TEXTURE_BASE_LEVEL;
import static org.lwjgl.opengl.GL12.GL_TEXTURE_MAX_LEVEL;
import static org.lwjgl.opengl.GL30.*;

/**
 * Denoises the path-traced image using Intel Open Image Denoise via an
 * external subprocess. Communication uses memory-mapped files for efficient
 * IPC without JNA/JNI overhead.
 *
 * <p>OIDN expects RGB float3 buffers. The path tracer outputs RGBA16F/RGBA32F,
 * so we read back as GL_RGB/GL_FLOAT to strip the alpha channel and promote
 * to 32-bit floats in a single glGetTexImage call.</p>
 *
 * <p>Integration point: called by {@link PostProcessStack#runChain} after
 * normalization and (optionally) after à-trous, but before VHS effects.</p>
 *
 * <h3>Thread safety</h3>
 * All methods must be called from the GL thread. The subprocess blocks the
 * calling thread until denoising completes (typically 50-200ms at 854×480).
 */
public final class OIDNDenoiser {

    private static final String[] QUALITY_NAMES = {"fast", "balanced", "high"};

    private final String executablePath;
    private int width, height;
    private long bufferSize;

    // Memory-mapped file resources
    private Path colorPath, albedoPath, normalPath, outputPath;
    private RandomAccessFile colorRaf, albedoRaf, normalRaf, outputRaf;
    private MappedByteBuffer colorMap, albedoMap, normalMap, outputMap;

    // GL texture for denoised result
    private int denoisedTexture;

    // Status
    private boolean initialized = false;
    private String  lastError   = "";
    private long    lastDenoiseMs = 0;

    // Caching — avoid running every sample
    private int lastDenoisedAtSpp = -1;
    private int cachedTexture     = 0;      // 0 = no cached result

    // ── Construction ───────────────────────────────────────────────────────

    public OIDNDenoiser(String executablePath) {
        this.executablePath = executablePath;
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────

    /**
     * Allocates memory-mapped files and the output texture for the given
     * resolution. Safe to call multiple times (disposes previous state).
     */
    public void initialize(int width, int height) {
        dispose();

        this.width  = width;
        this.height = height;
        // RGB float3: 3 floats per pixel, 4 bytes per float
        this.bufferSize = (long) width * height * 3 * Float.BYTES;

        try {
            colorPath  = Files.createTempFile("oidn_color_",  ".raw");
            albedoPath = Files.createTempFile("oidn_albedo_", ".raw");
            normalPath = Files.createTempFile("oidn_normal_", ".raw");
            outputPath = Files.createTempFile("oidn_output_", ".raw");

            colorRaf  = openAndMap(colorPath);
            albedoRaf = openAndMap(albedoPath);
            normalRaf = openAndMap(normalPath);
            outputRaf = openAndMap(outputPath);

            colorMap  = mapBuffer(colorRaf);
            albedoMap = mapBuffer(albedoRaf);
            normalMap = mapBuffer(normalRaf);
            outputMap = mapBuffer(outputRaf);

            denoisedTexture = createTexture(width, height);
            initialized = true;
            lastError = "";

            System.out.println("[OIDNDenoiser] Initialized: " + width + "x" + height
                + " (" + (bufferSize / 1024) + " KB per buffer)");
        } catch (IOException e) {
            lastError = "Init failed: " + e.getMessage();
            System.err.println("[OIDNDenoiser] " + lastError);
            dispose();
        }
    }

    /**
     * Returns the cached denoised texture if OIDN shouldn't run this frame,
     * or runs a fresh denoise if the interval/trigger conditions are met.
     *
     * @param totalSamples current accumulated sample count
     * @return texture ID to use, or 0 if OIDN should be skipped entirely this frame
     */
    public int denoiseIfNeeded(int colorTexId, int gBufferTexId, int gAlbedoTexId,
                            RenderSettings settings, int totalSamples) {
        if (!initialized || !settings.oidnEnabled) {
            cachedTexture = 0;
            lastDenoisedAtSpp = -1;
            return colorTexId;
        }

        // SPP range check
        boolean sppOk = (settings.oidnMinSpp == 0 || totalSamples >= settings.oidnMinSpp)
                    && (settings.oidnMaxSpp == 0 || totalSamples <= settings.oidnMaxSpp);
        if (!sppOk) return colorTexId;

        // Decide whether to run this frame
        boolean shouldRun = false;

        if (settings.oidnDenoiseNow) {
            shouldRun = true;
            settings.oidnDenoiseNow = false;  // consume the one-shot trigger
        } else if (settings.oidnInterval > 0
                && totalSamples > 0
                && totalSamples != lastDenoisedAtSpp
                && totalSamples % settings.oidnInterval == 0) {
            shouldRun = true;
        }

        if (shouldRun) {
            int result = denoise(colorTexId, gBufferTexId, gAlbedoTexId, settings);
            if (result != colorTexId) {
                cachedTexture = result;
                lastDenoisedAtSpp = totalSamples;
            }
            return result;
        }

        // Use cached result if we have one
        if (cachedTexture != 0) return cachedTexture;

        return colorTexId;
    }

    /**
     * Invalidates the cache (call on accumulation reset).
     */
    public void invalidateCache() {
        cachedTexture = 0;
        lastDenoisedAtSpp = -1;
    }

    /**
     * Runs the full denoise pipeline: reads GL textures → invokes subprocess
     * → uploads result to a GL texture.
     *
     * @param colorTexId   normalized HDR color buffer (after p00_normalize)
     * @param gBufferTexId G-buffer (xyz=world normal, w=depth) — RGBA16F
     * @param gAlbedoTexId first-hit albedo — RGBA16F
     * @param settings     current render settings
     * @return GL texture ID of denoised result, or colorTexId if skipped/failed
     */
    public int denoise(int colorTexId, int gBufferTexId, int gAlbedoTexId,
                       RenderSettings settings) {
        if (!initialized || !settings.oidnEnabled) return colorTexId;

        try {
            long t0 = System.nanoTime();

            // Read color buffer from GL → mmap
            readTextureRGB(colorTexId, colorMap);

            // Read auxiliary buffers if enabled
            if (settings.oidnUseAlbedo && gAlbedoTexId != 0) {
                readTextureRGB(gAlbedoTexId, albedoMap);
            }
            if (settings.oidnUseNormals && gBufferTexId != 0) {
                readTextureRGB(gBufferTexId, normalMap);
            }

            // Force flush mapped buffers before subprocess reads them
            colorMap.force();
            if (settings.oidnUseAlbedo)  albedoMap.force();
            if (settings.oidnUseNormals) normalMap.force();

            // Build and run subprocess
            ProcessBuilder pb = new ProcessBuilder();
            pb.command(
                executablePath,
                String.valueOf(width),
                String.valueOf(height),
                colorPath.toAbsolutePath().toString(),
                outputPath.toAbsolutePath().toString(),
                QUALITY_NAMES[Math.min(settings.oidnQuality, 2)]
            );

            if (settings.oidnUseAlbedo && gAlbedoTexId != 0) {
                pb.command().add("--albedo");
                pb.command().add(albedoPath.toAbsolutePath().toString());
            }
            if (settings.oidnUseNormals && gBufferTexId != 0) {
                pb.command().add("--normal");
                pb.command().add(normalPath.toAbsolutePath().toString());
            }

            pb.redirectErrorStream(true);
            Process process = pb.start();
            byte[] processOutput = process.getInputStream().readAllBytes();
            int exitCode = process.waitFor();

            if (exitCode != 0) {
                lastError = "Exit " + exitCode + ": " + new String(processOutput).trim();
                System.err.println("[OIDNDenoiser] " + lastError);
                return colorTexId;
            }

            // Upload denoised result to GL texture
            outputMap.position(0);
            glBindTexture(GL_TEXTURE_2D, denoisedTexture);
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height,
                            GL_RGB, GL_FLOAT, outputMap);
            glBindTexture(GL_TEXTURE_2D, 0);

            lastDenoiseMs = (System.nanoTime() - t0) / 1_000_000;
            lastError = "";
            return denoisedTexture;

        } catch (Exception e) {
            lastError = e.getMessage();
            System.err.println("[OIDNDenoiser] Denoise failed: " + lastError);
            return colorTexId;
        }
    }

    // ── GL helpers ─────────────────────────────────────────────────────────

    /**
     * Reads an RGBA texture as RGB float3 into a memory-mapped buffer.
     * GL handles the RGBA→RGB conversion and 16F→32F promotion automatically.
     */
    private void readTextureRGB(int textureId, MappedByteBuffer buffer) {
        buffer.position(0);
        glBindTexture(GL_TEXTURE_2D, textureId);
        glGetTexImage(GL_TEXTURE_2D, 0, GL_RGB, GL_FLOAT, buffer);
        glBindTexture(GL_TEXTURE_2D, 0);
    }

    private static int createTexture(int w, int h) {
        int tex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, tex);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB32F, w, h, 0,
                     GL_RGB, GL_FLOAT, (ByteBuffer) null);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_BASE_LEVEL, 0);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAX_LEVEL, 0);
        glBindTexture(GL_TEXTURE_2D, 0);
        return tex;
    }

    // ── File mapping helpers ───────────────────────────────────────────────

    private RandomAccessFile openAndMap(Path path) throws IOException {
        RandomAccessFile raf = new RandomAccessFile(path.toFile(), "rw");
        raf.setLength(bufferSize);
        return raf;
    }

    private MappedByteBuffer mapBuffer(RandomAccessFile raf) throws IOException {
        MappedByteBuffer map = raf.getChannel()
            .map(FileChannel.MapMode.READ_WRITE, 0, bufferSize);
        map.order(ByteOrder.nativeOrder());
        return map;
    }

    // ── Status ─────────────────────────────────────────────────────────────

    public boolean isInitialized()  { return initialized; }
    public String  getLastError()   { return lastError; }
    public long    getLastTimeMs()  { return lastDenoiseMs; }

    // ── Cleanup ────────────────────────────────────────────────────────────

    public void dispose() {
        initialized = false;

        // Close file handles (this also releases the mappings on Windows)
        closeQuietly(colorRaf);
        closeQuietly(albedoRaf);
        closeQuietly(normalRaf);
        closeQuietly(outputRaf);
        colorRaf = albedoRaf = normalRaf = outputRaf = null;
        colorMap = albedoMap = normalMap = outputMap = null;

        // Delete temp files
        deleteQuietly(colorPath);
        deleteQuietly(albedoPath);
        deleteQuietly(normalPath);
        deleteQuietly(outputPath);
        colorPath = albedoPath = normalPath = outputPath = null;

        if (denoisedTexture != 0) {
            glDeleteTextures(denoisedTexture);
            denoisedTexture = 0;
        }
    }

    private static void closeQuietly(RandomAccessFile raf) {
        if (raf != null) {
            try { raf.close(); } catch (IOException ignored) {}
        }
    }

    private static void deleteQuietly(Path path) {
        if (path != null) {
            try { Files.deleteIfExists(path); } catch (IOException ignored) {}
        }
    }
}