package com.editbookwiki.local;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.editbookwiki.core.document.DocumentMetadata;
import com.editbookwiki.core.document.Paragraph;
import com.editbookwiki.core.document.TextRun;
import com.editbookwiki.core.document.TextStyle;
import com.editbookwiki.core.document.WikiDocument;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalDocumentStoreTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void savesAndReloadsTheLatestCompleteDocument() throws Exception {
        LocalDocumentStore store = new LocalDocumentStore(temporaryDirectory.resolve("notes").resolve("preview.ewd"));
        WikiDocument first = document("第一次");
        WikiDocument second = document("第二次");

        assertTrue(store.load().isEmpty());
        store.save(first);
        assertEquals(first, store.load().orElseThrow());

        store.save(second);
        assertEquals(second, store.load().orElseThrow());
        assertTrue(Files.isRegularFile(temporaryDirectory.resolve("notes").resolve("preview.ewd")));
    }

    private static WikiDocument document(String text) {
        UUID owner = new UUID(2, 3);
        return new WikiDocument(
                WikiDocument.CURRENT_FORMAT_VERSION,
                "本地预览",
                List.of(new Paragraph(List.of(new TextRun(text, TextStyle.DEFAULT)))),
                new DocumentMetadata(owner, "开发者", Instant.EPOCH, owner, "开发者", Instant.EPOCH));
    }
}
