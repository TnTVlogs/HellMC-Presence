package net.hellmc.presence;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Títol de la finestra del joc: «HellMC Client 26.1.2 - Singleplayer» en lloc de «Minecraft* 26.1.2 - Singleplayer».
 *
 * <p>El mod no depèn de cap API de Minecraft (un sol jar per a tots els loaders i versions), així que això és
 * <b>opcional i degrada en silenci</b>: es fa per reflexió amb els noms de Mojang ({@code Minecraft.getInstance()},
 * {@code getWindow()}, {@code Window.setTitle}, {@code createTitle()}), que són els reals en les versions
 * sense ofuscar (26.x). En versions més antigues, on el loader ofusca els noms en temps d'execució, no es troben i
 * el títol es deixa com està (la Rich Presence no en depèn).
 *
 * <p>La part dinàmica («Singleplayer», «Multiplayer (3rd-party Server)», LAN…) no es reconstrueix: se la demana al
 * propi Minecraft ({@code createTitle()}, que ja la tradueix a l'idioma del jugador) i només es canvia el principi.
 * Tot s'executa al fil principal del joc ({@code Minecraft.execute}), que és on cal tocar la finestra.
 */
final class WindowTitle {
    private static final Logger LOG = Logger.getLogger("HellMC-Presence");
    /** «Minecraft» + «*» opcional (jocs modificats) + versió + resta («- Singleplayer», …). */
    private static final Pattern VANILLA = Pattern.compile("^Minecraft\\*?\\s+(\\S+)(.*)$", Pattern.DOTALL);
    private static final long TICK_MS = 500;

    private static volatile boolean started;

    private WindowTitle() {}

    /** «Minecraft* 26.1.2 - Singleplayer» → «HellMC Client 26.1.2 - Singleplayer»; {@code null} si no té el format esperat. */
    static String rebrand(String vanillaTitle, String brand) {
        if (vanillaTitle == null) return null;
        Matcher m = VANILLA.matcher(vanillaTitle);
        return m.matches() ? brand + " " + m.group(1) + m.group(2) : null;
    }

    static synchronized void start(String brand) {
        if (started || brand == null || brand.isEmpty()) return;
        started = true;
        Thread t = new Thread(() -> run(brand), "HellMC-WindowTitle");
        t.setDaemon(true);
        t.start();
    }

    private static void run(String brand) {
        Class<?> minecraft;
        Method getInstance;
        Method getWindow;
        Method execute;
        Method createTitle;
        Method setTitle;
        try {
            ClassLoader loader = Presence.class.getClassLoader();
            minecraft = Class.forName("net.minecraft.client.Minecraft", false, loader);
            getInstance = minecraft.getMethod("getInstance");
            getWindow = minecraft.getMethod("getWindow");
            execute = minecraft.getMethod("execute", Runnable.class);
            createTitle = minecraft.getDeclaredMethod("createTitle");
            createTitle.setAccessible(true);
            setTitle = getWindow.getReturnType().getMethod("setTitle", String.class);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            LOG.info("Window title not customised on this Minecraft version (" + e.getClass().getSimpleName() + ").");
            return;
        }

        final Method createTitleF = createTitle;
        final Method getWindowF = getWindow;
        final Method setTitleF = setTitle;
        boolean logged = false;
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Object mc = getInstance.invoke(null);
                if (mc != null) {
                    execute.invoke(mc, (Runnable) () -> apply(mc, brand, createTitleF, getWindowF, setTitleF));
                    if (!logged) {
                        logged = true;
                        LOG.info("Window title customised (\"" + brand + "\").");
                    }
                }
            } catch (InvocationTargetException | IllegalAccessException | RuntimeException e) {
                LOG.log(Level.FINE, "Window title tick failed", e);
            }
            try {
                Thread.sleep(TICK_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** S'executa al fil principal del joc. */
    private static void apply(Object mc, String brand, Method createTitle, Method getWindow, Method setTitle) {
        try {
            Object window = getWindow.invoke(mc);
            if (window == null) return;
            String branded = rebrand((String) createTitle.invoke(mc), brand);
            if (branded != null) setTitle.invoke(window, branded);
        } catch (InvocationTargetException | IllegalAccessException | RuntimeException e) {
            LOG.log(Level.FINE, "Window title update failed", e);
        }
    }
}
