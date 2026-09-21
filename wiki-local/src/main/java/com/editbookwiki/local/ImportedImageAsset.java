package com.editbookwiki.local;

import java.nio.file.Path;
import java.util.Objects;

/** Immutable result of safely copying a local PNG/JPEG into an asset store. */
public record ImportedImageAsset(String assetHash, String mediaType, int width, int height, Path storedFile) {
    public ImportedImageAsset {
        assetHash = Objects.requireNonNull(assetHash, "assetHash");
        mediaType = Objects.requireNonNull(mediaType, "mediaType");
        storedFile = Objects.requireNonNull(storedFile, "storedFile");
        if (assetHash.length() != 64 || !assetHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("assetHash must be a SHA-256 hex value");
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("image dimensions must be positive");
        }
    }
}
