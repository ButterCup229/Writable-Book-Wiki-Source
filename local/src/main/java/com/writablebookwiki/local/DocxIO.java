package com.writablebookwiki.local;

import com.writablebookwiki.core.document.DocumentMetadata;
import com.writablebookwiki.core.document.WikiDocument;
import com.writablebookwiki.local.docx.DocxArchive;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** Stable interchange entry point; OOXML details live together in the docx feature package. */
public final class DocxIO {
    private DocxIO() { }

    @FunctionalInterface public interface ImageImporter { ImportedImageAsset importImage(Path source) throws IOException; }
    @FunctionalInterface public interface ImageResolver { Path resolve(String hash) throws IOException; }
    public record ImportResult(WikiDocument document, List<String> warnings) {
        public ImportResult { warnings = List.copyOf(warnings); }
    }


    public static ImportResult read(Path source, DocumentMetadata metadata, ImageImporter images) throws IOException {
        return DocxArchive.read(source, metadata, images);
    }

    public static List<String> write(Path destination, WikiDocument document, ImageResolver images) throws IOException {
        return DocxArchive.write(destination, document, images);
    }
}
