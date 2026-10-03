package net.hellmc.presence;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Llegeix el que s'afegeix a {@code logs/latest.log} (sondeig, sense dependències). Si el fitxer es
 * trunca/rota (el joc es reinicia) es reobre des del principi.
 */
final class LogTailer {
    private final Path file;
    private long position;
    private final StringBuilder partial = new StringBuilder();

    LogTailer(Path file) {
        this.file = file;
    }

    /** Línies completes noves des de l'última crida (buit si res ha canviat o el fitxer no hi és). */
    List<String> poll() {
        List<String> lines = new ArrayList<>();
        try {
            if (!Files.exists(file)) return lines;
            long size = Files.size(file);
            if (size < position) {
                position = 0; // rotació
                partial.setLength(0);
            }
            if (size == position) return lines;
            try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
                raf.seek(position);
                byte[] buf = new byte[(int) Math.min(size - position, 1 << 20)];
                raf.readFully(buf);
                position += buf.length;
                partial.append(new String(buf, StandardCharsets.UTF_8));
            }
            int nl;
            while ((nl = partial.indexOf("\n")) >= 0) {
                lines.add(partial.substring(0, nl).replace("\r", ""));
                partial.delete(0, nl + 1);
            }
        } catch (IOException ignored) {
            // el joc pot tenir el fitxer bloquejat un instant; es torna a provar al proper sondeig
        }
        return lines;
    }
}
