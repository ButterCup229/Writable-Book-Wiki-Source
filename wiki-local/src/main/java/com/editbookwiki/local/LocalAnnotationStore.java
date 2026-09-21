package com.editbookwiki.local;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Private, client-local reader annotations.
 *
 * <p>This service intentionally stores only plain text under the local
 * configuration directory. It is not a document block, is never included in
 * a {@code WikiDocument}, and therefore cannot be sent to a server when the
 * shared book is saved.</p>
 */
public final class LocalAnnotationStore {
    private static final String COMMENTS_HEADER = "EDITBOOKWIKI-COMMENTS-1";
    private final Path directory;

    public LocalAnnotationStore(Path directory) {
        this.directory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
    }

    /** Loads an empty annotation when the current player has not written one yet. */
    public String load(String documentKey) throws IOException {
        Path annotationFile = annotationFile(documentKey);
        if (!Files.isRegularFile(annotationFile)) {
            return "";
        }
        return Files.readString(annotationFile, StandardCharsets.UTF_8);
    }

    /** Atomically replaces one private annotation. */
    public void save(String documentKey, String annotation) throws IOException {
        Objects.requireNonNull(annotation, "annotation");
        writeAtomically(annotationFile(documentKey), annotation);
    }

    /** Loads local, range-anchored review comments for one document version. */
    public List<LocalTextAnnotation> loadTextAnnotations(String documentKey) throws IOException {
        Path commentFile = commentFile(documentKey);
        if (!Files.isRegularFile(commentFile)) {
            return List.of();
        }
        List<String> lines = Files.readAllLines(commentFile, StandardCharsets.UTF_8);
        if (lines.isEmpty() || !COMMENTS_HEADER.equals(lines.getFirst())) {
            throw new IOException("Unsupported local comment file format");
        }
        List<LocalTextAnnotation> annotations = new ArrayList<>();
        for (int index = 1; index < lines.size(); index++) {
            String line = lines.get(index);
            if (line.isEmpty()) {
                continue;
            }
            String[] fields = line.split("\\t", -1);
            if (fields.length != 6) {
                throw new IOException("Malformed local comment entry");
            }
            try {
                annotations.add(new LocalTextAnnotation(
                        Integer.parseInt(fields[0]), Integer.parseInt(fields[1]),
                        Integer.parseInt(fields[2]), Integer.parseInt(fields[3]),
                        decode(fields[4]), decode(fields[5])));
            } catch (IllegalArgumentException exception) {
                throw new IOException("Malformed local comment entry", exception);
            }
        }
        return List.copyOf(annotations);
    }

    /** Atomically replaces all private review comments for one document version. */
    public static String versionKey(String documentKey, String contentHash) {
        if (contentHash == null || !contentHash.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Invalid document content hash");
        return UUID.nameUUIDFromBytes(documentKey.getBytes(StandardCharsets.UTF_8)) + "-" + contentHash;
    }

    /** One-time legacy migration; an explicitly empty version must not revive deleted comments. */
    public List<LocalTextAnnotation> loadVersionedTextAnnotations(String documentKey, String contentHash) throws IOException {
        String version = versionKey(documentKey, contentHash);
        if (Files.isRegularFile(commentFile(version))) return loadTextAnnotations(version);
        List<LocalTextAnnotation> legacy = loadTextAnnotations(documentKey);
        saveTextAnnotations(version, legacy);
        return legacy;
    }

    /** Called only for a server-acknowledged fork of this exact content snapshot. */
    public void copyVersionedTextAnnotations(String source, String target, String contentHash) throws IOException {
        String sourceKey = versionKey(source, contentHash);
        if (Files.isRegularFile(commentFile(sourceKey)))
            saveTextAnnotations(versionKey(target, contentHash), loadTextAnnotations(sourceKey));
    }

    public void saveTextAnnotations(String documentKey, List<LocalTextAnnotation> annotations) throws IOException {
        Objects.requireNonNull(annotations, "annotations");
        StringBuilder encoded = new StringBuilder(COMMENTS_HEADER).append('\n');
        for (LocalTextAnnotation annotation : annotations) {
            LocalTextAnnotation nonNull = Objects.requireNonNull(annotation, "annotation entry");
            encoded.append(nonNull.startBlock()).append('\t')
                    .append(nonNull.startColumn()).append('\t')
                    .append(nonNull.endBlock()).append('\t')
                    .append(nonNull.endColumn()).append('\t')
                    .append(encode(nonNull.quotedText())).append('\t')
                    .append(encode(nonNull.comment())).append('\n');
        }
        writeAtomically(commentFile(documentKey), encoded.toString());
    }

    /**
     * Carries one player's private comments forward when their held book is
     * copy-on-write forked.  The source comments are never sent to the world
     * and are intentionally retained for the older physical copy as well.
     */
    public void copyTextAnnotations(String sourceDocumentKey, String targetDocumentKey) throws IOException {
        Objects.requireNonNull(sourceDocumentKey, "sourceDocumentKey");
        Objects.requireNonNull(targetDocumentKey, "targetDocumentKey");
        if (sourceDocumentKey.equals(targetDocumentKey)) {
            return;
        }
        List<LocalTextAnnotation> source = loadTextAnnotations(sourceDocumentKey);
        if (!source.isEmpty()) {
            saveTextAnnotations(targetDocumentKey, source);
        }
    }

    private void writeAtomically(Path annotationFile, String annotation) throws IOException {
        Files.createDirectories(directory);
        Path temporary = Files.createTempFile(directory, annotationFile.getFileName().toString(), ".part");
        boolean completed = false;
        try {
            Files.writeString(temporary, annotation, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, annotationFile, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, annotationFile, StandardCopyOption.REPLACE_EXISTING);
            }
            completed = true;
        } finally {
            if (!completed) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    private Path annotationFile(String documentKey) {
        return fileFor(documentKey, ".txt");
    }

    private Path commentFile(String documentKey) {
        return fileFor(documentKey, ".comments");
    }

    private Path fileFor(String documentKey, String suffix) {
        String safeKey = Objects.requireNonNull(documentKey, "documentKey");
        if (!safeKey.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new IllegalArgumentException("Annotation key contains unsupported characters");
        }
        return directory.resolve(safeKey + suffix);
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }
}
