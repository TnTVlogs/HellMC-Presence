package net.hellmc.presence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Punt d'entrada comú (els tres loaders el criden igual). Obre un fil daemon que:
 * <ol>
 *   <li>llegeix {@code hellmc-presence.json} de la carpeta del joc (si no hi és, no fa res);</li>
 *   <li>connecta amb Discord (reintent silenciós cada 30 s si Discord no és obert);</li>
 *   <li>actualitza l'activitat segons {@code logs/latest.log} (menú / un jugador / servidor);</li>
 *   <li>l'esborra en sortir el joc (hook de la JVM).</li>
 * </ol>
 * Viu dins el procés de Minecraft: continua encara que es tanqui el launcher (P17).
 */
public final class Presence {
    private static final Logger LOG = Logger.getLogger("HellMC-Presence");
    private static final long POLL_MS = 1000;
    private static final long RETRY_MS = 30_000;

    private static volatile boolean started;

    private Presence() {}

    /** Idempotent: pot cridar-se des de més d'un entrypoint sense duplicar el fil. */
    public static synchronized void start() {
        if (started) return;
        Path gameDir = gameDir();
        Path configFile = gameDir.resolve(Config.FILE_NAME);
        if (!Files.isRegularFile(configFile)) {
            LOG.fine("No " + Config.FILE_NAME + " in " + gameDir + "; presence disabled.");
            return;
        }
        started = true;
        Thread t = new Thread(() -> run(gameDir, configFile), "HellMC-Presence");
        t.setDaemon(true);
        t.start();
    }

    static Path gameDir() {
        String override = System.getProperty("hellmc.presence.dir");
        return Path.of(override != null ? override : System.getProperty("user.dir", ".")).toAbsolutePath();
    }

    private static void run(Path gameDir, Path configFile) {
        Config config;
        try {
            config = Config.load(configFile);
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.WARNING, "Could not read " + configFile + ": " + e.getMessage());
            return;
        }
        if (!config.valid()) {
            LOG.warning("clientId missing in " + configFile + "; presence disabled.");
            return;
        }

        GameState state = new GameState(config);
        LogTailer tailer = new LogTailer(gameDir.resolve("logs").resolve("latest.log"));
        long pid = ProcessHandle.current().pid();
        DiscordIpc[] current = new DiscordIpc[1];
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            DiscordIpc ipc = current[0];
            if (ipc == null) return;
            // Esborra l'activitat en sortir; amb un límit de temps perquè un Discord penjat no
            // pugui bloquejar la sortida del joc.
            Thread clear = new Thread(() -> {
                try {
                    ipc.setActivity(null, pid);
                } catch (IOException ignored) {
                    // Discord ja no hi és
                }
                ipc.close();
            }, "HellMC-Presence-clear");
            clear.setDaemon(true);
            clear.start();
            try {
                clear.join(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "HellMC-Presence-shutdown"));

        GameState.View view = state.initial();
        boolean dirty = true;
        boolean warned = false;

        while (!Thread.currentThread().isInterrupted()) {
            DiscordIpc ipc = current[0];
            if (ipc == null) {
                try {
                    ipc = DiscordIpc.connect(config.clientId);
                    current[0] = ipc;
                    dirty = true;
                    LOG.info("Discord Rich Presence connected.");
                } catch (IOException e) {
                    if (!warned) {
                        warned = true;
                        LOG.info("Discord not detected; will retry quietly every 30 s.");
                    }
                    sleep(RETRY_MS);
                    // Els canvis d'estat s'acumulen mentre no hi ha Discord.
                    for (String line : tailer.poll()) {
                        GameState.View v = state.onLine(line);
                        if (v != null) view = v;
                    }
                    continue;
                }
            }

            for (String line : tailer.poll()) {
                GameState.View v = state.onLine(line);
                if (v != null) {
                    view = v;
                    dirty = true;
                }
            }

            if (dirty) {
                try {
                    ipc.setActivity(activityJson(config, view), pid);
                    dirty = false;
                } catch (IOException e) {
                    // Discord s'ha tancat: es reconnecta al proper cicle.
                    ipc.close();
                    current[0] = null;
                    warned = false;
                    continue;
                }
            }
            sleep(POLL_MS);
        }
    }

    static String activityJson(Config c, GameState.View v) {
        StringBuilder b = new StringBuilder("{");
        b.append("\"details\":").append(MiniJson.quote(v.details()));
        b.append(",\"state\":").append(MiniJson.quote(v.state()));
        b.append(",\"timestamps\":{\"start\":").append(c.startTimestampMs).append('}');
        StringBuilder assets = new StringBuilder();
        // Mateix criteri que el launcher antic: imatge gran = la del distribution global (`discord`),
        // petita = la del servidor; aquí el launcher ja ho ha resolt a largeImage*/smallImage*.
        if (c.largeImageKey != null) assets.append("\"large_image\":").append(MiniJson.quote(c.largeImageKey));
        if (c.largeImageText != null) append(assets, "\"large_text\":" + MiniJson.quote(c.largeImageText));
        if (c.smallImageKey != null) append(assets, "\"small_image\":" + MiniJson.quote(c.smallImageKey));
        if (c.smallImageText != null) append(assets, "\"small_text\":" + MiniJson.quote(c.smallImageText));
        if (assets.length() > 0) b.append(",\"assets\":{").append(assets).append('}');
        b.append(",\"instance\":false}");
        return b.toString();
    }

    private static void append(StringBuilder sb, String part) {
        if (sb.length() > 0) sb.append(',');
        sb.append(part);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
