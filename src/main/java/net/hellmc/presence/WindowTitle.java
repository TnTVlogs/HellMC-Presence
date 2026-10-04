package net.hellmc.presence;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Títol i icona de la finestra del joc: «HellMC Client 1.20.1 - Singleplayer» i la flama de HellMC.
 *
 * <p>Per funcionar a <b>totes</b> les versions (1.18 → 26.x) i loaders amb un sol jar, no usa cap mètode de Minecraft
 * pel seu nom (que és ofuscat i canvia segons el loader i la versió). Només necessita dues coses que són estables:
 * <ul>
 *   <li>la classe principal del joc: {@code net.minecraft.client.Minecraft} (noms oficials: Forge, NeoForge, 26.x) o
 *       {@code net.minecraft.class_310} (Fabric/Quilt, nom «intermediary», estable des de 1.14), i el seu mètode estàtic
 *       sense paràmetres que la retorna ({@code getInstance}), que es troba per la signatura, no pel nom;</li>
 *   <li>que implementa {@link Executor}: {@code execute(Runnable)} el posa a la cua del <b>fil principal</b> del joc.</li>
 * </ul>
 * Des d'aquest fil, {@code glfwGetCurrentContext()} dona la finestra, i el títol i la icona es posen amb GLFW
 * ({@link WindowGlfw}).
 *
 * <p>El que ha dit el joc de sí mateix («Singleplayer», «Multiplayer…») no es llegeix del joc: es reprodueix amb el
 * que ja sap el mod pel log (menú / un jugador / servidor) i els textos que el launcher envia traduïts. Es reaplica cada
 * 500 ms perquè el joc reescriu el seu títol en canviar de món.
 */
final class WindowTitle {
    private static final Logger LOG = Logger.getLogger("HellMC-Presence");
    private static final String[] MINECRAFT_CLASSES = {"net.minecraft.client.Minecraft", "net.minecraft.class_310"};
    private static final long TICK_MS = 500;

    private static volatile boolean started;

    private WindowTitle() {}

    /** «HellMC Client 1.20.1», «HellMC Client 1.20.1 - Singleplayer», «… - Multiplayer (3rd-party Server)». */
    static String compose(Config config, GameState.Mode mode) {
        StringBuilder b = new StringBuilder(config.windowTitle);
        if (config.minecraftVersion != null) b.append(' ').append(config.minecraftVersion);
        switch (mode) {
            case SINGLEPLAYER -> b.append(" - ").append(config.text("titleSingleplayer", "Singleplayer"));
            case SERVER -> b.append(" - ").append(config.text("titleMultiplayer", "Multiplayer (3rd-party Server)"));
            default -> {
                // menú o carregant: com Minecraft, sense sufix
            }
        }
        return b.toString();
    }

    static synchronized void start(Path gameDir, Config config) {
        if (started) return;
        started = true;
        Thread t = new Thread(() -> run(gameDir, config), "HellMC-WindowTitle");
        t.setDaemon(true);
        t.start();
    }

    private static void run(Path gameDir, Config config) {
        Class<?> minecraft = findMinecraftClass();
        Method getInstance = minecraft == null ? null : findInstanceMethod(minecraft);
        if (getInstance == null || !Executor.class.isAssignableFrom(minecraft)) {
            LOG.info("Window title/icon not customised: Minecraft main class not recognised.");
            return;
        }

        GameState state = new GameState(config);
        LogTailer tailer = new LogTailer(gameDir.resolve("logs").resolve("latest.log"));
        AtomicInteger applied = new AtomicInteger();
        boolean logged = false;
        while (!Thread.currentThread().isInterrupted()) {
            // Amb l'agent (`-javaagent`, vegeu `net.hellmc.presence.agent`) el títol i la icona ja es posen a GLFW mateix,
            // abans que arribin a la finestra: no cal (ni convé) pelejar-hi des d'aquí.
            if (agentActive()) {
                LOG.info("Window title/icon handled by the HellMC agent.");
                return;
            }
            for (String line : tailer.poll()) state.onLine(line);
            try {
                Object mc = getInstance.invoke(null);
                if (mc instanceof Executor executor) {
                    String title = compose(config, state.mode());
                    executor.execute(() -> {
                        // La icona es posa a la primera passada i es repeteix més tard (el joc posa la seva en arrencar).
                        int n = applied.get();
                        boolean icon = n == 0 || n == 10 || n == 40;
                        try {
                            if (WindowGlfw.apply(title, icon)) applied.incrementAndGet();
                        } catch (RuntimeException | LinkageError e) {
                            LOG.log(Level.FINE, "Window title update failed", e);
                        }
                    });
                    if (!logged) {
                        logged = true;
                        LOG.info("Window title/icon customised (\"" + config.windowTitle + "\").");
                    }
                }
            } catch (ReflectiveOperationException | RuntimeException e) {
                LOG.log(Level.FINE, "Window title tick failed", e);
            }
            try {
                Thread.sleep(TICK_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** L'agent posa aquesta propietat només quan ha reescrit tots els mètodes de GLFW (vegeu {@code GlfwTransformer}). */
    static boolean agentActive() {
        return System.getProperty("hellmc.presence.agent") != null;
    }

    private static Class<?> findMinecraftClass() {
        ClassLoader[] loaders = {Presence.class.getClassLoader(), Thread.currentThread().getContextClassLoader()};
        for (String name : MINECRAFT_CLASSES) {
            for (ClassLoader loader : loaders) {
                if (loader == null) continue;
                try {
                    return Class.forName(name, false, loader);
                } catch (ClassNotFoundException | LinkageError ignored) {
                    // es prova el següent nom/loader
                }
            }
        }
        return null;
    }

    /** El mètode estàtic sense paràmetres que torna la pròpia classe (el `getInstance` de Minecraft, sigui quin sigui el seu nom). */
    static Method findInstanceMethod(Class<?> type) {
        for (Method m : type.getDeclaredMethods()) {
            if (Modifier.isStatic(m.getModifiers()) && m.getParameterCount() == 0 && m.getReturnType() == type) {
                try {
                    m.setAccessible(true);
                    return m;
                } catch (RuntimeException e) {
                    // sense accés: no es pot usar
                }
            }
        }
        return null;
    }
}
