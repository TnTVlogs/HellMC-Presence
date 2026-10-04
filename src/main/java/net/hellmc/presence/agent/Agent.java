package net.hellmc.presence.agent;

import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Entrada de Java agent ({@code -javaagent:hellmc-presence.jar}, que afegeix el launcher): s'executa **abans** que el
 * joc arrenqui i prepara la reescriptura de GLFW ({@link GlfwTransformer}) perquè el títol i la icona de la finestra
 * siguin sempre els de HellMC, sense cap instant en què es vegi «Minecraft».
 *
 * <p>Si no s'ha afegit l'agent (o falla), el mod segueix posant el títol i la icona pel seu compte, tot i que amb un
 * petit retard ({@code WindowTitle}).
 */
public final class Agent {
    static final String HOOK_PACKAGE_PATH = "net/hellmc/presence/agent/hook/";

    private Agent() {}

    public static void premain(String args, Instrumentation instrumentation) {
        try {
            Path self = Path.of(Agent.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (!Files.isRegularFile(self)) throw new IOException("l'agent no s'executa des d'un jar: " + self);
            System.setProperty("hellmc.presence.agentJar", self.toString());
            appendHookToBootstrap(instrumentation, self);
            instrumentation.addTransformer(new GlfwTransformer(instrumentation));
        } catch (IOException | URISyntaxException | RuntimeException | LinkageError e) {
            System.err.println("[HellMC-Presence] Agent desactivat: " + e);
        }
    }

    /**
     * GLFW és d'LWJGL i el carrega el classloader del joc (en depèn del loader); la classe que la crida ha de ser visible
     * des de qualsevol d'ells. Es copia només el paquet {@code hook} (sense cap dependència de loaders) a un jar temporal
     * i s'afegeix al classpath d'arrencada: així ni les classes del mod ni ASM hi acaben, on podrien xocar amb les del loader.
     */
    private static void appendHookToBootstrap(Instrumentation instrumentation, Path self) throws IOException {
        Path hookJar = Files.createTempFile("hellmc-presence-hook", ".jar");
        hookJar.toFile().deleteOnExit();
        try (ZipFile zip = new ZipFile(self.toFile()); JarOutputStream out = new JarOutputStream(Files.newOutputStream(hookJar))) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().startsWith(HOOK_PACKAGE_PATH)) continue;
                out.putNextEntry(new JarEntry(entry.getName()));
                zip.getInputStream(entry).transferTo(out);
                out.closeEntry();
            }
        }
        instrumentation.appendToBootstrapClassLoaderSearch(new JarFile(hookJar.toFile()));
    }
}
