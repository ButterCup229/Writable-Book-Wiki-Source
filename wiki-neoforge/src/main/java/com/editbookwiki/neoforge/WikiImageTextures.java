package com.editbookwiki.neoforge;

import com.editbookwiki.local.ChunkedAssetReceiver;
import com.mojang.blaze3d.platform.NativeImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.loading.FMLPaths;

/** Client-only, bounded preview cache for locally stored Wiki image assets. */
final class WikiImageTextures {
    /** Avoid decoding an enormous local file straight into the Minecraft GPU. */
    private static final long MAX_PREVIEW_PIXELS = 8L * 1024 * 1024;
    private static final Path ASSET_DIRECTORY = FMLPaths.CONFIGDIR.get()
            .resolve(EditBookWikiMod.MOD_ID).resolve("assets");
    private static final Map<String, Optional<LoadedImage>> CACHE = new ConcurrentHashMap<>();
    /*
     * A document layout keeps the resolved pixel size of every image. Asset
     * bytes can arrive after that layout first drew a placeholder, so the
     * screen needs an observable invalidation token.
     */
    private static long generation;

    private WikiImageTextures() {
    }

    static Optional<LoadedImage> get(String assetHash) {
        return CACHE.computeIfAbsent(assetHash, WikiImageTextures::load);
    }

    /** Returns a token that changes whenever image cache contents change. */
    static long generation() {
        return generation;
    }

    /**
     * An import may replace a previously missing preview with the same hash.
     * Forget the negative (or stale) lookup so the shared painter retries on
     * its next frame instead of permanently displaying a missing-image label.
     */
    static void invalidate(String assetHash) {
        // Re-importing the same asset must replace its GPU copy too.  Leaving
        // the old DynamicTexture registered both leaked GPU memory and could
        // keep rendering an obsolete, filtered texture until the client was
        // restarted.
        Optional<LoadedImage> removed = CACHE.remove(assetHash);
        if (removed != null) {
            removed.ifPresent(image ->
                    Minecraft.getInstance().getTextureManager().release(image.texture()));
        }
        generation++;
    }

    /**
     * Commits one server-verified image to the client's private cache.  The
     * UI only ever reads this cache; world images are never read directly
     * from a client-selected source path.
     */
    static ChunkedAssetReceiver beginReceivedAsset(String assetHash, int totalBytes, int chunkCount, int chunkBytes)
            throws IOException {
        return ChunkedAssetReceiver.create(ASSET_DIRECTORY, "received-", assetHash, totalBytes, chunkCount, chunkBytes);
    }

    /**
     * Moves a completed, stream-verified temporary image into the local
     * content-addressed cache. The caller has already checked its SHA-256
     * incrementally while receiving network chunks.
     */
    static boolean cacheReceivedAsset(String assetHash, Path temporary) {
        if (assetHash == null || !assetHash.matches("[0-9a-f]{64}") || temporary == null
                || !Files.isRegularFile(temporary)) {
            return false;
        }
        try {
            Files.createDirectories(ASSET_DIRECTORY);
            Path destination = ASSET_DIRECTORY.resolve(assetHash + ".asset");
            boolean completed = false;
            try {
                try {
                    Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException exception) {
                    Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
                }
                completed = true;
            } finally {
                if (!completed) {
                    Files.deleteIfExists(temporary);
                }
            }
            invalidate(assetHash);
            return true;
        } catch (IOException exception) {
            return false;
        }
    }

    private static Optional<LoadedImage> load(String assetHash) {
        Path asset = ASSET_DIRECTORY.resolve(assetHash + ".asset");
        if (!Files.isRegularFile(asset)) {
            return Optional.empty();
        }
        try {
            ImageSize size = readSize(asset);
            if ((long) size.width * size.height > MAX_PREVIEW_PIXELS) {
                // The original file remains safely stored and is still part of
                // the document; the UI shows a readable placeholder instead
                // of allocating a potentially crashing full-size texture.
                return Optional.empty();
            }
            try (InputStream input = Files.newInputStream(asset)) {
                NativeImage pixels = NativeImage.read(input);
                DynamicTexture texture = new DynamicTexture(pixels);
                ResourceLocation location = Minecraft.getInstance().getTextureManager().register(
                        EditBookWikiMod.MOD_ID + "/wiki/" + assetHash, texture);
                // These are photographs and screenshots, not pixel-art
                // sprites. Most imported captures are wider than a document
                // column and must be reduced to fit it; nearest-neighbour
                // sampling discarded whole strokes/letters and looked like a
                // low-resolution thumbnail. Linear sampling preserves the
                // original source pixels while producing a usable downscale.
                texture.setFilter(true, false);
                return Optional.of(new LoadedImage(location, size.width, size.height));
            }
        } catch (IOException | RuntimeException ignored) {
            // A missing/corrupt local asset cannot make a saved document
            // unreadable. The caller renders a small placeholder instead.
            return Optional.empty();
        }
    }

    private static ImageSize readSize(Path asset) throws IOException {
        try (ImageInputStream input = ImageIO.createImageInputStream(asset.toFile())) {
            if (input == null) {
                throw new IOException("Image data cannot be read");
            }
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new IOException("Unsupported image data");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                return new ImageSize(reader.getWidth(0), reader.getHeight(0));
            } finally {
                reader.dispose();
            }
        }
    }

    record LoadedImage(ResourceLocation texture, int width, int height) {
    }

    private record ImageSize(int width, int height) {
    }
}
