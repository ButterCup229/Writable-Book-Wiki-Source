package com.writablebookwiki.local;

import com.writablebookwiki.core.document.DocumentAnchorMapping;
import com.writablebookwiki.core.document.TextSelection;
import com.writablebookwiki.core.document.WikiDocument;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Private anchor history with the same lifetime as the document's undo roots. */
public final class AnnotationEditHistory {
    private final Map<WikiDocument, List<LocalTextAnnotation>> snapshots = new IdentityHashMap<>();

    /** A comment edit is independent of body undo: never resurrect an older comment's text. */
    public void commentsChanged() { snapshots.clear(); }

    public List<LocalTextAnnotation> rebase(WikiDocument before, WikiDocument after,
            List<LocalTextAnnotation> annotations, List<WikiDocument> retained) {
        if (before == after) return annotations;
        snapshots.putIfAbsent(before, List.copyOf(annotations));
        List<LocalTextAnnotation> mapped = snapshots.get(after);
        if (mapped == null) {
            DocumentAnchorMapping mapping = new DocumentAnchorMapping(before, after);
            mapped = annotations.stream().map(note -> {
                TextSelection range = mapping.map(new TextSelection(note.startBlock(), note.startColumn(),
                        note.endBlock(), note.endColumn()));
                return new LocalTextAnnotation(range.startLine(), range.startColumn(), range.endLine(),
                        range.endColumn(), "", note.comment());
            }).toList();
        }
        snapshots.put(after, mapped);
        Map<WikiDocument, Boolean> roots = new IdentityHashMap<>();
        retained.forEach(document -> roots.put(document, true));
        snapshots.keySet().removeIf(document -> !roots.containsKey(document));
        return mapped;
    }
}
