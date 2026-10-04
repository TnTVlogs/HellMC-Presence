package net.hellmc.presence.agent.hook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HookTest {
    @BeforeEach
    @AfterEach
    void forgetConfig() {
        Hook.reset();
        System.clearProperty("hellmc.presence.dir");
    }

    private static void gameDirWith(Path dir, String json) throws IOException {
        Files.writeString(dir.resolve(Hook.CONFIG_FILE), json, StandardCharsets.UTF_8);
        System.setProperty("hellmc.presence.dir", dir.toString());
    }

    @Test
    void replacesOnlyTheMinecraftPrefixAndKeepsWhatTheGameTranslated() {
        assertEquals("HellMC Client 1.20.1", Hook.rewriteTitle("Minecraft* 1.20.1", "HellMC Client"));
        assertEquals("HellMC Client 26.1.2", Hook.rewriteTitle("Minecraft 26.1.2", "HellMC Client"));
        assertEquals("HellMC Client 1.20.1 - Un jugador", Hook.rewriteTitle("Minecraft* 1.20.1 - Un jugador", "HellMC Client"));
        assertEquals("HellMC Client 1.20.1 - Multiplayer (3rd-party Server)",
                Hook.rewriteTitle("Minecraft* 1.20.1 - Multiplayer (3rd-party Server)", "HellMC Client"));
    }

    @Test
    void leavesAnythingElseUntouched() {
        assertEquals("HellMC Client 1.20.1", Hook.rewriteTitle("HellMC Client 1.20.1", "HellMC Client"));
        assertEquals("Una altra finestra", Hook.rewriteTitle("Una altra finestra", "HellMC Client"));
        assertEquals("Minecraft* 1.20.1", Hook.rewriteTitle("Minecraft* 1.20.1", null));
    }

    @Test
    void readsTheStringFieldWithEscapes() {
        String json = "{\"clientId\":\"1\", \"windowTitle\" :  \"Mi \\\"Cliente\\\" \\u00e0\\n\", \"texts\":{}}";
        assertEquals("Mi \"Cliente\" à\n", Hook.extractString(json, "windowTitle"));
        assertNull(Hook.extractString(json, "missing"));
        assertNull(Hook.extractString("{\"windowTitle\":null}", "windowTitle"));
        assertNull(Hook.extractString("{\"windowTitle\":\"sense final", "windowTitle"));
    }

    @Test
    void titleIsRewrittenWhenTheLauncherLeftItsFile(@TempDir Path dir) throws IOException {
        gameDirWith(dir, "{\"windowTitle\":\"HellMC Client\",\"minecraftVersion\":\"1.20.1\"}");
        assertEquals("HellMC Client 1.20.1", Hook.title("Minecraft* 1.20.1").toString());
        assertEquals("HellMC Client 1.20.1 - Singleplayer", Hook.title("Minecraft* 1.20.1 - Singleplayer").toString());
        assertNull(Hook.title(null));
    }

    @Test
    void usesTheDefaultBrandWhenTheFileHasNone(@TempDir Path dir) throws IOException {
        gameDirWith(dir, "{\"clientId\":null}");
        assertEquals("HellMC Client 1.21", Hook.title("Minecraft 1.21").toString());
    }

    @Test
    void doesNothingWithoutTheLauncherFile(@TempDir Path dir) {
        System.setProperty("hellmc.presence.dir", dir.toString());
        CharSequence title = "Minecraft* 1.20.1";
        assertSame(title, Hook.title(title));
        Object original = new Object();
        assertSame(original, Hook.icon(Object.class, original));
    }

    @Test
    void rewritesNulTerminatedUtf8Buffers(@TempDir Path dir) throws IOException {
        gameDirWith(dir, "{\"windowTitle\":\"HellMC Client\"}");
        ByteBuffer in = ByteBuffer.allocateDirect(32);
        in.put("Minecraft* 1.20.1".getBytes(StandardCharsets.UTF_8)).put((byte) 0).flip();

        ByteBuffer out = Hook.titleBytes(in);
        byte[] bytes = new byte[out.remaining()];
        out.get(bytes);
        assertEquals(0, bytes[bytes.length - 1]);
        assertEquals("HellMC Client 1.20.1", new String(bytes, 0, bytes.length - 1, StandardCharsets.UTF_8));
    }
}
