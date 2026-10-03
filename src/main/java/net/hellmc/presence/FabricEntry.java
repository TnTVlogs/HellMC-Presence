package net.hellmc.presence;

import net.fabricmc.api.ModInitializer;

/** Entrypoint de Fabric/Quilt (només es carrega si el loader és Fabric). */
public final class FabricEntry implements ModInitializer {
    @Override
    public void onInitialize() {
        Presence.start();
    }
}
