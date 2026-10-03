package net.hellmc.presence;

import java.io.Closeable;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Client mínim del protocol IPC local de Discord (handshake + SET_ACTIVITY), sense dependències.
 * Windows: pipe amb nom {@code \\?\pipe\discord-ipc-N}. Linux/macOS: socket Unix {@code discord-ipc-N}
 * a {@code $XDG_RUNTIME_DIR}/{@code $TMPDIR}/… (també les carpetes de Snap i Flatpak).
 *
 * <p>Marc d'un paquet: {@code int32 LE opcode, int32 LE longitud, JSON UTF-8}.
 */
class DiscordIpc implements Closeable {
    static final int OP_HANDSHAKE = 0;
    static final int OP_FRAME = 1;
    static final int OP_CLOSE = 2;

    /** Transport abstracte (pipe de Windows o socket Unix; també un socket de prova). */
    interface Transport extends Closeable {
        void write(byte[] data) throws IOException;

        /** Llegeix exactament {@code len} bytes (bloqueja). */
        byte[] read(int len) throws IOException;
    }

    private final Transport transport;

    DiscordIpc(Transport transport) {
        this.transport = transport;
    }

    /** Obre el primer canal {@code discord-ipc-0..9} disponible; llança {@link IOException} si cap. */
    static DiscordIpc connect(String clientId) throws IOException {
        IOException last = null;
        for (int n = 0; n < 10; n++) {
            for (String candidate : candidates(n)) {
                try {
                    DiscordIpc ipc = new DiscordIpc(open(candidate));
                    ipc.handshake(clientId);
                    return ipc;
                } catch (IOException | RuntimeException e) {
                    last = e instanceof IOException io ? io : new IOException(e);
                }
            }
        }
        throw last != null ? last : new IOException("Discord IPC not found");
    }

    static List<String> candidates(int n) {
        String name = "discord-ipc-" + n;
        List<String> out = new ArrayList<>();
        if (isWindows()) {
            out.add("\\\\?\\pipe\\" + name); // String, no Path: Path.of() rebutja aquest prefix a Windows
            return out;
        }
        List<String> bases = new ArrayList<>();
        for (String env : new String[] {"XDG_RUNTIME_DIR", "TMPDIR", "TMP", "TEMP"}) {
            String v = System.getenv(env);
            if (v != null && !v.isEmpty()) bases.add(v);
        }
        bases.add("/tmp");
        for (String base : bases) {
            out.add(Path.of(base, name).toString());
            out.add(Path.of(base, "snap.discord", name).toString());
            out.add(Path.of(base, "app", "com.discordapp.Discord", name).toString());
            out.add(Path.of(base, "app", "com.discordapp.DiscordCanary", name).toString());
        }
        return out;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static Transport open(String location) throws IOException {
        if (isWindows()) {
            return new PipeTransport(new RandomAccessFile(location, "rw"));
        }
        Path path = Path.of(location);
        if (!Files.exists(path)) throw new IOException("no socket " + path);
        SocketChannel ch = SocketChannel.open(StandardProtocolFamily.UNIX);
        try {
            ch.connect(UnixDomainSocketAddress.of(path));
        } catch (IOException e) {
            ch.close();
            throw e;
        }
        return new SocketTransport(ch);
    }

    void handshake(String clientId) throws IOException {
        send(OP_HANDSHAKE, "{\"v\":1,\"client_id\":" + MiniJson.quote(clientId) + "}");
        Frame reply = readFrame();
        if (reply.opcode == OP_CLOSE) throw new IOException("Discord rejected handshake: " + reply.json);
    }

    /** {@code activityJson} = objecte d'activitat, o {@code null} per esborrar-la. */
    void setActivity(String activityJson, long pid) throws IOException {
        String body = "{\"cmd\":\"SET_ACTIVITY\",\"args\":{\"pid\":" + pid + ",\"activity\":"
            + (activityJson == null ? "null" : activityJson) + "},\"nonce\":" + MiniJson.quote(UUID.randomUUID().toString()) + "}";
        send(OP_FRAME, body);
        readFrame(); // resposta (o error); es consumeix perquè el canal no s'ompli
    }

    void send(int opcode, String json) throws IOException {
        transport.write(encode(opcode, json));
    }

    static byte[] encode(int opcode, String json) {
        byte[] payload = json.getBytes(StandardCharsets.UTF_8);
        ByteBuffer b = ByteBuffer.allocate(8 + payload.length).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(opcode).putInt(payload.length).put(payload);
        return b.array();
    }

    record Frame(int opcode, String json) {}

    Frame readFrame() throws IOException {
        ByteBuffer head = ByteBuffer.wrap(transport.read(8)).order(ByteOrder.LITTLE_ENDIAN);
        int opcode = head.getInt();
        int len = head.getInt();
        if (len < 0 || len > (1 << 20)) throw new IOException("bad frame length " + len);
        return new Frame(opcode, new String(transport.read(len), StandardCharsets.UTF_8));
    }

    @Override
    public void close() {
        try {
            send(OP_CLOSE, "{}");
        } catch (IOException ignored) {
            // ja tancat
        }
        try {
            transport.close();
        } catch (IOException ignored) {
            // ja tancat
        }
    }

    private static final class PipeTransport implements Transport {
        private final RandomAccessFile pipe;

        PipeTransport(RandomAccessFile pipe) {
            this.pipe = pipe;
        }

        @Override
        public void write(byte[] data) throws IOException {
            pipe.write(data);
        }

        @Override
        public byte[] read(int len) throws IOException {
            byte[] buf = new byte[len];
            pipe.readFully(buf);
            return buf;
        }

        @Override
        public void close() throws IOException {
            pipe.close();
        }
    }

    static final class SocketTransport implements Transport {
        private final SocketChannel ch;

        SocketTransport(SocketChannel ch) {
            this.ch = ch;
        }

        @Override
        public void write(byte[] data) throws IOException {
            ByteBuffer b = ByteBuffer.wrap(data);
            while (b.hasRemaining()) ch.write(b);
        }

        @Override
        public byte[] read(int len) throws IOException {
            ByteBuffer b = ByteBuffer.allocate(len);
            while (b.hasRemaining()) {
                if (ch.read(b) < 0) throw new IOException("connection closed");
            }
            return b.array();
        }

        @Override
        public void close() throws IOException {
            ch.close();
        }
    }
}
