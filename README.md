# HellMC Presence

Discord Rich Presence **inside the game** for [HellMC Client](https://github.com/TnTVlogs/HellMC-Client).

One **universal jar** for **Fabric / Quilt, Forge and NeoForge**, every Minecraft version from 1.18 up to 26.x
(Java 17+ bytecode). It touches **no Minecraft API by name** — only the loader entrypoint and the game's main-thread `Executor` — so it does not
need one build per loader × Minecraft version.

## Why a mod

The launcher spawns the game *detached*, so the game survives closing the launcher — but a Rich Presence living in
the launcher would die with it. The mod lives inside the Minecraft process instead (design decision P17,
`docs/client-redesign/06-client-arquitectura.md` §8.1):

| State | Who shows the presence |
|---|---|
| Launcher open, no game running | The launcher |
| Game running | **This mod** (the launcher clears its own activity when it launches the game) |

## How it works

1. The launcher writes `hellmc-presence.json` into the instance folder (the game's working directory) before every
   launch, from the `distribution.json` (`discord` global settings + `server.discord`) and translated texts.
   **Without that file the mod does nothing** (so it is harmless on a dedicated server or a non-HellMC install).
2. A daemon thread connects to Discord's local IPC (named pipe on Windows, Unix socket on Linux/macOS, including the
   Snap and Flatpak paths). If Discord is not running it retries quietly every 30 s.
3. The activity follows `logs/latest.log` (same expressions the launcher used to scrape from stdout): *loading → main
   menu → singleplayer / server (with the address; private IPs are masked)*.
4. On exit the activity is cleared (with a time limit so a stuck Discord can never block shutting the game down).

No dependencies: the IPC protocol (handshake + `SET_ACTIVITY`) and the JSON handling are implemented in ~400 lines.

### `hellmc-presence.json`

```jsonc
{
  "clientId": "123456789012345678",      // required: Discord application id
  "versionName": "Forge 1.20.1",
  "serverName": "HellMC Survival",       // null when playing without a server
  "serverShortId": "survival",
  "largeImageKey": "logo", "largeImageText": "HellMC",
  "smallImageKey": "survival", "smallImageText": "HellMC Survival",
  "startTimestamp": 1767225600000,        // ms since epoch (launch time)
  "texts": {                              // already translated by the launcher
    "menu": "In the main menu", "singleplayer": "Playing singleplayer", "joining": "Loading…",
    "joined": "Playing on {server}", "playingAt": "Playing on {ip}", "localServer": "Local server"
  }
}
```

The directory can be overridden with `-Dhellmc.presence.dir=<path>` (defaults to the working directory).

## Window title and icon

The game window is renamed to **`HellMC Client 1.20.1`** (Minecraft version of the instance), with the usual mode suffix
(`- Singleplayer`, `- Multiplayer (3rd-party Server)`, translated to the player's language), and gets the HellMC flame as its
icon. The `*` vanilla puts after "Minecraft" for modded games is not shown.

It works on **every version and loader** with the single jar, because it uses **no Minecraft method by name** (those are
obfuscated and change per loader/version). It only needs:

1. the main game class — `net.minecraft.client.Minecraft` (official names: Forge, NeoForge, 26.x) or
   `net.minecraft.class_310` (Fabric/Quilt) — and its only static, no-argument method that returns that class (found by
   *signature*, whatever it is called);
2. that the class implements `java.util.concurrent.Executor`: `execute(Runnable)` queues work on the game's **main thread**,
   where GLFW's current context is the game window.

From there the title and the icon are set with LWJGL's GLFW (`glfwSetWindowTitle`, `glfwSetWindowIcon`; the classes come
from the game itself — `compileOnly`, not packaged). Verified against the real 26.1.2 client and the intermediary-remapped
1.21.11 Fabric client. The suffix is **not read from the game**: it follows the same log-based state as the Discord
presence (menu / singleplayer / server) with texts sent by the launcher (`titleSingleplayer`, `titleMultiplayer`, and
`minecraftVersion` / `windowTitle` in `hellmc-presence.json`). The title is re-applied every 500 ms (the game rewrites its own
when the world changes); the icon on the first pass and again a few seconds later (the game sets its own while starting). On
macOS the icon is skipped (the Dock icon is the app's). It does not depend on Discord: `clientId` may be missing.

PNGs in six sizes (16–256) live in `src/main/resources/hellmc-icon-*.png`.

## Build

```bash
gradle build        # needs Java 17+ and Gradle 8.5+ (tested with Gradle 9.2 / Java 25)
# → build/libs/hellmc-presence-<version>.jar
```

The jar contains `fabric.mod.json`, `META-INF/mods.toml` (Forge, NeoForge ≤ 1.20.4) and
`META-INF/neoforge.mods.toml` (NeoForge ≥ 1.20.5). The loader annotations/interfaces needed at compile time are
tiny stubs (`src/stubs`) that are **not** packaged.

Tests: `gradle test` (JSON, state machine, IPC framing/handshake against a fake Unix-socket server).

## Deploy

Put the built jar somewhere on the panel server and point `PRESENCE_JAR` (panel `backend/.env`) at it. On every
publish the panel copies it into the `required/` mods folder of **all** versions, so Nebula emits it as a mandatory
module. The client hides it from the mods list (it is infrastructure, not a player option).

## License

MIT. See [LICENSE](LICENSE).
