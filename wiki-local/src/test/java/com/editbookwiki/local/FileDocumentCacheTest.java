package com.editbookwiki.local;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.editbookwiki.core.book.BookPointer;
import com.editbookwiki.core.document.DocumentMetadata;
import com.editbookwiki.core.document.Paragraph;
import com.editbookwiki.core.document.TextRun;
import com.editbookwiki.core.document.TextStyle;
import com.editbookwiki.core.document.WikiDocument;
import com.editbookwiki.core.document.WikiDocumentCodec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileDocumentCacheTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void cacheOnlyReturnsADocumentMatchingTheExactPointerHash() throws Exception {
        WikiDocument document = document("缓存正文");
        BookPointer pointer = pointer(document);
        FileDocumentCache cache = new FileDocumentCache(temporaryDirectory.resolve("book-cache"));

        assertTrue(cache.load(pointer).isEmpty());
        cache.save(pointer, document);
        assertEquals(document, cache.load(pointer).orElseThrow());
        assertTrue(Files.isRegularFile(temporaryDirectory.resolve("book-cache")
                .resolve(pointer.contentHash() + ".ewd")));

        BookPointer wrongHash = new BookPointer(pointer.bookId(), "0".repeat(64), pointer.locked());
        assertTrue(cache.load(wrongHash).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> cache.save(wrongHash, document));
    }

    @Test
    void malformedCacheDataBecomesAMissInsteadOfBreakingTheBook() throws Exception {
        WikiDocument document = document("可恢复正文");
        BookPointer pointer = pointer(document);
        Path directory = temporaryDirectory.resolve("book-cache");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve(pointer.contentHash() + ".ewd"), "not a wiki document");

        assertTrue(new FileDocumentCache(directory).load(pointer).isEmpty());
    }

    private static BookPointer pointer(WikiDocument document) {
        return new BookPointer(new UUID(4, 5), new WikiDocumentCodec().contentHash(document), false);
    }

    private static WikiDocument document(String text) {
        UUID author = new UUID(2, 3);
        return new WikiDocument(WikiDocument.CURRENT_FORMAT_VERSION, "缓存测试",
                List.of(new Paragraph(List.of(new TextRun(text, TextStyle.DEFAULT)))),
                new DocumentMetadata(author, "开发者", Instant.EPOCH, author, "开发者", Instant.EPOCH));
    }
}
