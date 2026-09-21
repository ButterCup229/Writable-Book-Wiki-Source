package com.writablebookwiki.neoforge;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Development-only safety proof for a future movable native document window.
 *
 * <p>Minecraft deliberately disables AWT/Swing. A real secondary window must
 * therefore be a GLFW window with its own OpenGL context. This proof does not
 * render document content and is never opened by a player-facing key. It only
 * verifies the dangerous part in isolation: create one shared context, paint
 * it using raw OpenGL, swap it, and restore the primary Minecraft context and
 * its LWJGL capabilities.</p>
 */
final class StandaloneGlfwProofWindow {
    private static final Logger LOGGER = LoggerFactory.getLogger("writablebookwiki/StandaloneGlfwProofWindow");
    private static final int WIDTH = 520;
    private static final int HEIGHT = 280;
    private static long window;
    private static boolean reportedFailure;

    private StandaloneGlfwProofWindow() {
    }

    static void openForDevelopmentIfRequested() {
        if (!Boolean.getBoolean("writablebookwiki.glfwProof") || window != MemoryUtil.NULL || reportedFailure) {
            return;
        }
        if (!RenderSystem.isOnRenderThread()) {
            return;
        }
        long mainWindow = Minecraft.getInstance().getWindow().getWindow();
        if (mainWindow == MemoryUtil.NULL) {
            fail("主游戏窗口尚未初始化");
            return;
        }

        // GLFW is already initialised and owned by Minecraft. Do not call
        // glfwInit/glfwTerminate or replace its process-wide error callback.
        GLFW.glfwDefaultWindowHints();
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_RESIZABLE, GLFW.GLFW_TRUE);
        GLFW.glfwWindowHint(GLFW.GLFW_FOCUSED, GLFW.GLFW_FALSE);
        long created = GLFW.glfwCreateWindow(WIDTH, HEIGHT, "Writable Book Wiki - 原生窗口验证",
                MemoryUtil.NULL, mainWindow);
        if (created == MemoryUtil.NULL) {
            fail("GLFW 无法创建共享上下文窗口");
            return;
        }

        try {
            renderProofFrame(created, mainWindow);
            GLFW.glfwShowWindow(created);
            window = created;
            LOGGER.info("Writable Book Wiki standalone GLFW proof window opened safely");
        } catch (RuntimeException exception) {
            GLFW.glfwDestroyWindow(created);
            fail("共享上下文绘制失败：" + exception.getClass().getSimpleName());
        }
    }

    static void closeForDevelopment() {
        if (window == MemoryUtil.NULL) {
            return;
        }
        if (!RenderSystem.isOnRenderThread()) {
            return;
        }
        GLFW.glfwDestroyWindow(window);
        window = MemoryUtil.NULL;
    }

    private static void renderProofFrame(long proofWindow, long mainWindow) {
        long originalContext = GLFW.glfwGetCurrentContext();
        GLCapabilities originalCapabilities = GL.getCapabilities();
        if (originalContext != mainWindow) {
            throw new IllegalStateException("Minecraft primary context is not current");
        }
        try {
            GLFW.glfwMakeContextCurrent(proofWindow);
            GL.createCapabilities();
            GLFW.glfwSwapInterval(1);
            org.lwjgl.opengl.GL11.glViewport(0, 0, WIDTH, HEIGHT);
            org.lwjgl.opengl.GL11.glClearColor(0.18f, 0.15f, 0.22f, 1.0f);
            org.lwjgl.opengl.GL11.glClear(org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT);
            GLFW.glfwSwapBuffers(proofWindow);
        } finally {
            // Context and LWJGL capabilities are thread-local. Both must be
            // restored before Minecraft executes another RenderSystem call.
            GLFW.glfwMakeContextCurrent(mainWindow);
            GL.setCapabilities(originalCapabilities);
        }
    }

    private static void fail(String message) {
        reportedFailure = true;
        LOGGER.warn("Writable Book Wiki standalone GLFW proof: {}", message);
    }
}
