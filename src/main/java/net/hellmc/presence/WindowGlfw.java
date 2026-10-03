package net.hellmc.presence;

import java.util.List;
import java.util.Locale;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWImage;

/**
 * Títol i icona de la finestra del joc amb GLFW. Només s'ha de cridar al fil principal del joc (que és on el contexte
 * GLFW és el «current», i d'on s'obté la finestra sense cap API de Minecraft). LWJGL el posa el propi joc en temps
 * d'execució (és {@code compileOnly}).
 */
final class WindowGlfw {
    private WindowGlfw() {}

    /** @return {@code false} si encara no hi ha finestra (el joc encara arrenca). */
    static boolean apply(String title, boolean icon) {
        long window = GLFW.glfwGetCurrentContext();
        if (window == 0L) return false;
        GLFW.glfwSetWindowTitle(window, title);
        if (icon) setIcon(window);
        return true;
    }

    private static void setIcon(long window) {
        // macOS no té icona per finestra (és la de l'app al Dock).
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac")) return;
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
