package net.hellmc.presence;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Dades que el launcher deixa a la carpeta d'instància (`hellmc-presence.json`) abans de llançar el
 * joc — són les mateixes que li dona el `distribution.json` (`discord` global + `server.discord`),
 * més els textos ja traduïts a l'idioma del jugador. Si el fitxer no hi és, el mod no fa res (així
 * és inofensiu en un servidor dedicat o si algú el posa a mà a una instal·lació sense HellMC).
 */
final class Config {
    static final String FILE_NAME = "hellmc-presence.json";

    final String clientId;
    final String versionName;
    final String serverName;
    final String serverShortId;
    final String largeImageKey;
    final String largeImageText;
    final String smallImageKey;
    final String smallImageText;
    /** Nom de la finestra del joc («HellMC Client»); si el launcher no l'envia, es fa servir aquest valor per defecte. */
    final String windowTitle;
    /** Versió de Minecraft de la instància («1.20.1», «26.1.2»…), per al títol de la finestra. */
    final String minecraftVersion;
    final long startTimestampMs;
    private final Map<String, String> texts;

    private Config(Map<String, Object> m) {
        this.clientId = str(m, "clientId");
        this.versionName = str(m, "versionName");
        this.serverName = str(m, "serverName");
        this.serverShortId = str(m, "serverShortId");
        this.largeImageKey = str(m, "largeImageKey");
        this.largeImageText = str(m, "largeImageText");
        this.smallImageKey = str(m, "smallImageKey");
        this.smallImageText = str(m, "smallImageText");
        this.minecraftVersion = str(m, "minecraftVersion");
        String title = str(m, "windowTitle");
        this.windowTitle = title != null ? title : "HellMC Client";
        Object ts = m.get("startTimestamp");
        this.startTimestampMs = ts instanceof Number n ? n.longValue() : System.currentTimeMillis();
        Map<String, String> t = new java.util.HashMap<>();
        if (m.get("texts") instanceof Map<?, ?> tm) {
            for (Map.Entry<?, ?> e : tm.entrySet()) {
                if (e.getKey() instanceof String k && e.getValue() instanceof String v) t.put(k, v);
            }
        }
        this.texts = t;
    }

    /** Text localitzat amb valor per defecte (anglès) si el launcher no l'ha enviat. */
    String text(String key, String fallback) {
        String v = texts.get(key);
        return v != null && !v.isEmpty() ? v : fallback;
    }

    boolean valid() {
        return clientId != null && !clientId.isEmpty();
    }

    static Config parse(String json) {
        return new Config(MiniJson.parseObject(json));
    }

    static Config load(Path file) throws IOException {
        return parse(Files.readString(file, StandardCharsets.UTF_8));
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v instanceof String s && !s.isEmpty() ? s : null;
    }
}
