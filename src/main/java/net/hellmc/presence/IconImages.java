package net.hellmc.presence;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/** Icones de la finestra (PNG dins el jar) com a píxels RGBA, sense dependre de LWJGL (així es pot provar). */
final class IconImages {
    static final int[] SIZES = {16, 32, 48, 64, 128, 256};

    /** Una imatge RGBA de {@code width x height} (píxels de 4 bytes, de dalt a baix) en un buffer directe. */
    record Rgba(int width, int height, ByteBuffer pixels) {}

    private IconImages() {}

    static List<Rgba> load() {
        List<Rgba> images = new ArrayList<>();
        for (int size : SIZES) {
            try (InputStream in = IconImages.class.getResourceAsStream("/hellmc-icon-" + size + ".png")) {
                if (in == null) continue;
                BufferedImage image = ImageIO.read(in);
                if (image != null) images.add(toRgba(image));
            } catch (IOException | RuntimeException e) {
                // una mida que no es pot llegir se salta; amb cap, no hi ha icona
            }
        }
        return images;
    }

    static Rgba toRgba(BufferedImage image) {
        int w = image.getWidth();
        int h = image.getHeight();
        ByteBuffer buffer = ByteBuffer.allocateDirect(w * h * 4);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = image.getRGB(x, y);
                buffer.put((byte) (argb >> 16)).put((byte) (argb >> 8)).put((byte) argb).put((byte) (argb >>> 24));
            }
        }
        buffer.flip();
        return new Rgba(w, h, buffer);
    }
}
