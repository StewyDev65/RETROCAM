#version 430 core

// Generates a single triangle covering the entire clip space from
// gl_VertexID alone — no vertex buffer required.
// Draw with: glDrawArrays(GL_TRIANGLES, 0, 3)

out vec2 v_uv;

void main() {
    // Map vertex IDs 0,1,2 to positions that cover [-1,1]x[-1,1]
    float x = float((gl_VertexID & 1) << 2) - 1.0; // -1, 3, -1
    float y = float((gl_VertexID & 2) << 1) - 1.0; // -1, -1, 3

    v_uv = vec2(x, y) * 0.5 + 0.5;
    gl_Position = vec4(x, y, 0.0, 1.0);
}
