package com.retrocam.post;

import com.retrocam.gl.ShaderProgram;

/**
 * A single post-process pass: one named fragment shader loaded against the
 * standard {@code fullscreen.vert}.  All rendering is delegated to
 * {@link PostProcessStack}, which owns the FBO chain and VAO.
 */
public final class PostProcessPass {

    private final String        name;
    private final ShaderProgram shader;

    public PostProcessPass(String name, String fragClasspath) {
        this.name   = name;
        this.shader = ShaderProgram.createRender(
                "/shaders/fullscreen.vert", fragClasspath);
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public String        name()   { return name;   }
    public ShaderProgram shader() { return shader; }

    public void destroy() { shader.destroy(); }
}