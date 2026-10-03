package net.hellmc.presence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class GameStateTest {
    private static Config config(String extra) {
        return Config.parse("{\"clientId\":\"123\",\"versionName\":\"Forge 1.20.1\",\"serverName\":\"HellMC Survival\","
            + "\"texts\":{\"menu\":\"Al menú\",\"singleplayer\":\"En solitari\",\"joined\":\"Jugant a {server}\","
            + "\"playingAt\":\"A {ip}\",\"localServer\":\"Servidor local\",\"joining\":\"Carregant…\"}" + extra + "}");
    }

    private static String line(String msg) {
        return "[12:00:00] [Render thread/INFO]: " + msg;
    }

    @Test
    void startsLoading() {
        GameState s = new GameState(config(""));
        assertEquals("Carregant…", s.initial().details());
        assertEquals(GameState.Mode.LOADING, s.mode());
    }

    @Test
    void soundEngineMeansMenu() {
        GameState s = new GameState(config(""));
        GameState.View v = s.onLine(line("Sound engine started"));
        assertEquals(GameState.Mode.MENU, s.mode());
        assertEquals("Al menú", v.details());
        assertEquals("Forge 1.20.1", v.state());
    }

    @Test
    void singleplayerThenBackToMenu() {
        GameState s = new GameState(config(""));
        s.onLine(line("Sound engine started"));
        assertEquals("En solitari", s.onLine(line("Starting integrated minecraft server version 1.20.1")).details());
        assertEquals(GameState.Mode.SINGLEPLAYER, s.mode());
        assertEquals("Al menú", s.onLine(line("Stopping integrated minecraft server")).details());
    }

    @Test
    void serverJoinNeedsConfirmation() {
        GameState s = new GameState(config(""));
        s.onLine(line("Sound engine started"));
        s.onLine(line("Connecting to play.hellmc.example, 25565"));
        assertEquals(GameState.Mode.LOADING, s.mode());
        GameState.View v = s.onLine(line("Loaded 1200 advancements"));
        assertEquals(GameState.Mode.SERVER, s.mode());
        assertEquals("Jugant a HellMC Survival", v.details());
        assertEquals("A play.hellmc.example", v.state());
    }

    @Test
    void masksPrivateAddresses() {
        GameState s = new GameState(config(""));
        s.onLine(line("Connecting to 192.168.1.20, 25565"));
        GameState.View v = s.onLine(line("Creating pipeline for dimension minecraft:overworld"));
        assertEquals("A Servidor local", v.state());
    }

    @Test
    void ignoresUnrelatedLinesAndDuplicateMenu() {
        GameState s = new GameState(config(""));
        assertNull(s.onLine(line("Loaded 1200 advancements"))); // sense Connecting previ
        s.onLine(line("Sound engine started"));
        assertNull(s.onLine(line("Back to main menu"))); // ja som al menú
        assertNull(s.onLine("random text"));
    }

    @Test
    void defaultsToEnglishWithoutTexts() {
        GameState s = new GameState(Config.parse("{\"clientId\":\"1\"}"));
        assertEquals("In the main menu", s.onLine(line("Sound engine started")).details());
    }

    @Test
    void activityJsonIsWellFormed() {
        Config c = config(",\"largeImageKey\":\"logo\",\"largeImageText\":\"HellMC\",\"smallImageKey\":\"s\",\"startTimestamp\":1700000000000");
        String json = Presence.activityJson(c, new GameState(c).view());
        java.util.Map<String, Object> m = MiniJson.parseObject(json);
        assertEquals("Carregant…", m.get("details"));
        assertEquals(1700000000000.0, ((java.util.Map<?, ?>) m.get("timestamps")).get("start"));
        assertEquals("logo", ((java.util.Map<?, ?>) m.get("assets")).get("large_image"));
        assertEquals(Boolean.FALSE, m.get("instance"));
    }
}
