package com.retrocam.gl;

import org.lwjgl.opengl.GL43;
import org.lwjgl.system.MemoryStack;

import java.io.IOException;
import java.io.InputStream;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.lwjgl.opengl.GL43.*;

/**
 * Wraps an OpenGL shader program (vertex+fragment or compute).
 * Sources are loaded from the classpath (src/main/resources).
 */
public final class ShaderProgram {

    private final int id;
    private final Map<String, Integer> uniformCache = new HashMap<>();

    // ── Construction ────────────────────────────────────────────────────────

    /** Vertex + fragment program. */
    public static ShaderProgram createRender(String vertPath, String fragPath) {
        int vert = compileShader(GL_VERTEX_SHADER,   loadSource(vertPath));
        int frag = compileShader(GL_FRAGMENT_SHADER, loadSource(fragPath));
        return new ShaderProgram(link(vert, frag));
    }

    /** Single compute shader program. */
    public static ShaderProgram createCompute(String compPath) {
        int comp = compileShader(GL_COMPUTE_SHADER, loadSource(compPath));
        return new ShaderProgram(link(comp));
    }

    private ShaderProgram(int programId) {
        this.id = programId;
    }

    // ── Usage ────────────────────────────────────────────────────────────────

    public void bind()   { glUseProgram(id); }
    public void unbind() { glUseProgram(0);  }

    public int id() { return id; }

    // ── Uniform setters ──────────────────────────────────────────────────────

    public void setInt(String name, int v)       { glUniform1i(loc(name), v); }
    public void setFloat(String name, float v)   { glUniform1f(loc(name), v); }
    public void setFloat2(String name, float x, float y) {
        glUniform2f(loc(name), x, y);
    }
    public void setFloat3(String name, float x, float y, float z) {
        glUniform3f(loc(name), x, y, z);
    }
    public void setFloat4(String name, float x, float y, float z, float w) {
        glUniform4f(loc(name), x, y, z, w);
    }
    public void setMat3(String name, float[] m) {
        glUniformMatrix3fv(loc(name), false, m);
    }
    public void setMat4(String name, float[] m) {
        glUniformMatrix4fv(loc(name), false, m);
    }

    // ── Cleanup ──────────────────────────────────────────────────────────────

    public void destroy() { glDeleteProgram(id); }

    // ── Internal ─────────────────────────────────────────────────────────────

    private int loc(String name) {
        return uniformCache.computeIfAbsent(name,
            n -> glGetUniformLocation(id, n));
    }

    private static String loadSource(String path) {
        try (InputStream is = ShaderProgram.class.getResourceAsStream(path)) {
            if (is == null) throw new RuntimeException("Shader not found: " + path);
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load shader: " + path, e);
        }
    }

    private static int compileShader(int type, String source) {
        int shader = glCreateShader(type);
        glShaderSource(shader, source);
        glCompileShader(shader);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer status = stack.mallocInt(1);
            glGetShaderiv(shader, GL_COMPILE_STATUS, status);
            if (status.get(0) == GL_FALSE) {
                String log = glGetShaderInfoLog(shader);
                glDeleteShader(shader);
                throw new RuntimeException("Shader compile error:\n" + log);
            }
        }
        return shader;
    }

    private static int link(int... shaders) {
        int program = glCreateProgram();
        for (int s : shaders) glAttachShader(program, s);
        glLinkProgram(program);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer status = stack.mallocInt(1);
            glGetProgramiv(program, GL_LINK_STATUS, status);
            if (status.get(0) == GL_FALSE) {
                String log = glGetProgramInfoLog(program);
                glDeleteProgram(program);
                throw new RuntimeException("Program link error:\n" + log);
            }
        }
        for (int s : shaders) glDeleteShader(s);
        return program;
    }
}
