package net.hellmc.presence.agent.hook;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.imageio.ImageIO;

/**
 * Codi que les funcions de GLFW reescrites per l'agent ({@code GlfwTransformer}) criden abans de fer la seva feina:
 * canvien el títol («Minecraft* 1.20.1» → «HellMC Client 1.20.1») i la icona de la finestra **abans** que arribin al
 * sistema, de manera que mai no es veu el del joc.
 *
 * <p>Aquesta classe viu al classpath d'arrencada (l'agent en copia el paquet a un jar temporal que afegeix al
 * «bootstrap»), perquè GLFW —que és d'LWJGL i el carrega qualsevol classloader— l'hi pugui veure. Per això només usa
 * classes del JDK: LWJGL s'hi toca per reflexió. Mai llança: davant de qualsevol error el joc segueix amb el seu
 * valor original.
 */
public final class Hook {
    /** Mateixos noms que {@code Config}: aquesta classe no en pot dependre (vegeu més amunt). */
    public static final String CONFIG_FILE = "hellmc-presence.json";
    public static final String DEFAULT_TITLE = "HellMC Client";
    private static final int[] ICON_SIZES = {16, 32, 48, 64, 128, 256};

    private static final Object LOCK = new Object();
    private static boolean loaded;
    /** Nom de la finestra del joc; {@code null} = no hi ha fitxer del launcher: no es toca res. */
    private static String windowTitle;

    private static boolean iconBuilt;
    private static Object iconBuffer;
    /** Els píxels han de viure tant com el {@code GLFWImage.Buffer}, que en guarda només l'adreça. */
    @SuppressWarnings({"unused", "FieldCanBeLocal"})
    private static List<ByteBuffer> iconPixels;

    private Hook() {}

    /** Només per a proves: oblida el que s'ha llegit de la carpeta del joc. */
    static void reset() {
        synchronized (LOCK) {
            loaded = false;
            windowTitle = null;
            iconBuilt = false;
            iconBuffer = null;
            iconPixels = null;
        }
    }

    // ── Títol ────────────────────────────────────────────────────────────────────────────────────────────

    /** Per a {@code glfwSetWindowTitle(long, CharSequence)} i {@code glfwCreateWindow(int, int, CharSequence, …)}. */
    public static CharSequence title(CharSequence title) {
        try {
            if (title == null) return null;
            String brand = windowTitle();
            if (brand == null) return title;
            String rewritten = rewriteTitle(title.toString(), brand);
            return rewritten.equals(title.toString()) ? title : rewritten;
        } catch (Throwable t) {
            report(t);
            return title;
        }
    }

    /** Per a les variants amb {@code ByteBuffer} (UTF-8 acabat en NUL, com exigeix LWJGL). */
    public static ByteBuffer titleBytes(ByteBuffer title) {
        try {
            if (title == null) return null;
            String brand = windowTitle();
            if (brand == null) return title;
            ByteBuffer view = title.duplicate();
            int length = view.remaining();
            if (length > 0 && view.get(view.limit() - 1) == 0) length--;
            byte[] bytes = new byte[length];
            view.get(bytes);
            String original = new String(bytes, StandardCharsets.UTF_8);
            String rewritten = rewriteTitle(original, brand);
            if (rewritten.equals(original)) return title;
            byte[] out = rewritten.getBytes(StandardCharsets.UTF_8);
            ByteBuffer buffer = ByteBuffer.allocateDirect(out.length + 1);
            buffer.put(out).put((byte) 0).flip();
            return buffer;
        } catch (Throwable t) {
            report(t);
            return title;
        }
    }

    /**
     * El joc posa sempre «Minecraft» (i un «*» si està modificat) al principi del títol i després la versió i el mode
     * en l'idioma del jugador («Minecraft* 1.20.1 - Un jugador»): només se'n canvia el principi, així el que ve darrere
     * ja ve traduït pel propi joc.
     */
    public static String rewriteTitle(String title, String brand) {
        if (brand == null || !title.startsWith("Minecraft")) return title;
        int i = "Minecraft".length();
        if (i < title.length() && title.charAt(i) == '*') i++;
        return brand + title.substring(i);
    }

    // ── Icona ────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Per a {@code glfwSetWindowIcon(long, GLFWImage.Buffer)}: la icona de HellMC en lloc de la que demana el joc. El
     * {@code GLFWImage.Buffer} es crea un sol cop i es reutilitza (GLFW en copia el contingut en cada crida).
     *
     * @param glfw la classe {@code org.lwjgl.glfw.GLFW}, per trobar-ne el classloader sense dependre d'LWJGL
     * @param original el que demanava el joc (pot ser {@code null}); és el que es torna si no hi ha icona pròpia
     */
    public static Object icon(Class<?> glfw, Object original) {
        try {
            if (windowTitle() == null) return original;
            Object ours = iconBuffer(glfw.getClassLoader());
            return ours != null ? ours : original;
        } catch (Throwable t) {
            report(t);
            return original;
        }
    }

    private static Object iconBuffer(ClassLoader lwjgl) {
        synchronized (LOCK) {
            if (iconBuilt) return iconBuffer;
            iconBuilt = true;
            // macOS no té icona per finestra (és la de l'app al Dock).
            if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac")) return null;
            try {
                List<Image> images = loadImages();
                if (images.isEmpty()) return null;
                Class<?> imageType = Class.forName("org.lwjgl.glfw.GLFWImage", false, lwjgl);
                Class<?> bufferType = Class.forName("org.lwjgl.glfw.GLFWImage$Buffer", false, lwjgl);
                Object buffer = imageType.getMethod("malloc", int.class).invoke(null, images.size());
                Method get = bufferType.getMethod("get", int.class);
                Method set = imageType.getMethod("set", int.class, int.class, ByteBuffer.class);
                List<ByteBuffer> pixels = new ArrayList<>();
                for (int i = 0; i < images.size(); i++) {
                    Image image = images.get(i);
                    set.invoke(get.invoke(buffer, i), image.width, image.height, image.pixels);
                    pixels.add(image.pixels);
                }
                iconPixels = pixels;
                iconBuffer = buffer;
            } catch (Throwable t) {
                report(t);
                iconBuffer = null;
            }
            return iconBuffer;
        }
    }

    private record Image(int width, int height, ByteBuffer pixels) {}

    /** Les PNG van dins el mateix jar que l'agent (el seu camí el deixa l'agent a {@code hellmc.presence.agentJar}). */
    private static List<Image> loadImages() throws IOException {
        List<Image> images = new ArrayList<>();
        String jar = System.getProperty("hellmc.presence.agentJar");
        if (jar == null) return images;
        try (ZipFile zip = new ZipFile(jar)) {
            for (int size : ICON_SIZES) {
                ZipEntry entry = zip.getEntry("hellmc-icon-" + size + ".png");
                if (entry == null) continue;
                try (InputStream in = zip.getInputStream(entry)) {
                    BufferedImage image = ImageIO.read(in);
                    if (image != null) images.add(toRgba(image));
                } catch (IOException | RuntimeException e) {
                    // una mida que no es pot llegir se salta
                }
            }
        }
        return images;
    }

    private static Image toRgba(BufferedImage image) {
        int w = image.getWidth();
        int h = image.getHeight();
        ByteBuffer buffer = ByteBuffer.allocateDirect(w * h * 4);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = image.getRGB(x, y);
                buffer.put((byte) (argb >> 16)).put((byte) (argb >> 8)).put((byte) argb).put((byte) (argb >>> 24));
            }
        }
        buffer.flip();
        return new Image(w, h, buffer);
    }

    private static boolean reported;

    /** Un error del hook mai ha de tombar el joc, però tampoc passar desapercebut: s'avisa un sol cop. */
    private static void report(Throwable t) {
        synchronized (LOCK) {
            if (reported) return;
            reported = true;
        }
        System.err.println("[HellMC-Presence] Error a l'agent (la finestra conserva el valor del joc): " + t);
    }

    // ── Configuració del launcher ────────────────────────────────────────────────────────────────────────

    /** Sense {@code hellmc-presence.json} a la carpeta del joc (un servidor, una instal·lació sense HellMC) no es toca res. */
    private static String windowTitle() {
        synchronized (LOCK) {
            if (!loaded) {
                loaded = true;
                try {
                    String dir = System.getProperty("hellmc.presence.dir");
                    Path file = Path.of(dir != null ? dir : System.getProperty("user.dir", ".")).resolve(CONFIG_FILE);
                    if (Files.isRegularFile(file)) {
                        String title = extractString(Files.readString(file, StandardCharsets.UTF_8), "windowTitle");
                        windowTitle = title != null && !title.isEmpty() ? title : DEFAULT_TITLE;
                    }
                } catch (IOException | RuntimeException e) {
                    windowTitle = null;
                }
            }
            return windowTitle;
        }
    }

    /** El valor de {@code "key": "…"} (cadena JSON, amb escapades) d'un objecte pla, o {@code null} si no hi és. */
    static String extractString(String json, String key) {
        int at = json.indexOf('"' + key + '"');
        if (at < 0) return null;
        int i = at + key.length() + 2;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
        if (i >= json.length() || json.charAt(i) != ':') return null;
        i++;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
        if (i >= json.length() || json.charAt(i) != '"') return null;
        i++;
        StringBuilder out = new StringBuilder();
        while (i < json.length()) {
            char c = json.charAt(i++);
            if (c == '"') return out.toString();
            if (c != '\\') {
                out.append(c);
                continue;
            }
            if (i >= json.length()) return null;
            char e = json.charAt(i++);
            switch (e) {
                case 'n' -> out.append('\n');
                case 't' -> out.append('\t');
                case 'r' -> out.append('\r');
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'u' -> {
                    if (i + 4 > json.length()) return null;
                    out.append((char) Integer.parseInt(json.substring(i, i + 4), 16));
                    i += 4;
                }
                default -> out.append(e); // \" \\ \/
            }
        }
        return null;
    }
}
