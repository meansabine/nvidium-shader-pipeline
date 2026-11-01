package net.nvidium.shaders.pipeline.gl;

import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL30C;

/**
 * Utility to render a screen-covering triangle without storing vertex buffers.
 * The vertex shader generates positions analytically from gl_VertexID.
 */
public class FullscreenQuad {
    private static int dummyVao = 0;

    public static void init() {
        if (dummyVao == 0) {
            dummyVao = GL30C.glGenVertexArrays();
        }
    }

    public static void render() {
        if (dummyVao == 0) {
            init();
        }
        GL30C.glBindVertexArray(dummyVao);
        GL11C.glDrawArrays(GL11C.GL_TRIANGLES, 0, 3);
        GL30C.glBindVertexArray(0);
    }

    public static void destroy() {
        if (dummyVao != 0) {
            GL30C.glDeleteVertexArrays(dummyVao);
            dummyVao = 0;
        }
    }
}
