package net.hellmc.presence;

import java.util.List;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWImage;

/**
 * Posa la icona de HellMC a la finestra del joc ({@code glfwSetWindowIcon}). Només s'ha de cridar al fil principal del
 * joc. LWJGL el posa el propi Minecraft en temps d'execució (és {@code compileOnly}); aquesta classe només es carrega si
 * hi ha finestra, i qualsevol error es deixa passar sense efecte.
 */
final class WindowIcon {
    private WindowIcon() {}

    static void apply(long window) {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        if (os.contains("mac") || window == 0L) return; // macOS no té icona per finestra (és la del dock)
        List<IconImages.Rgba> images = IconImages.load();
        if (images.isEmpty()) return;
        GLFWImage.Buffer buffer = GLFWImage.malloc(images.size());
        try {
            for (int i = 0; i < images.size(); i++) {
                IconImages.Rgba image = images.get(i);
                buffer.get(i).set(image.width(), image.height(), image.pixels());
            }
            GLFW.glfwSetWindowIcon(window, buffer);
        } finally {
            buffer.free();
        }
    }
}
