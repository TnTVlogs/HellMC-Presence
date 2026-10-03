package net.hellmc.presence;

import net.minecraftforge.fml.common.Mod;

/** Entrypoint de Forge (només Forge llegeix aquesta anotació; NeoForge l'ignora). */
@Mod("hellmc_presence")
public final class ForgeEntry {
    public ForgeEntry() {
        Presence.start();
    }
}
