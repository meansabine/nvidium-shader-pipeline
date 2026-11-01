#version 460 core

// Depth-only shadow fragment shader
// Leaves/cutout surfaces can sample diffuse alpha here and discard if alpha < 0.1

void main() {
    // Empty depth write (hardware writes gl_FragCoord.z to shadow depth texture automatically)
}
