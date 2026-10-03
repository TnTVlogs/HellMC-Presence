package net.hellmc.presence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class WindowTitleTest {
    @Test
    void replacesTheLeadingMinecraftKeepingVersionAndMode() {
        assertEquals("HellMC Client 26.1.2 - Singleplayer", WindowTitle.rebrand("Minecraft* 26.1.2 - Singleplayer", "HellMC Client"));
        assertEquals("HellMC Client 1.20.1", WindowTitle.rebrand("Minecraft 1.20.1", "HellMC Client"));
        assertEquals("HellMC Client 26.1.2 - Multiplayer (3rd-party Server)",
            WindowTitle.rebrand("Minecraft* 26.1.2 - Multiplayer (3rd-party Server)", "HellMC Client"));
        assertEquals("HellMC Client 26.1.2 - Un jugador", WindowTitle.rebrand("Minecraft* 26.1.2 - Un jugador", "HellMC Client"));
    }

    @Test
    void leavesUnknownTitlesAlone() {
        assertNull(WindowTitle.rebrand("Una altra cosa", "HellMC Client"));
        assertNull(WindowTitle.rebrand(null, "HellMC Client"));
    }
}
