package net.hellmc.presence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class IconImagesTest {
    @Test
    void loadsEverySizeAsRgbaWithTransparentCorners() {
        List<IconImages.Rgba> images = IconImages.load();
        assertEquals(IconImages.SIZES.length, images.size());
        for (IconImages.Rgba image : images) {
            assertEquals(image.width() * image.height() * 4, image.pixels().remaining());
            assertEquals(image.width(), image.height());
        }
        IconImages.Rgba big = images.get(images.size() - 1);
        assertEquals(256, big.width());
        // cantonada superior esquerra transparent (icona arrodonida), centre opac
        assertEquals(0, big.pixels().get(3) & 0xff);
        int center = (128 * 256 + 128) * 4;
        assertTrue((big.pixels().get(center + 3) & 0xff) > 200);
    }
}
