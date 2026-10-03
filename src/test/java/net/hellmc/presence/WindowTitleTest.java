package net.hellmc.presence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

class WindowTitleTest {
    private static Config config(String extra) {
        return Config.parse("{\"clientId\":\"1\",\"minecraftVersion\":\"1.20.1\"" + extra + "}");
    }

    @Test
    void composesBrandVersionAndMode() {
        Config c = config("");
        assertEquals("HellMC Client 1.20.1", WindowTitle.compose(c, GameState.Mode.MENU));
        assertEquals("HellMC Client 1.20.1", WindowTitle.compose(c, GameState.Mode.LOADING));
        assertEquals("HellMC Client 1.20.1 - Singleplayer", WindowTitle.compose(c, GameState.Mode.SINGLEPLAYER));
        assertEquals("HellMC Client 1.20.1 - Multiplayer (3rd-party Server)", WindowTitle.compose(c, GameState.Mode.SERVER));
    }

    @Test
    void usesTheTranslatedTextsAndTheCustomBrand() {
        Config c = config(",\"windowTitle\":\"Mi Cliente\",\"texts\":{\"titleSingleplayer\":\"Un jugador\",\"titleMultiplayer\":\"Multijugador\"}");
        assertEquals("Mi Cliente 1.20.1 - Un jugador", WindowTitle.compose(c, GameState.Mode.SINGLEPLAYER));
        assertEquals("Mi Cliente 1.20.1 - Multijugador", WindowTitle.compose(c, GameState.Mode.SERVER));
    }

    @Test
    void worksWithoutKnowingTheMinecraftVersion() {
        Config c = Config.parse("{\"clientId\":\"1\"}");
        assertEquals("HellMC Client", WindowTitle.compose(c, GameState.Mode.MENU));
    }

    /** Minecraft es troba pel seu mètode estàtic sense paràmetres que retorna la classe, sigui quin sigui el nom (ofuscat). */
    @Test
    void findsTheSingletonAccessorBySignature() throws Exception {
        Method m = WindowTitle.findInstanceMethod(FakeMinecraft.class);
        assertNotNull(m);
        assertEquals("method_1551", m.getName());
        assertEquals(FakeMinecraft.INSTANCE, m.invoke(null));
    }

    static final class FakeMinecraft {
        static final FakeMinecraft INSTANCE = new FakeMinecraft();

        static FakeMinecraft method_1551() {
            return INSTANCE;
        }

        static String unrelated() {
            return "no";
        }
    }
}
