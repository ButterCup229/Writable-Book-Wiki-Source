package com.writablebookwiki.local;

import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import javax.imageio.ImageIO;

/** Converts clipboard pixels to a temporary PNG, then uses the session's normal asset import. */
public final class ClipboardImageImport {
    private ClipboardImageImport() { }

    @FunctionalInterface
    public interface Importer {
        ImportedImageAsset importImage(Path source) throws IOException;
    }

    public static boolean hasImage(Transferable contents) throws IOException, UnsupportedFlavorException {
        return contents != null && (contents.isDataFlavorSupported(DataFlavor.imageFlavor)
                || imageFile(contents) != null);
    }

    public static ImportedImageAsset importImage(Transferable contents, Path temporaryDirectory, Importer importer)
            throws IOException, UnsupportedFlavorException {
        if (contents == null) return null;
        if (!contents.isDataFlavorSupported(DataFlavor.imageFlavor)) {
            Path file = imageFile(contents);
            return file == null ? null : importer.importImage(file);
        }
        Object data = contents.getTransferData(DataFlavor.imageFlavor);
        if (!(data instanceof Image image)) throw new IOException("剪贴板中的图片无法读取");
        int width = image.getWidth(null), height = image.getHeight(null);
        if (width <= 0 || height <= 0 || width > LocalImageAssetStore.MAX_IMAGE_EDGE
                || height > LocalImageAssetStore.MAX_IMAGE_EDGE
                || (long) width * height > LocalImageAssetStore.MAX_IMAGE_PIXELS) {
            throw new IOException("剪贴板图片尺寸无效或过大");
        }
        BufferedImage pixels = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = pixels.createGraphics();
        try {
            graphics.setComposite(java.awt.AlphaComposite.Src);
            if (!graphics.drawImage(image, 0, 0, null)) throw new IOException("剪贴板图片尚未准备好，请重新复制");
        } finally {
            graphics.dispose();
        }
        Files.createDirectories(temporaryDirectory);
        Path temporary = Files.createTempFile(temporaryDirectory, "clipboard-", ".png");
        try {
            if (!ImageIO.write(pixels, "png", temporary.toFile())) throw new IOException("无法保存剪贴板图片");
            return importer.importImage(temporary);
        } finally {
            Files.deleteIfExists(temporary);
            pixels.flush();
        }
    }

    private static Path imageFile(Transferable contents) throws IOException, UnsupportedFlavorException {
        if (!contents.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) return null;
        Object data = contents.getTransferData(DataFlavor.javaFileListFlavor);
        if (!(data instanceof List<?> files) || files.size() != 1 || !(files.getFirst() instanceof File file)) return null;
        String name = file.getName().toLowerCase(Locale.ROOT);
        return name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg") ? file.toPath() : null;
    }
}
