package net.hellmc.presence.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.util.CheckClassAdapter;

/** Es prova contra la classe GLFW d'LWJGL de veritat (la mateixa que carrega el joc), no contra una de mentida. */
class GlfwTransformerTest {
    private static byte[] glfwClass() throws Exception {
        try (InputStream in = GlfwTransformerTest.class.getResourceAsStream("/" + GlfwTransformer.GLFW + ".class")) {
            assertNotNull(in, "lwjgl-glfw ha d'ésser al classpath de proves");
            return in.readAllBytes();
        }
    }

    /** `nom+descriptor` de cada mètode que crida a `Hook`, repetit una vegada per crida. */
    private static List<String> hookCallers(byte[] classfile) {
        List<String> callers = new ArrayList<>();
        new ClassReader(classfile).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String method, String desc, boolean itf) {
                        if (owner.equals("net/hellmc/presence/agent/hook/Hook")) callers.add(name + descriptor + " -> " + method);
                    }
                };
            }
        }, 0);
        return callers;
    }

    private static String verify(byte[] classfile) {
        StringWriter out = new StringWriter();
        CheckClassAdapter.verify(new ClassReader(classfile), GlfwTransformerTest.class.getClassLoader(), false, new PrintWriter(out));
        return out.toString();
    }

    @Test
    void hooksEveryWayOfSettingTheTitleAndTheIcon() throws Exception {
        byte[] original = glfwClass();
        assertEquals(List.of(), hookCallers(original));

        byte[] patched = GlfwTransformer.patch(original, false);

        assertNotNull(patched);
        List<String> callers = hookCallers(patched);
        assertEquals(GlfwTransformer.EXPECTED_PATCHES, callers.size(), callers.toString());
        assertTrue(callers.contains("glfwSetWindowTitle(JLjava/lang/CharSequence;)V -> title"), callers.toString());
        assertTrue(callers.contains("glfwSetWindowTitle(JLjava/nio/ByteBuffer;)V -> titleBytes"), callers.toString());
        assertTrue(callers.contains("glfwCreateWindow(IILjava/lang/CharSequence;JJ)J -> title"), callers.toString());
        assertTrue(callers.contains("glfwCreateWindow(IILjava/nio/ByteBuffer;JJ)J -> titleBytes"), callers.toString());
        assertTrue(callers.contains("glfwSetWindowIcon(JLorg/lwjgl/glfw/GLFWImage$Buffer;)V -> icon"), callers.toString());
    }

    /** El bytecode reescrit ha de passar el verificador igual que l'original (mateixos tipus a la pila i als locals). */
    @Test
    void rewrittenClassStillVerifies() throws Exception {
        byte[] original = glfwClass();
        String before = verify(original);
        String after = verify(GlfwTransformer.patch(original, false));
        assertEquals(before, after);
    }

    @Test
    void doesNotMarkTheAgentActiveWhenAskedNotTo() throws Exception {
        System.clearProperty(GlfwTransformer.ACTIVE_PROPERTY);
        GlfwTransformer.patch(glfwClass(), false);
        assertEquals(null, System.getProperty(GlfwTransformer.ACTIVE_PROPERTY));
    }
}
