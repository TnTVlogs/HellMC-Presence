package net.hellmc.presence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Servidor IPC fals sobre un socket Unix (Java 16+ també a Windows 10+) per provar handshake i activitat. */
class DiscordIpcTest {
    @Test
    void encodesLittleEndianFrames() {
        byte[] b = DiscordIpc.encode(1, "{}");
        assertEquals(1, b[0]);
        assertEquals(0, b[1]);
        assertEquals(2, b[4]);
        assertEquals(10, b.length);
    }

    @Test
    void handshakeAndSetActivity(@TempDir Path dir) throws Exception {
        Path sock = dir.resolve("ipc.sock");
        List<String> received = new CopyOnWriteArrayList<>();
        try (ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            server.bind(UnixDomainSocketAddress.of(sock));
            Thread t = new Thread(() -> {
                try (SocketChannel c = server.accept()) {
                    for (int i = 0; i < 2; i++) {
                        ByteBuffer head = readFully(c, 8);
                        int op = head.getInt();
                        int len = head.getInt();
                        received.add(op + ":" + new String(readFully(c, len).array(), StandardCharsets.UTF_8));
                        byte[] reply = DiscordIpc.encode(1, "{\"evt\":\"READY\"}");
                        c.write(ByteBuffer.wrap(reply));
                    }
                } catch (IOException ignored) {
                    // el test acaba
                }
            });
            t.start();

            SocketChannel client = SocketChannel.open(StandardProtocolFamily.UNIX);
            client.connect(UnixDomainSocketAddress.of(sock));
            try (DiscordIpc ipc = new DiscordIpc(new DiscordIpc.SocketTransport(client))) {
                ipc.handshake("424242");
                ipc.setActivity("{\"details\":\"hola\"}", 77);
            }
            t.join(5000);
        }

        assertTrue(received.size() >= 2, "received " + received);
        assertEquals("0:{\"v\":1,\"client_id\":\"424242\"}", received.get(0));
        assertTrue(received.get(1).startsWith("1:"), received.get(1));
        Map<String, Object> cmd = MiniJson.parseObject(received.get(1).substring(2));
        assertEquals("SET_ACTIVITY", cmd.get("cmd"));
        Map<?, ?> args = (Map<?, ?>) cmd.get("args");
        assertEquals(77.0, args.get("pid"));
        assertEquals("hola", ((Map<?, ?>) args.get("activity")).get("details"));
    }

    @Test
    void connectFailsCleanlyWithoutDiscord() {
        // Sense Discord obert (entorn de CI) no pot trobar cap canal; ha de fallar amb IOException, no penjar-se.
        try {
            DiscordIpc.connect("1").close();
        } catch (IOException expected) {
            return;
        }
        // Si hi ha un Discord real obert a la màquina, connectar també és vàlid.
    }

    private static ByteBuffer readFully(SocketChannel c, int len) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(len).order(ByteOrder.LITTLE_ENDIAN);
        while (b.hasRemaining()) {
            if (c.read(b) < 0) throw new IOException("closed");
        }
        b.flip();
        return b;
    }
}
