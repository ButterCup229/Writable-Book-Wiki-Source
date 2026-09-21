package com.writablebookwiki.local;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalImageAssetStoreTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void importsPngByHashWithoutRetainingTheOriginalPath() throws IOException {
        Path source = temporaryDirectory.resolve("source.png");
        writeImage(source, "png", 3, 2);
        LocalImageAssetStore store = new LocalImageAssetStore(temporaryDirectory.resolve("store"));

        ImportedImageAsset imported = store.importImage(source);
        ImportedImageAsset importedAgain = store.importImage(source);

        assertEquals(64, imported.assetHash().length());
        assertEquals("image/png", imported.mediaType());
        assertEquals(3, imported.width());
        assertEquals(2, imported.height());
        assertTrue(Files.isRegularFile(imported.storedFile()));
        assertFalse(imported.storedFile().equals(source));
        assertEquals(imported, importedAgain);
        try (var files = Files.list(temporaryDirectory.resolve("store/assets"))) {
            assertEquals(1, files.count());
        }
    }

    @Test
    void rejectsUnsupportedFilenameAndDisguisedData() throws IOException {
        LocalImageAssetStore store = new LocalImageAssetStore(temporaryDirectory.resolve("store"));
        Path wrongExtension = temporaryDirectory.resolve("image.gif");
        Files.writeString(wrongExtension, "not an image");
        Path disguisedImage = temporaryDirectory.resolve("image.png");
        Files.writeString(disguisedImage, "not an image");

        assertThrows(IOException.class, () -> store.importImage(wrongExtension));
        assertThrows(IOException.class, () -> store.importImage(disguisedImage));
    }

    private static void writeImage(Path output, String format, int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, Color.ORANGE.getRGB());
        assertTrue(ImageIO.write(image, format, output.toFile()));
    }
}
