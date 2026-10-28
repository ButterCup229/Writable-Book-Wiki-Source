package com.writablebookwiki.local.annotation;

import com.writablebookwiki.core.document.PlainTextEditor;
import com.writablebookwiki.core.document.WikiDocument;
import com.writablebookwiki.core.document.WikiDocumentCodec;
import com.writablebookwiki.local.AnnotationEditHistory;
import com.writablebookwiki.local.LocalAnnotationStore;
import com.writablebookwiki.local.LocalTextAnnotation;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * One document's local review state. No Minecraft types, focus routing or shared-book writes.
 * An unsuccessful write leaves the draft and accepted entries intact so closing can retry safely.
 */
public final class AnnotationWorkspace {
    public interface Storage {
        List<LocalTextAnnotation> load(String namespace, String hash) throws IOException;
        void save(String versionKey, List<LocalTextAnnotation> entries) throws IOException;
    }

    public record Anchor(int startBlock, int startColumn, int endBlock, int endColumn, String quotedText) {
        public Anchor {
            new LocalTextAnnotation(startBlock, startColumn, endBlock, endColumn, "", "");
            quotedText = ""; // Source text is only an anchor, never part of the comment.
        }
    }

    private static final Comparator<LocalTextAnnotation> ORDER = Comparator
            .comparingInt(LocalTextAnnotation::startBlock).thenComparingInt(LocalTextAnnotation::startColumn)
            .thenComparingInt(LocalTextAnnotation::endBlock).thenComparingInt(LocalTextAnnotation::endColumn)
            .thenComparing(LocalTextAnnotation::quotedText).thenComparing(LocalTextAnnotation::comment);
    private final Storage storage;
    private final PlainTextEditor input = new PlainTextEditor();
    private final AnnotationEditHistory history = new AnnotationEditHistory();
    private List<LocalTextAnnotation> entries = List.of();
    // Keep history in stable entry order; the visible list may reorder when anchors move.
    private List<LocalTextAnnotation> historyEntries = List.of();
    private final AnnotationEditHistory draftHistory = new AnnotationEditHistory();
    private WikiDocument document;
    private IOException loadFailure;
    private Anchor anchor;
    private int editingIndex = -1;
    private String acceptedText = "";
    private String cachedNamespace;
    private WikiDocument cachedDocument;
    private String cachedKey;

    public AnnotationWorkspace(WikiDocument document, String namespace, LocalAnnotationStore store) {
        this(document, namespace, new Storage() {
            public List<LocalTextAnnotation> load(String key, String hash) throws IOException {
                return store.loadVersionedTextAnnotations(key, hash);
            }
            public void save(String key, List<LocalTextAnnotation> entries) throws IOException {
                store.saveTextAnnotations(key, entries);
            }
        });
    }

    public AnnotationWorkspace(WikiDocument document, String namespace, Storage storage) {
        this.document = Objects.requireNonNull(document);
        this.storage = Objects.requireNonNull(storage);
        try {
            entries = sorted(storage.load(namespace, new WikiDocumentCodec().contentHash(document)));
            historyEntries = entries;
        } catch (IOException | IllegalArgumentException failure) {
            // Do not replace unreadable existing comments with a silently empty list.
            loadFailure = new IOException("Local comments could not be loaded; existing file is preserved", failure);
        }
    }

    public List<LocalTextAnnotation> entries() { return entries; }
    public PlainTextEditor input() { return input; }
    public boolean editing() { return anchor != null; }
    public Anchor anchor() { return anchor; }
    public int editingIndex() { return editingIndex; }
    public boolean loadFailed() { return loadFailure != null; }
    public boolean draftChanged() { return editing() && !acceptedText.equals(input.text()); }

    /** Caller must close the previous editor first; a failed draft must never be replaced. */
    public void begin(Anchor selected) {
        if (editing()) throw new IllegalStateException("Close the current comment first");
        anchor = Objects.requireNonNull(selected);
        editingIndex = -1;
        acceptedText = "";
        input.reset("");
        draftHistory.commentsChanged();
    }

    public void edit(int index) {
        if (editing()) throw new IllegalStateException("Close the current comment first");
        LocalTextAnnotation note = entries.get(index);
        anchor = new Anchor(note.startBlock(), note.startColumn(), note.endBlock(), note.endColumn(), "");
        editingIndex = index;
        acceptedText = note.comment();
        input.reset(acceptedText);
    }

    /** Persist a changed draft before accepting its new ordering and index. */
    public void persistDraft(String namespace) throws IOException {
        if (!draftChanged()) return;
        requireReadable();
        List<LocalTextAnnotation> next = new ArrayList<>(entries);
        LocalTextAnnotation saved = null;
        if (input.text().isBlank()) {
            if (editingIndex >= 0) next.remove(editingIndex);
        } else {
            saved = new LocalTextAnnotation(anchor.startBlock(), anchor.startColumn(), anchor.endBlock(),
                    anchor.endColumn(), "", input.text());
            if (editingIndex >= 0) next.set(editingIndex, saved);
            else next.add(saved);
        }
        List<LocalTextAnnotation> ordered = sorted(next);
        storage.save(storageKey(namespace), ordered);
        history.commentsChanged();
        entries = ordered;
        historyEntries = entries;
        draftHistory.commentsChanged();
        editingIndex = saved == null ? -1 : entries.indexOf(saved);
        acceptedText = input.text();
    }

    /** IOException means the editor remains open with its entire draft and undo history. */
    public void close(String namespace) throws IOException {
        persistDraft(namespace);
        anchor = null;
        editingIndex = -1;
        acceptedText = "";
        input.reset("");
    }

    /** Commit draft anchors for this content version before the shared body save is requested. */
    public void saveVersion(String namespace) throws IOException {
        requireReadable();
        persistDraft(namespace);
        storage.save(storageKey(namespace), entries);
    }

    public void rebase(WikiDocument next, List<WikiDocument> retainedDocuments) {
        if (document == next) return;
        int trackedIndex = editingIndex < 0 ? -1 : historyEntries.indexOf(entries.get(editingIndex));
        List<LocalTextAnnotation> draft = editing() && trackedIndex < 0
                ? draftHistory.rebase(document, next, List.of(new LocalTextAnnotation(anchor.startBlock(),
                    anchor.startColumn(), anchor.endBlock(), anchor.endColumn(), "", "")), retainedDocuments)
                : List.of();
        historyEntries = history.rebase(document, next, historyEntries, retainedDocuments);
        entries = sorted(historyEntries);
        if (editing()) {
            LocalTextAnnotation moved = trackedIndex >= 0 ? historyEntries.get(trackedIndex) : draft.getFirst();
            anchor = new Anchor(moved.startBlock(), moved.startColumn(), moved.endBlock(), moved.endColumn(), "");
            editingIndex = trackedIndex < 0 ? -1 : entries.indexOf(moved);
        }
        document = next;
    }

    private String storageKey(String namespace) {
        if (cachedDocument != document || !Objects.equals(cachedNamespace, namespace)) {
            cachedKey = LocalAnnotationStore.versionKey(namespace, new WikiDocumentCodec().contentHash(document));
            cachedNamespace = namespace;
            cachedDocument = document;
        }
        return cachedKey;
    }

    private void requireReadable() throws IOException {
        if (loadFailure != null) throw new IOException(loadFailure.getMessage(), loadFailure);
    }

    private static List<LocalTextAnnotation> sorted(List<LocalTextAnnotation> source) {
        return source.stream().sorted(ORDER).toList();
    }
}
