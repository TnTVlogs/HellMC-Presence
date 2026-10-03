package net.hellmc.presence;

import net.neoforged.fml.common.Mod;

/** Entrypoint de NeoForge (només NeoForge llegeix aquesta anotació; Forge l'ignora). */
@Mod("hellmc_presence")
public final class NeoForgeEntry {
    public NeoForgeEntry() {
        Presence.start();
    }
}
