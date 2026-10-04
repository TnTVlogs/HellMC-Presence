package net.hellmc.presence.agent;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Reescriu {@code org.lwjgl.glfw.GLFW} en carregar-se perquè totes les maneres de posar el títol o la icona d'una
 * finestra passin abans per {@code Hook}. Minecraft ho fa sempre per aquí, sigui quin sigui el loader i la versió
 * (els noms ofuscats de Minecraft no hi intervenen), de manera que el títol i la icona de HellMC són els únics que
 * arriben a existir: no hi ha cap instant, ni a l'arrencada, en què es vegi «Minecraft».
 *
 * <p>Només s'insereix, a l'inici de cada mètode, una crida que substitueix l'argument pel que torna {@code Hook}; el
 * cos original no es toca, així que sense fitxer del launcher (o si {@code Hook} falla) tot es comporta com sempre.
 */
final class GlfwTransformer implements ClassFileTransformer {
    static final String GLFW = "org/lwjgl/glfw/GLFW";
    private static final String HOOK = "net/hellmc/presence/agent/hook/Hook";
    private static final String HOOK_CLASS = "net.hellmc.presence.agent.hook.Hook";
    private static final String IMAGE_BUFFER = "org/lwjgl/glfw/GLFWImage$Buffer";

    /** Propietat que el mod mira per deixar de fer el títol/icona pel seu compte (vegeu {@code WindowTitle}). */
    static final String ACTIVE_PROPERTY = "hellmc.presence.agent";

    private static final String TITLE_CS = "(Ljava/lang/CharSequence;)Ljava/lang/CharSequence;";
    private static final String TITLE_BB = "(Ljava/nio/ByteBuffer;)Ljava/nio/ByteBuffer;";

    /** Mètodes de GLFW que s'han de reescriure: nom + descriptor (l'argument a canviar és sempre el de l'índex 2). */
    private static final Set<String> TITLE_METHODS = Set.of(
            "glfwSetWindowTitle(JLjava/lang/CharSequence;)V",
            "glfwCreateWindow(IILjava/lang/CharSequence;JJ)J");
    private static final Set<String> TITLE_BYTES_METHODS = Set.of(
            "glfwSetWindowTitle(JLjava/nio/ByteBuffer;)V",
            "glfwCreateWindow(IILjava/nio/ByteBuffer;JJ)J");
    private static final String ICON_METHOD = "glfwSetWindowIcon(JL" + IMAGE_BUFFER + ";)V";
    static final int EXPECTED_PATCHES = TITLE_METHODS.size() + TITLE_BYTES_METHODS.size() + 1;

    private final Instrumentation instrumentation;

    GlfwTransformer(Instrumentation instrumentation) {
        this.instrumentation = instrumentation;
    }

    @Override
    public byte[] transform(Module module, ClassLoader loader, String className, Class<?> redefined, ProtectionDomain domain, byte[] classfile) {
        if (!GLFW.equals(className)) return null;
        try {
            byte[] patched = patch(classfile, true);
            if (patched != null) allowAccessToHook(module);
            return patched;
        } catch (Throwable t) {
            System.err.println("[HellMC-Presence] No s'ha pogut reescriure GLFW; el títol i la icona de la finestra es posaran sense l'agent: " + t);
            return null;
        }
    }

    /**
     * @param markActive si s'han reescrit tots els mètodes, avisa el mod (propietat) que ja no cal que ho faci ell
     * @return la classe reescrita, o {@code null} si no s'ha pogut (llavors el joc segueix amb la seva)
     */
    static byte[] patch(byte[] classfile, boolean markActive) {
        ClassReader reader = new ClassReader(classfile);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        int[] patched = {0};
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                String id = name + descriptor;
                if (TITLE_METHODS.contains(id)) return titlePatch(mv, "title", TITLE_CS, patched);
                if (TITLE_BYTES_METHODS.contains(id)) return titlePatch(mv, "titleBytes", TITLE_BB, patched);
                if (ICON_METHOD.equals(id)) return iconPatch(mv, patched);
                return mv;
            }
        }, 0);
        if (patched[0] != EXPECTED_PATCHES) {
            System.err.println("[HellMC-Presence] GLFW no té els mètodes esperats (" + patched[0] + "/" + EXPECTED_PATCHES + "); l'agent no la modifica.");
            return null;
        }
        if (markActive) System.setProperty(ACTIVE_PROPERTY, "true");
        return writer.toByteArray();
    }

    /** {@code title = Hook.title(title)} a l'inici del mètode (el títol és sempre l'argument de l'índex 2). */
    private static MethodVisitor titlePatch(MethodVisitor mv, String hookMethod, String hookDescriptor, int[] patched) {
        return new MethodVisitor(Opcodes.ASM9, mv) {
            @Override
            public void visitCode() {
                super.visitCode();
                super.visitVarInsn(Opcodes.ALOAD, 2);
                super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, hookMethod, hookDescriptor, false);
                super.visitVarInsn(Opcodes.ASTORE, 2);
                patched[0]++;
            }
        };
    }

    /** {@code images = (GLFWImage.Buffer) Hook.icon(GLFW.class, images)} a l'inici de {@code glfwSetWindowIcon}. */
    private static MethodVisitor iconPatch(MethodVisitor mv, int[] patched) {
        return new MethodVisitor(Opcodes.ASM9, mv) {
            @Override
            public void visitCode() {
                super.visitCode();
                super.visitLdcInsn(Type.getObjectType(GLFW));
                super.visitVarInsn(Opcodes.ALOAD, 2);
                super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "icon", "(Ljava/lang/Class;Ljava/lang/Object;)Ljava/lang/Object;", false);
                super.visitTypeInsn(Opcodes.CHECKCAST, IMAGE_BUFFER);
                super.visitVarInsn(Opcodes.ASTORE, 2);
                patched[0]++;
            }
        };
    }

    /**
     * Si LWJGL és un mòdul amb nom (Forge/NeoForge amb mòduls), no «llegeix» el classpath d'arrencada on viu {@code Hook};
     * se li afegeix la lectura perquè la crida inserida no falli amb {@code IllegalAccessError}.
     */
    private void allowAccessToHook(Module glfwModule) {
        if (glfwModule == null || !glfwModule.isNamed()) return;
        try {
            Module hookModule = Class.forName(HOOK_CLASS, false, null).getModule();
            instrumentation.redefineModule(glfwModule, Set.of(hookModule), Map.of(), Map.of(), Set.of(), Map.of());
        } catch (Throwable t) {
            System.err.println("[HellMC-Presence] No s'ha pogut donar accés al mòdul de GLFW: " + t);
        }
    }
}
