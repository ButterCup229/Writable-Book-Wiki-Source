package com.writablebookwiki.neoforge;

import com.writablebookwiki.core.book.BookPointer;
import com.writablebookwiki.core.book.BookRevision;
import com.writablebookwiki.core.document.DocumentMetadata;
import com.writablebookwiki.core.document.Paragraph;
import com.writablebookwiki.core.document.TextRun;
import com.writablebookwiki.core.document.TextStyle;
import com.writablebookwiki.core.document.WikiDocument;
import com.writablebookwiki.core.document.WikiDocumentCodec;
import com.writablebookwiki.local.ChunkedAssetReceiver;
import com.writablebookwiki.local.LocalDocumentStore;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

/** Server-only repository for actual world-owned editable-book documents. */
final class WorldBookRepository {
    private static final String DOCUMENT_DIRECTORY = "documents";
    private static final String ASSET_DIRECTORY = "assets";
    private static final int MAX_IMAGE_EDGE = 16_384;
    private static final long MAX_IMAGE_PIXELS = 64L * 1024L * 1024L;

    private final WorldBookIndexData index;
    private final Path documentDirectory;
    private final Path assetDirectory;
    private final WikiDocumentCodec codec = new WikiDocumentCodec();

    private WorldBookRepository(WorldBookIndexData index, Path documentDirectory, Path assetDirectory) {
        this.index = index;
        this.documentDirectory = documentDirectory;
        this.assetDirectory = assetDirectory;
    }

    static WorldBookRepository forLevel(ServerLevel level) {
        Path worldRoot = level.getServer().getWorldPath(LevelResource.ROOT);
        // A physical book travels between dimensions. Its index must therefore
        // be world-scoped rather than duplicated per dimension.
        return forRoot(worldRoot, WorldBookIndexData.get(level.getServer().overworld()));
    }

    static WorldBookRepository forRoot(Path worldRoot, WorldBookIndexData index) {
        Path root = com.writablebookwiki.local.StoragePaths.dataRoot(worldRoot);
        return new WorldBookRepository(index, root.resolve(DOCUMENT_DIRECTORY),
                root.resolve(ASSET_DIRECTORY));
    }

    BookRevision create(ServerPlayer player) throws IOException {
        UUID authorId = player.getUUID();
        Instant now = Instant.now();
        WikiDocument document = new WikiDocument(WikiDocument.CURRENT_FORMAT_VERSION, "未命名编辑书",
                List.of(new Paragraph(List.of(new TextRun("", TextStyle.DEFAULT)))),
                new DocumentMetadata(authorId, player.getGameProfile().getName(), now,
                        authorId, player.getGameProfile().getName(), now));
        BookPointer pointer = new BookPointer(UUID.randomUUID(), codec.contentHash(document), false);
        BookRevision revision = new BookRevision(pointer, document);
        saveNew(revision);
        return revision;
    }

    Optional<BookRevision> load(BookPointer pointer) {
        try {
            Path path = documentPath(pointer.bookId());
            if (!Files.isRegularFile(path)) {
                return Optional.empty();
            }
            byte[] stored = Files.readAllBytes(path);
            WikiDocument document = codec.decode(stored);
            String currentHash = codec.contentHash(document);
            if (!pointer.contentHash().equals(currentHash)) {
                // A v2/v3 physical pointer contains the historical content
                // hash, including its old format header. Decode() migrates
                // that document to the current format, so only a hash of the
                // original stored bytes can prove it is the same book.
                if (pointer.contentHash().equals(codec.storedContentHash(stored))) {
                    BookPointer migrated = new BookPointer(pointer.bookId(), currentHash, pointer.locked());
                    // LocalDocumentStore first writes a temporary sibling and
                    // only then atomically replaces this file. Crucially, the
                    // legacy pointer is recorded only after that succeeds, so
                    // a write failure leaves the old file fully recoverable.
                    new LocalDocumentStore(path).save(document);
                    index.registerMigration(pointer.bookId(), currentHash, pointer.contentHash());
                    return Optional.of(new BookRevision(migrated, document));
                }

                // The current file may already have been migrated after
                // another physical copy was opened. Such an old pointer is
                // accepted only when this world index has a precise alias
                // written by that earlier verified migration.
                if (index.hasLegacyAlias(pointer.bookId(), currentHash, pointer.contentHash())) {
                    return Optional.of(new BookRevision(
                            new BookPointer(pointer.bookId(), currentHash, pointer.locked()), document));
                }
                return Optional.empty();
            }
            // The ItemStack pointer and the on-disk document are the source of
            // truth.  Older development builds stored this small index per
            // dimension, so a perfectly valid book could cross a dimension
            // and be reported as damaged solely because its index entry was
            // not present in the overworld.  Once both real pieces verify,
            // restore the world-scoped index instead of rejecting the book.
            index.register(pointer.bookId(), pointer.contentHash());
            return Optional.of(new BookRevision(pointer, document));
        } catch (IOException | IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    void saveNew(BookRevision revision) throws IOException {
        new LocalDocumentStore(documentPath(revision.pointer().bookId())).save(revision.document());
        index.register(revision.pointer().bookId(), revision.pointer().contentHash());
    }

    /** Starts a disk-backed, ordered receiver for one incoming image upload. */
    ChunkedAssetReceiver beginAssetTransfer(String assetHash, int totalBytes, int chunkCount, int chunkBytes)
            throws IOException {
        requireAssetHash(assetHash);
        Files.createDirectories(assetDirectory);
        return ChunkedAssetReceiver.create(assetDirectory, "upload-", assetHash, totalBytes, chunkCount, chunkBytes);
    }

    /** Moves a completed, hash-verified upload into world-owned asset storage. */
    void saveReceivedAsset(String assetHash, ChunkedAssetReceiver transfer) throws IOException {
        requireAssetHash(assetHash);
        Path temporary = transfer.finish().orElseThrow(() -> new IOException("图片校验值不匹配"));
        boolean completed = false;
        try {
            validateImage(temporary);
            Files.createDirectories(assetDirectory);
            Path destination = assetPath(assetHash);
            if (Files.isRegularFile(destination)) {
                Files.deleteIfExists(temporary);
                completed = true;
                return;
            }
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, destination);
            } catch (java.nio.file.FileAlreadyExistsException ignored) {
                // Content-addressed storage: an equivalent verified upload won the race.
                Files.deleteIfExists(temporary);
            }
            completed = true;
        } finally {
            if (!completed) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    Optional<Path> assetPathIfPresent(String assetHash) {
        try {
            Path asset = assetPath(assetHash);
            return Files.isRegularFile(asset) ? Optional.of(asset) : Optional.empty();
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private Path documentPath(UUID bookId) {
        return documentDirectory.resolve(bookId + ".ewd");
    }

    private Path assetPath(String assetHash) {
        requireAssetHash(assetHash);
        return assetDirectory.resolve(assetHash + ".asset");
    }

    private static void requireAssetHash(String assetHash) {
        if (assetHash == null || !assetHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("图片资源标识无效");
        }
    }

    private static void validateImage(Path imageFile) throws IOException {
        try (ImageInputStream input = ImageIO.createImageInputStream(imageFile.toFile())) {
            if (input == null) {
                throw new IOException("无法读取图片内容");
            }
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new IOException("只支持 PNG、JPG 或 JPEG 图片");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                String format = reader.getFormatName().toLowerCase(java.util.Locale.ROOT);
                if (!(format.equals("png") || format.equals("jpeg") || format.equals("jpg"))) {
                    throw new IOException("只支持 PNG、JPG 或 JPEG 图片");
                }
                int imageWidth = reader.getWidth(0);
                int imageHeight = reader.getHeight(0);
                if (imageWidth <= 0 || imageHeight <= 0 || imageWidth > MAX_IMAGE_EDGE || imageHeight > MAX_IMAGE_EDGE
                        || (long) imageWidth * imageHeight > MAX_IMAGE_PIXELS) {
                    throw new IOException("图片像素尺寸不安全");
                }
            } finally {
                reader.dispose();
            }
        }
    }
}
