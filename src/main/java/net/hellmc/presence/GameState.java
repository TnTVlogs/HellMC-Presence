package net.hellmc.presence;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Màquina d'estats del «què està fent el jugador», derivada del log del joc (mateixes expressions
 * que feia servir el launcher antic a `landing.js` — el format de log4j de Minecraft és el mateix a
 * Forge/NeoForge/Fabric). Sense dependències de Minecraft: només text.
 */
final class GameState {
    enum Mode { LOADING, MENU, SINGLEPLAYER, SERVER }

    /** Activitat a mostrar: {@code details} (línia 1) i {@code state} (línia 2). */
    record View(String details, String state) {}

    private static final Pattern SOUND_ENGINE = Pattern.compile("\\[.+\\]: Sound engine started");
    private static final Pattern SINGLEPLAYER = Pattern.compile("\\[.+\\]: Starting integrated minecraft server");
    private static final Pattern MENU = Pattern.compile(
        "\\[.+\\]: (?:Back to main menu|Quitting to main menu|Disconnected from server|Left the game|"
            + "Closing NetworkManager|Stopping integrated minecraft server|Disconnecting|Instance shutdown)");
    private static final Pattern CONNECT = Pattern.compile("\\[.+\\]: Connecting to ([^, ]+)");
    private static final Pattern CONFIRMED_JOIN = Pattern.compile(
        "\\[.+\\]: (?:reloading ETF data|Loaded \\d+ advancements|Creating pipeline for dimension)");
    private static final Pattern PRIVATE_IP = Pattern.compile("^(?:10\\.|127\\.|172\\.(?:1[6-9]|2\\d|3[01])\\.|192\\.168\\.|localhost)");
    private static final Pattern NUMERIC_IP = Pattern.compile("^\\d{1,3}(?:\\.\\d{1,3}){3}$");

    private final Config config;
    private Mode mode = Mode.LOADING;
    private String attemptedAddress;
    private String serverAddress;

    GameState(Config config) {
        this.config = config;
    }

    Mode mode() {
        return mode;
    }

    /** Vista inicial (mentre el joc arrenca). */
    View initial() {
        return view();
    }

    /**
     * Processa una línia de log; retorna la nova vista si ha canviat alguna cosa visible, o
     * {@code null} si la línia no afecta l'activitat.
     */
    View onLine(String line) {
        String data = line.trim();
        Matcher connect = CONNECT.matcher(data);
        if (connect.find()) {
            attemptedAddress = connect.group(1);
            return set(Mode.LOADING, null);
        }
        if (attemptedAddress != null && CONFIRMED_JOIN.matcher(data).find()) {
            String joined = attemptedAddress;
            attemptedAddress = null;
            return set(Mode.SERVER, joined);
        }
        if (SINGLEPLAYER.matcher(data).find()) {
            return set(Mode.SINGLEPLAYER, null);
        }
        if (MENU.matcher(data).find()) {
            attemptedAddress = null;
            return mode == Mode.MENU ? null : set(Mode.MENU, null);
        }
        if (SOUND_ENGINE.matcher(data).find() && mode == Mode.LOADING && attemptedAddress == null) {
            // El joc ja és al menú principal.
            return set(Mode.MENU, null);
        }
        return null;
    }

    private View set(Mode m, String address) {
        mode = m;
        serverAddress = address;
        return view();
    }

    View view() {
        String where = config.serverName != null ? config.serverName
            : config.serverShortId != null ? config.serverShortId : "HellMC";
        return switch (mode) {
            case LOADING -> new View(
                config.text("joining", "Loading…"),
                config.versionName != null ? config.versionName : where);
            case MENU -> new View(config.text("menu", "In the main menu"), versionLine());
            case SINGLEPLAYER -> new View(config.text("singleplayer", "Playing singleplayer"), versionLine());
            case SERVER -> new View(
                config.text("joined", "Playing on {server}").replace("{server}", where),
                config.text("playingAt", "Playing on {ip}").replace("{ip}", maskLocal(serverAddress)));
        };
    }

    private String versionLine() {
        return config.versionName != null ? config.versionName : "HellMC";
    }

    private String maskLocal(String address) {
        if (address == null) return "";
        return NUMERIC_IP.matcher(address).matches() && PRIVATE_IP.matcher(address).find()
            || address.equalsIgnoreCase("localhost")
            ? config.text("localServer", "Local server")
            : address;
    }
}
