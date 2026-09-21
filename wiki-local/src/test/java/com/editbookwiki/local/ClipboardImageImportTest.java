package com.editbookwiki.local;

import java.awt.datatransfer.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ClipboardImageImportTest {
    @TempDir Path root;

    @Test void bitmapIsStoredByHashWithTransparencyAndTemporaryFileIsRemoved() throws Exception {
        BufferedImage image = new BufferedImage(3, 2, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(1, 1, 0x80443322);
        Transferable data = pixels(image);
        assertTrue(ClipboardImageImport.hasImage(data));
        LocalImageAssetStore store = new LocalImageAssetStore(root.resolve("store"));
        var imported = ClipboardImageImport.importImage(data, root.resolve("tmp"), store::importImage);
        assertEquals(3, imported.width());
        assertEquals(2, imported.height());
        assertEquals(0x80443322, ImageIO.read(imported.storedFile().toFile()).getRGB(1, 1));
        try (var files = Files.list(root.resolve("tmp"))) { assertEquals(0, files.count()); }
    }

    @Test void failedSessionImportCleansUpAndPlainTextIsNotTreatedAsImage() throws Exception {
        assertThrows(IOException.class, () -> ClipboardImageImport.importImage(
                pixels(new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)), root.resolve("tmp"),
                path -> { throw new IOException("upload unavailable"); }));
        try (var files = Files.list(root.resolve("tmp"))) { assertEquals(0, files.count()); }
        StringSelection text = new StringSelection("normal paste");
        assertFalse(ClipboardImageImport.hasImage(text));
        assertNull(ClipboardImageImport.importImage(text, root.resolve("tmp"),
                path -> { throw new AssertionError("Must not import text"); }));
    }

    @Test void copiedImageFileUsesNormalImportAndOversizedBitmapIsRejected() throws Exception {
        Path source = root.resolve("copied.png");
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB), "png", source.toFile());
        Transferable file = new Transferable() {
            public DataFlavor[] getTransferDataFlavors() { return new DataFlavor[]{DataFlavor.javaFileListFlavor}; }
            public boolean isDataFlavorSupported(DataFlavor flavor) { return DataFlavor.javaFileListFlavor.equals(flavor); }
            public Object getTransferData(DataFlavor flavor) { return java.util.List.of(source.toFile()); }
        };
        LocalImageAssetStore store = new LocalImageAssetStore(root.resolve("store"));
        assertTrue(ClipboardImageImport.hasImage(file));
        assertNotNull(ClipboardImageImport.importImage(file, root.resolve("tmp"), store::importImage));
        assertTrue(Files.exists(source));
        assertThrows(IOException.class, () -> ClipboardImageImport.importImage(
                pixels(new BufferedImage(LocalImageAssetStore.MAX_IMAGE_EDGE + 1, 1, BufferedImage.TYPE_INT_ARGB)),
                root.resolve("tmp"), store::importImage));
    }

    private static Transferable pixels(BufferedImage image) {
        return new Transferable() {
            public DataFlavor[] getTransferDataFlavors() { return new DataFlavor[]{DataFlavor.imageFlavor}; }
            public boolean isDataFlavorSupported(DataFlavor flavor) { return DataFlavor.imageFlavor.equals(flavor); }
            public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
                if (!isDataFlavorSupported(flavor)) throw new UnsupportedFlavorException(flavor);
                return image;
            }
        };
    }
}
