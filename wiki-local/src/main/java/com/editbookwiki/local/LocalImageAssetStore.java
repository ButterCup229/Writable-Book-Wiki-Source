package com.editbookwiki.local;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Locale;
import java.util.Objects;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/**
 * Copies player-selected PNG/JPEG files into a content-addressed local store.
 *
 * <p>Importing never keeps the original path. Data is copied in bounded chunks
 * to a temporary file while SHA-256 is calculated, then validated from that
 * copy before becoming a reusable asset. It intentionally does not decode a
 * full image into memory; header-only dimension checks reject pixel bombs.</p>
 */
public final class LocalImageAssetStore {
    public static final int MAX_IMAGE_EDGE = 16_384;
    public static final long MAX_IMAGE_PIXELS = 64L * 1024 * 1024;

    private static final int COPY_BUFFER_BYTES = 64 * 1024;

    private final Path assetDirectory;
    private final Path temporaryDirectory;

    public LocalImageAssetStore(Path rootDirectory) {
        Path root = Objects.requireNonNull(rootDirectory, "rootDirectory").toAbsolutePath().normalize();
        this.assetDirectory = root.resolve("assets");
        this.temporaryDirectory = root.resolve("temporary");
    }

    public ImportedImageAsset importImage(Path source) throws IOException {
        Path sourceFile = Objects.requireNonNull(source, "source").toAbsolutePath().normalize();
        requireSupportedFilename(sourceFile);
        if (!Files.isRegularFile(sourceFile)) {
            throw new IOException("Image source is not a regular file");
        }

        Files.createDirectories(assetDirectory);
        Files.createDirectories(temporaryDirectory);
        Path temporaryFile = Files.createTempFile(temporaryDirectory, "import-", ".part");
        boolean completed = false;
        try {
            String hash = copyAndHash(sourceFile, temporaryFile);
            ImageInfo info = inspect(temporaryFile);
            Path destination = assetDirectory.resolve(hash + ".asset");
            moveIntoStore(temporaryFile, destination);
            completed = true;
            return new ImportedImageAsset(hash, info.mediaType, info.width, info.height, destination);
        } finally {
            if (!completed) {
                Files.deleteIfExists(temporaryFile);
            }
        }
    }

    private static String copyAndHash(Path source, Path temporary) throws IOException {
        MessageDigest digest = sha256();
        byte[] buffer = new byte[COPY_BUFFER_BYTES];
        try (InputStream input = Files.newInputStream(source); OutputStream output = Files.newOutputStream(temporary)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) {
                    continue;
                }
                digest.update(buffer, 0, read);
                output.write(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static ImageInfo inspect(Path imageFile) throws IOException {
        try (ImageInputStream input = ImageIO.createImageInputStream(imageFile.toFile())) {
            if (input == null) {
                throw new IOException("Image data cannot be read");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new IOException("Only PNG and JPEG images are supported");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                String mediaType = switch (format) {
                    case "png" -> "image/png";
                    case "jpeg", "jpg" -> "image/jpeg";
                    default -> throw new IOException("Only PNG and JPEG images are supported");
                };
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                validateDimensions(width, height);
                return new ImageInfo(mediaType, width, height);
            } finally {
                reader.dispose();
            }
        }
    }

    private static void validateDimensions(int width, int height) throws IOException {
        if (width <= 0 || height <= 0 || width > MAX_IMAGE_EDGE || height > MAX_IMAGE_EDGE
                || (long) width * height > MAX_IMAGE_PIXELS) {
            throw new IOException("Image dimensions are not safe to decode");
        }
    }

    private static void moveIntoStore(Path temporary, Path destination) throws IOException {
        if (Files.exists(destination)) {
            Files.deleteIfExists(temporary);
            return;
        }
        try {
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            try {
                Files.move(temporary, destination);
            } catch (java.nio.file.FileAlreadyExistsException ignored) {
                Files.deleteIfExists(temporary);
            }
        } catch (java.nio.file.FileAlreadyExistsException ignored) {
            Files.deleteIfExists(temporary);
        }
    }

    private static void requireSupportedFilename(Path source) throws IOException {
        String fileName = source.getFileName() == null ? "" : source.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!(fileName.endsWith(".png") || fileName.endsWith(".jpg") || fileName.endsWith(".jpeg"))) {
            throw new IOException("Please select a PNG, JPG, or JPEG image");
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record ImageInfo(String mediaType, int width, int height) {
    }
}
