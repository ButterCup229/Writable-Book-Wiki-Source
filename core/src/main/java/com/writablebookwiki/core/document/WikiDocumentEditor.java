package com.writablebookwiki.core.document;

import static com.writablebookwiki.core.document.TextRunEdits.*;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.text.Collator;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * Platform-independent editing commands for one {@link WikiDocument}.
 *
 * <p>This is deliberately not a UI control. Minecraft, a local note screen,
 * and a future plugin-backed editor can all use the same immutable document
 * updates, selection-based styling, and ten-step undo/redo behaviour.</p>
 */
public final class WikiDocumentEditor {
    public static final int DEFAULT_HISTORY_LIMIT = 10;

    private final int historyLimit;
    private final Deque<WikiDocument> undo = new ArrayDeque<>();
    private final Deque<WikiDocument> redo = new ArrayDeque<>();
    private WikiDocument document;
    private boolean coalescingTextEdit;

    public WikiDocumentEditor(WikiDocument document) {
        this(document, DEFAULT_HISTORY_LIMIT);
    }

    public WikiDocumentEditor(WikiDocument document, int historyLimit) {
        this.document = Objects.requireNonNull(document, "document");
        if (historyLimit <= 0) {
            throw new IllegalArgumentException("historyLimit must be positive");
        }
        this.historyLimit = historyLimit;
    }

    public WikiDocument document() {
        return document;
    }

    /** Imports a complete document as one undoable change, retaining this book's ownership metadata. */
    public boolean importDocument(WikiDocument imported) {
        return replaceDocument(new WikiDocument(WikiDocument.CURRENT_FORMAT_VERSION, imported.title(),
                imported.blocks(), document.metadata()), false);
    }

    public boolean canUndo() {
        return !undo.isEmpty();
    }

    public boolean canRedo() {
        return !redo.isEmpty();
    }

    /** Finishes the current continuous typing run before the next command. */
    public void finishTextEdit() {
        coalescingTextEdit = false;
    }

    /** Replaces the title as ordinary continuous typing. */
    public boolean replaceTitle(String title) {
        Objects.requireNonNull(title, "title");
        return replaceDocument(new WikiDocument(document.formatVersion(), title, document.blocks(), document.metadata()), true);
    }

    /**
     * Replaces editor lines after ordinary typing, paste, delete, or newline.
     * Consecutive calls are deliberately coalesced into one undo step until a
     * formatting command, save, focus change, undo, or redo ends the run.
     */
    public boolean replaceLines(List<String> lines) {
        Objects.requireNonNull(lines, "lines");
        List<String> checkedLines = List.copyOf(lines);
        for (String line : checkedLines) {
            Objects.requireNonNull(line, "line");
        }
        if (checkedLines.isEmpty()) {
            checkedLines = List.of("");
        }

        List<Block> oldBlocks = document.blocks();
        int prefix = equalLinePrefix(oldBlocks, checkedLines);
        int suffix = equalLineSuffix(oldBlocks, checkedLines, prefix);
        List<Block> result = new ArrayList<>(checkedLines.size());
        result.addAll(oldBlocks.subList(0, prefix));

        int oldChangedEnd = oldBlocks.size() - suffix;
        int newChangedEnd = checkedLines.size() - suffix;
        for (int newIndex = prefix; newIndex < newChangedEnd; newIndex++) {
            int oldIndex = prefix + (newIndex - prefix);
            Block oldBlock = oldIndex < oldChangedEnd ? oldBlocks.get(oldIndex) : null;
            result.add(replaceBlockText(oldBlock, checkedLines.get(newIndex)));
        }
        if (suffix > 0) {
            result.addAll(oldBlocks.subList(oldChangedEnd, oldBlocks.size()));
        }

        return replaceDocument(new WikiDocument(document.formatVersion(), document.title(), result, document.metadata()), true);
    }

    /**
     * Replaces only one authored page while leaving the other pages and their
     * explicit boundaries untouched. UI controls can therefore show a real
     * page at a time without turning page navigation into a scroll operation.
     */
    public boolean replacePageLines(int pageIndex, List<String> lines) {
        Objects.requireNonNull(lines, "lines");
        if (pageIndex < 0) {
            return false;
        }
        List<String> checkedLines = List.copyOf(lines);
        for (String line : checkedLines) {
            Objects.requireNonNull(line, "line");
        }
        if (checkedLines.isEmpty()) {
            checkedLines = List.of("");
        }
        final DocumentPages.PageRange pageRange;
        try {
            pageRange = DocumentPages.range(document, pageIndex);
        } catch (IndexOutOfBoundsException exception) {
            return false;
        }
        int start = pageRange.startInclusive();
        int end = pageRange.endExclusive();
        List<Block> result = new ArrayList<>(document.blocks().size() + checkedLines.size() - (end - start));
        result.addAll(document.blocks().subList(0, start));
        for (int index = 0; index < checkedLines.size(); index++) {
            Block oldBlock = start + index < end ? document.blocks().get(start + index) : null;
            result.add(replaceBlockText(oldBlock, checkedLines.get(index)));
        }
        result.addAll(document.blocks().subList(end, document.blocks().size()));
        return replaceDocument(new WikiDocument(document.formatVersion(), document.title(), result, document.metadata()), true);
    }

    /**
     * Replaces the editable text rows of one page without allowing a
     * line-oriented native text widget to turn structural blocks into text.
     *
     * <p>The UI supplies one exact {@code structuralLineMarker} row for every
     * structural block on the page.  It is deliberately not document content:
     * it is an identity anchor which survives Enter, paste and edits before an
     * image.  This keeps a picture in place even though the underlying
     * {@code TextArea} only understands a list of strings.  Deleting a picture
     * must still go through {@link #deleteImageBlock(int)} rather than by
     * deleting this marker row.</p>
     */
    public boolean replacePageLinesPreservingStructuralBlocks(int pageIndex, List<String> lines,
            String structuralLineMarker) {
        Objects.requireNonNull(lines, "lines");
        if (pageIndex < 0 || structuralLineMarker == null || structuralLineMarker.isEmpty()) {
            return false;
        }
        List<String> checkedLines = List.copyOf(lines);
        for (String line : checkedLines) {
            Objects.requireNonNull(line, "line");
        }
        if (checkedLines.isEmpty()) {
            checkedLines = List.of("");
        }
        final DocumentPages.PageRange pageRange;
        try {
            pageRange = DocumentPages.range(document, pageIndex);
        } catch (IndexOutOfBoundsException exception) {
            return false;
        }

        List<Block> oldPage = document.blocks().subList(pageRange.startInclusive(), pageRange.endExclusive());
        boolean hasStructuralBlock = oldPage.stream().anyMatch(block -> !(block instanceof TextualBlock));
        if (!hasStructuralBlock) {
            return replacePageLines(pageIndex, checkedLines);
        }

        List<List<Block>> oldTextGroups = new ArrayList<>();
        List<Block> structuralBlocks = new ArrayList<>();
        List<Block> currentOldText = new ArrayList<>();
        for (Block block : oldPage) {
            if (block instanceof TextualBlock) {
                currentOldText.add(block);
            } else {
                oldTextGroups.add(List.copyOf(currentOldText));
                currentOldText.clear();
                structuralBlocks.add(block);
            }
        }
        oldTextGroups.add(List.copyOf(currentOldText));

        List<List<String>> newTextGroups = new ArrayList<>();
        List<String> currentNewText = new ArrayList<>();
        for (String line : checkedLines) {
            if (structuralLineMarker.equals(line) && newTextGroups.size() < structuralBlocks.size()) {
                newTextGroups.add(List.copyOf(currentNewText));
                currentNewText.clear();
            } else if (!structuralLineMarker.equals(line)) {
                currentNewText.add(line);
            }
        }
        newTextGroups.add(List.copyOf(currentNewText));
        while (newTextGroups.size() < structuralBlocks.size() + 1) {
            newTextGroups.add(List.of());
        }

        List<Block> result = new ArrayList<>(document.blocks().size() + checkedLines.size() - oldPage.size());
        result.addAll(document.blocks().subList(0, pageRange.startInclusive()));
        for (int group = 0; group < structuralBlocks.size(); group++) {
            appendRewrittenTextGroup(result, oldTextGroups.get(group), newTextGroups.get(group));
            result.add(structuralBlocks.get(group));
        }
        appendRewrittenTextGroup(result, oldTextGroups.getLast(), newTextGroups.getLast());
        result.addAll(document.blocks().subList(pageRange.endExclusive(), document.blocks().size()));
        return replaceDocument(new WikiDocument(document.formatVersion(), document.title(), result, document.metadata()),
                true);
    }

    /**
     * Replaces a character range inside one ordinary text block.
     *
     * <p>This is the primitive used by a native character-at-a-time editor.
     * It deliberately refuses structural blocks, so typing near an image or a
     * table can never turn that node into plain text. Consecutive invocations
     * coalesce into one undo entry exactly like {@link #replaceLines(List)}.</p>
     */
    public boolean replaceTextInBlock(int blockIndex, int startColumn, int endColumn, String replacement) {
        Objects.requireNonNull(replacement, "replacement");
        if (blockIndex < 0 || blockIndex >= document.blocks().size()
                || !(document.blocks().get(blockIndex) instanceof TextualBlock)) {
            return false;
        }
        Block oldBlock = document.blocks().get(blockIndex);
        String oldText = blockText(oldBlock);
        int start = safeBoundary(oldText, startColumn, true);
        int end = safeBoundary(oldText, endColumn, false);
        if (start > end) {
            int previousStart = start;
            start = end;
            end = previousStart;
        }
        List<TextRun> runs = replaceRunsText(oldBlock.runs(), oldText, start, end, replacement);
        Block updatedBlock = withRuns((TextualBlock) oldBlock, runs);
        if (updatedBlock.equals(oldBlock)) {
            return false;
        }
        List<Block> result = new ArrayList<>(document.blocks());
        result.set(blockIndex, updatedBlock);
        return replaceDocument(new WikiDocument(
                document.formatVersion(), document.title(), result, document.metadata()), true);
    }

    /**
     * Replaces a selection that may cover several adjacent text blocks.
     *
     * <p>Line breaks in {@code replacement} create normal paragraphs. The
     * first surviving block keeps its original paragraph/heading/quote type;
     * later pasted lines are ordinary paragraphs. Structural blocks are an
     * explicit protection boundary: a selection that crosses an image, table
     * or page break is rejected instead of silently converting or removing it.
     * This gives native cut/paste Word-like behaviour without reintroducing
     * the old unsafe plain-text editor.</p>
     */
    public boolean replaceTextSelection(TextSelection selection, String replacement) {
        return replaceTextSelection(selection, replacement, null);
    }

    /** Explicit insertion style supports choosing formatting before typing. */
    public boolean replaceTextSelection(TextSelection selection, String replacement, TextStyle insertionStyle) {
        Objects.requireNonNull(selection, "selection");
        Objects.requireNonNull(replacement, "replacement");
        NormalizedSelection normalized = normalizeSelection(selection);
        if (normalized == null) {
            return false;
        }
        for (int index = normalized.startLine; index <= normalized.endLine; index++) {
            if (!(document.blocks().get(index) instanceof TextualBlock)) {
                return false;
            }
        }
        Block first = document.blocks().get(normalized.startLine);
        Block last = document.blocks().get(normalized.endLine);
        TextualBlock firstTextual = (TextualBlock) first;
        TextualBlock lastTextual = (TextualBlock) last;
        TextStyle insertedStyle = insertionStyle != null ? insertionStyle : styleAt(firstTextual.runs(), normalized.startColumn);
        List<TextRun> prefix = new ArrayList<>();
        List<TextRun> suffix = new ArrayList<>();
        addSlice(prefix, firstTextual.runs(), 0, normalized.startColumn, null);
        addSlice(suffix, lastTextual.runs(), normalized.endColumn, blockText(last).length(), null);

        String normalizedReplacement = replacement.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalizedReplacement.split("\n", -1);
        List<Block> result = new ArrayList<>(document.blocks().size() + lines.length);
        result.addAll(document.blocks().subList(0, normalized.startLine));

        List<TextRun> firstRuns = new ArrayList<>(prefix);
        append(firstRuns, new TextRun(lines[0], insertedStyle));
        if (lines.length == 1) {
            for (TextRun run : suffix) {
                append(firstRuns, run);
            }
            if (firstRuns.isEmpty()) {
                firstRuns = defaultRuns("");
            }
            result.add(withRuns(firstTextual, firstRuns));
        } else {
            if (firstRuns.isEmpty()) {
                firstRuns = defaultRuns("");
            }
            result.add(withRuns(firstTextual, firstRuns));
            for (int index = 1; index < lines.length - 1; index++) {
                result.add(paragraphInFrame(List.of(new TextRun(lines[index], insertedStyle)), first.frame()));
            }
            List<TextRun> lastRuns = new ArrayList<>();
            append(lastRuns, new TextRun(lines[lines.length - 1], insertedStyle));
            for (TextRun run : suffix) {
                append(lastRuns, run);
            }
            if (lastRuns.isEmpty()) {
                lastRuns = defaultRuns("");
            }
            result.add(paragraphInFrame(lastRuns, first.frame()));
        }
        result.addAll(document.blocks().subList(normalized.endLine + 1, document.blocks().size()));
        return replaceDocument(new WikiDocument(
                document.formatVersion(), document.title(), result, document.metadata()), true);
    }

    /** Copies the selected runs AND their paragraph presentation without flattening soft breaks. */
    public TextFragment copyTextFragment(TextSelection selection) {
        NormalizedSelection range = normalizeSelection(selection);
        if (range == null || range.isEmpty()) return null;
        List<TextFragment.Part> result = new ArrayList<>();
        for (int i = range.startLine; i <= range.endLine; i++) {
            if (!(document.blocks().get(i) instanceof TextualBlock text)) return null;
            List<TextRun> runs = new ArrayList<>();
            String plain = blockText(text);
            int start = i == range.startLine ? range.startColumn : 0;
            int end = i == range.endLine ? range.endColumn : plain.length();
            addSlice(runs, text.runs(), start, end, null);
            // A visual wrap is not a new logical line. Partial text carries run styles only.
            boolean wholeLine = (start == 0 || plain.charAt(start - 1) == '\n')
                    && (end == plain.length() || plain.charAt(end) == '\n'
                    || end > start && plain.charAt(end - 1) == '\n');
            result.add(new TextFragment.Part(withRuns(text, runs), wholeLine));
        }
        return new TextFragment(result);
    }

    public record PasteResult(int blockIndex, int column) { }

    /** One undoable rich paste. Different paragraph formats do not consume surrounding prose. */
    public PasteResult pasteTextFragment(TextSelection selection, TextFragment fragment) {
        Objects.requireNonNull(fragment);
        NormalizedSelection range = normalizeSelection(selection);
        if (range == null) return null;
        for (int i = range.startLine; i <= range.endLine; i++)
            if (!(document.blocks().get(i) instanceof TextualBlock)) return null;
        TextualBlock first = (TextualBlock) document.blocks().get(range.startLine);
        TextualBlock last = (TextualBlock) document.blocks().get(range.endLine);
        List<TextRun> prefix = new ArrayList<>(), suffix = new ArrayList<>();
        addSlice(prefix, first.runs(), 0, range.startColumn, null);
        addSlice(suffix, last.runs(), range.endColumn, blockText(last).length(), null);
        List<TextualBlock> sources = new ArrayList<>();
        java.util.Map<BlockFrame, BlockFrame> pastedFrames = new java.util.HashMap<>();
        int nextFrame = BlockFrame.nextGroup(document.blocks());
        for (int i = 0; i < fragment.parts().size(); i++) {
            TextFragment.Part part = fragment.parts().get(i);
            TextualBlock source = part.wholeLine() ? part.block() : withRuns(i == 0 ? first : last, part.block().runs());
            if (part.wholeLine() && source.frame() != null) {
                BlockFrame old = source.frame();
                if (!pastedFrames.containsKey(old)) pastedFrames.put(old, new BlockFrame(old.kind(), nextFrame++));
                source = RichTextBlock.from(source).withFrame(pastedFrames.get(old));
            }
            sources.add(source);
        }
        List<Block> result = new ArrayList<>(document.blocks().subList(0, range.startLine));
        boolean mergePrefix = withRuns(first, List.of()).equals(withRuns(sources.getFirst(), List.of()));
        boolean mergeSuffix = withRuns(last, List.of()).equals(withRuns(sources.getLast(), List.of()));
        if (!prefix.isEmpty() && !mergePrefix) result.add(withRuns(first, prefix));
        int caretIndex = result.size(), caretColumn = 0;
        for (int i = 0; i < sources.size(); i++) {
            TextualBlock source = sources.get(i);
            List<TextRun> runs = new ArrayList<>();
            if (i == 0 && mergePrefix) runs.addAll(prefix);
            for (TextRun run : source.runs()) append(runs, run);
            caretIndex = result.size();
            caretColumn = textLength(runs);
            if (i == sources.size() - 1 && mergeSuffix)
                for (TextRun run : suffix) append(runs, run);
            result.add(withRuns(source, runs));
        }
        if (!suffix.isEmpty() && !mergeSuffix) result.add(withRuns(last, suffix));
        result.addAll(document.blocks().subList(range.endLine + 1, document.blocks().size()));
        replaceDocument(new WikiDocument(document.formatVersion(), document.title(), result, document.metadata()), false);
        return new PasteResult(caretIndex, caretColumn);
    }

    /**
     * Splits one editable block at a text boundary, creating a normal
     * paragraph for the trailing content. Images, tables and page boundaries
     * are never accepted as split targets.
     */
    public boolean splitTextBlock(int blockIndex, int column) {
        if (blockIndex < 0 || blockIndex >= document.blocks().size()
                || !(document.blocks().get(blockIndex) instanceof TextualBlock textual)) {
            return false;
        }
        String text = blockText(textual);
        int boundary = safeBoundary(text, column, true);
        List<TextRun> before = new ArrayList<>();
        List<TextRun> after = new ArrayList<>();
        addSlice(before, textual.runs(), 0, boundary, null);
        addSlice(after, textual.runs(), boundary, text.length(), null);
        List<Block> result = new ArrayList<>(document.blocks().size() + 1);
        result.addAll(document.blocks().subList(0, blockIndex));
        result.add(withRuns(textual, before));
        if (textual instanceof RichTextBlock rich && rich.headingLevel() == 0 && rich.kind() != RichTextBlock.Kind.CODE) {
            result.add(new RichTextBlock(rich.kind(), rich.alignment(), 0, false, after, rich.frame()));
        } else result.add(paragraphInFrame(after, textual.frame()));
        result.addAll(document.blocks().subList(blockIndex + 1, document.blocks().size()));
        return replaceDocument(new WikiDocument(
                document.formatVersion(), document.title(), result, document.metadata()), true);
    }

    /**
     * Joins a text block onto the end of the preceding text block.
     *
     * <p>This is the structural half of Backspace at the beginning of a
     * paragraph.  It deliberately refuses an image, table, or page boundary
     * rather than allowing a keyboard deletion to detach a document object
     * from its author-selected position.</p>
     */
    public boolean mergeTextBlockWithPrevious(int blockIndex) {
        return mergeAdjacentTextBlocks(blockIndex - 1, blockIndex);
    }

    /**
     * Joins the next text block onto the end of this text block.
     *
     * <p>This is the structural half of Delete at the end of a paragraph.
     * The first block keeps its paragraph, heading, or quote kind, matching
     * the normal editor rule that deleting a line break does not replace the
     * preceding paragraph's formatting.</p>
     */
    public boolean mergeTextBlockWithNext(int blockIndex) {
        return mergeAdjacentTextBlocks(blockIndex, blockIndex + 1);
    }

    private boolean mergeAdjacentTextBlocks(int firstIndex, int secondIndex) {
        if (firstIndex < 0 || secondIndex != firstIndex + 1 || secondIndex >= document.blocks().size()
                || !(document.blocks().get(firstIndex) instanceof TextualBlock first)
                || !(document.blocks().get(secondIndex) instanceof TextualBlock second)) {
            return false;
        }
        List<TextRun> joinedRuns = new ArrayList<>(first.runs().size() + second.runs().size());
        for (TextRun run : first.runs()) {
            append(joinedRuns, run);
        }
        for (TextRun run : second.runs()) {
            append(joinedRuns, run);
        }
        List<Block> result = new ArrayList<>(document.blocks().size() - 1);
        result.addAll(document.blocks().subList(0, firstIndex));
        result.add(withRuns(first, joinedRuns));
        result.addAll(document.blocks().subList(secondIndex + 1, document.blocks().size()));
        return replaceDocument(new WikiDocument(
                document.formatVersion(), document.title(), result, document.metadata()), true);
    }

    /** Changes paragraph presentation in one undo transaction, skipping structural objects. */
    public boolean formatParagraphs(TextSelection selection, UnaryOperator<RichTextBlock> operation) {
        NormalizedSelection range = normalizeSelection(selection);
        if (range == null) return false;
        List<Block> blocks = new ArrayList<>(document.blocks());
        for (int i = range.startLine; i <= range.endLine; i++) {
            if (i == range.endLine && i > range.startLine && range.endColumn == 0) break;
            if (blocks.get(i) instanceof TextualBlock text) {
                RichTextBlock updated = operation.apply(RichTextBlock.from(text));
                if ((updated.isTask() || updated.isList()) && text.frame() == null)
                    updated = updated.withFrame(BlockFrame.forInsertion(text, blocks));
                if (text.frame() != null && !Objects.equals(text.frame(), updated.frame())) {
                    int from = i, to = i;
                    while (from > 0 && text.frame().equals(blocks.get(from - 1).frame())) from--;
                    while (to + 1 < blocks.size() && text.frame().equals(blocks.get(to + 1).frame())) to++;
                    for (int member = from; member <= to; member++) {
                        Block value = blocks.get(member);
                        if (value instanceof RichTextBlock rich) value = new RichTextBlock(
                                rich.isTask() ? RichTextBlock.Kind.TASK : rich.isList() ? rich.kind() : RichTextBlock.Kind.NORMAL,
                                rich.alignment(), rich.headingLevel(), rich.checked(), rich.runs(), updated.frame());
                        else value = BlockFrame.attach(value, updated.frame());
                        blocks.set(member, value);
                    }
                }
                blocks.set(i, updated);
            }
        }
        return replaceDocument(new WikiDocument(document.formatVersion(), document.title(), blocks, document.metadata()), false);
    }

    /** Shift+Enter on an empty final prose member exits the callout without removing its other contents. */
    public boolean exitFrame(int index) {
        if (index < 0 || index >= document.blocks().size() || !(document.blocks().get(index) instanceof RichTextBlock text)
                || text.frame() == null || text.isTask() || text.isList() || !blockText(text).isEmpty()) return false;
        if (index + 1 < document.blocks().size() && text.frame().equals(document.blocks().get(index + 1).frame())) return false;
        List<Block> blocks = new ArrayList<>(document.blocks());
        blocks.set(index, new Paragraph(List.of()));
        return replaceDocument(new WikiDocument(document.formatVersion(), document.title(), blocks, document.metadata()), false);
    }

    private static TextualBlock paragraphInFrame(List<TextRun> runs, BlockFrame frame) {
        return frame == null ? new Paragraph(runs)
                : new RichTextBlock(RichTextBlock.Kind.NORMAL, RichTextBlock.Alignment.LEFT, 0, false, runs, frame);
    }

    public boolean toggleTask(int index) {
        if (index < 0 || index >= document.blocks().size() || !(document.blocks().get(index) instanceof RichTextBlock rich)
                || !rich.isTask()) return false;
        return formatParagraphs(new TextSelection(index, 0, index, 0),
                r -> new RichTextBlock(r.kind(), r.alignment(), r.headingLevel(), !r.checked(), r.runs(), r.frame()));
    }

    /** Turn current/selected logical lines into tasks while retaining their shared callout container. */
    public PasteResult addTasksInsideQuote(TextSelection selection) {
        return addListInsideFrame(selection, RichTextBlock.Kind.TASK);
    }

    /**
     * Recognizes a just-typed list prefix at the start of a logical line. Call only after
     * ordinary keyboard space insertion, not after paste. Conversion has its own undo
     * boundary, so undo restores the literal prefix including the triggering space.
     */
    public PasteResult convertTypedListPrefix(TextSelection selection) {
        if (selection.startLine() != selection.endLine() || selection.startColumn() != selection.endColumn()) return null;
        int index = selection.startLine(), column = selection.startColumn();
        if (index < 0 || index >= document.blocks().size()
                || !(document.blocks().get(index) instanceof TextualBlock original)) return null;
        RichTextBlock textBlock = RichTextBlock.from(original);
        if (textBlock.headingLevel() != 0 || switch (textBlock.kind()) {
            case NORMAL, QUOTE, INFO, WARNING -> false;
            default -> true;
        }) return null;
        String text = blockText(textBlock);
        if (column < 1 || column > text.length()) return null;
        int from = text.lastIndexOf('\n', column - 1) + 1;
        RichTextBlock.Kind kind = switch (text.substring(from, column)) {
            case "1. " -> RichTextBlock.Kind.NUMBERED;
            case "- ", "* " -> RichTextBlock.Kind.BULLET;
            default -> null;
        };
        if (kind == null) return null;
        BlockFrame frame = BlockFrame.forInsertion(original, document.blocks());
        textBlock = textBlock.withFrame(frame);
        int to = text.indexOf('\n', column);
        if (to < 0) to = text.length();
        List<Block> blocks = new ArrayList<>(document.blocks().subList(0, index));
        if (from > 0) {
            List<TextRun> before = new ArrayList<>();
            addSlice(before, textBlock.runs(), 0, from - 1, null);
            blocks.add(textBlock.withRuns(before));
        }
        List<TextRun> item = new ArrayList<>();
        addSlice(item, textBlock.runs(), column, to, null);
        int itemIndex = blocks.size();
        blocks.add(textBlock.withRuns(item).withPresentation(kind, true));
        if (to < text.length()) {
            List<TextRun> after = new ArrayList<>();
            addSlice(after, textBlock.runs(), to + 1, text.length(), null);
            blocks.add(textBlock.withRuns(after));
        }
        blocks.addAll(document.blocks().subList(index + 1, document.blocks().size()));
        replaceDocument(new WikiDocument(document.formatVersion(), document.title(), blocks, document.metadata()), false);
        return new PasteResult(itemIndex, 0);
    }

    /** Applies an item style to current/selected logical lines without replacing surrounding callout prose. */
    public PasteResult addListInsideFrame(TextSelection selection, RichTextBlock.Kind kind) {
        if (kind != RichTextBlock.Kind.TASK && kind != RichTextBlock.Kind.BULLET && kind != RichTextBlock.Kind.NUMBERED)
            throw new IllegalArgumentException("Not a list or task style");
        NormalizedSelection range = normalizeSelection(selection);
        if (range == null || range.startLine != range.endLine
                || !(document.blocks().get(range.startLine) instanceof TextualBlock original)) return null;
        BlockFrame frame = BlockFrame.forInsertion(original, document.blocks());
        RichTextBlock quote = RichTextBlock.from(original).withFrame(frame);
        if (frame == null || quote.kind() == kind || kind == RichTextBlock.Kind.TASK && quote.isTask()) return null;
        String text = blockText(quote);
        int from = text.lastIndexOf('\n', range.startColumn - 1) + 1;
        int endAt = range.endColumn > range.startColumn && range.endColumn > 0
                && text.charAt(range.endColumn - 1) == '\n' ? range.endColumn - 1 : range.endColumn;
        int to = text.indexOf('\n', endAt);
        if (to < 0) to = text.length();
        List<Block> blocks = new ArrayList<>(document.blocks().subList(0, range.startLine));
        if (from > 0) {
            List<TextRun> before = new ArrayList<>();
            addSlice(before, quote.runs(), 0, from - 1, null);
            blocks.add(quote.withRuns(before));
        }
        int lastIndex = blocks.size(), lastLength = 0;
        for (int start = from; start <= to;) {
            int end = text.indexOf('\n', start);
            if (end < 0 || end > to) end = to;
            List<TextRun> runs = new ArrayList<>();
            addSlice(runs, quote.runs(), start, end, null);
            lastIndex = blocks.size(); lastLength = end - start;
            blocks.add(quote.withRuns(runs).withPresentation(kind, true));
            if (end == to) break;
            start = end + 1;
        }
        if (to < text.length()) {
            List<TextRun> after = new ArrayList<>();
            addSlice(after, quote.runs(), to + 1, text.length(), null);
            blocks.add(quote.withRuns(after));
        }
        blocks.addAll(document.blocks().subList(range.endLine + 1, document.blocks().size()));
        replaceDocument(new WikiDocument(document.formatVersion(), document.title(), blocks, document.metadata()), false);
        return new PasteResult(lastIndex, lastLength);
    }

    public boolean insertHorizontalRule(TextSelection selection) {
        NormalizedSelection range = normalizeSelection(selection);
        if (range == null || range.startLine != range.endLine || !(document.blocks().get(range.startLine) instanceof TextualBlock text)) return false;
        List<TextRun> before = new ArrayList<>(), after = new ArrayList<>();
        addSlice(before, text.runs(), 0, range.startColumn, null);
        addSlice(after, text.runs(), range.endColumn, blockText(text).length(), null);
        List<Block> blocks = new ArrayList<>(document.blocks());
        blocks.set(range.startLine, withRuns(text, before));
        blocks.add(range.startLine + 1, HorizontalRule.INSTANCE);
        blocks.add(range.startLine + 2, new Paragraph(after));
        return replaceDocument(new WikiDocument(document.formatVersion(), document.title(), blocks, document.metadata()), false);
    }

    public boolean deleteHorizontalRule(int index) {
        if (index < 0 || index >= document.blocks().size() || !(document.blocks().get(index) instanceof HorizontalRule)) return false;
        List<Block> blocks = new ArrayList<>(document.blocks());
        blocks.remove(index);
        if (blocks.isEmpty()) blocks.add(new Paragraph(List.of()));
        return replaceDocument(new WikiDocument(document.formatVersion(), document.title(), blocks, document.metadata()), false);
    }

    /** Applies a style operation to the selected characters only. */
    public boolean applyStyle(TextSelection selection, UnaryOperator<TextStyle> operation) {
        Objects.requireNonNull(selection, "selection");
        Objects.requireNonNull(operation, "operation");
        NormalizedSelection normalized = normalizeSelection(selection);
        if (normalized == null || normalized.isEmpty()) {
            return false;
        }

        List<Block> result = new ArrayList<>(document.blocks().size());
        for (int line = 0; line < document.blocks().size(); line++) {
            Block block = document.blocks().get(line);
            if (line < normalized.startLine || line > normalized.endLine
                    || !(block instanceof TextualBlock textualBlock)) {
                result.add(block);
                continue;
            }
            int start = line == normalized.startLine ? normalized.startColumn : 0;
            int end = line == normalized.endLine ? normalized.endColumn : blockText(block).length();
            result.add(withRuns(textualBlock, styleRuns(block.runs(), start, end, operation)));
        }
        return replaceDocument(new WikiDocument(document.formatVersion(), document.title(), result, document.metadata()), false);
    }

    public boolean toggleBold(TextSelection selection) {
        return toggle(selection, style -> style.bold(), (style, enabled) -> style.withBold(enabled));
    }

    public boolean toggleItalic(TextSelection selection) {
        return toggle(selection, style -> style.italic(), (style, enabled) -> style.withItalic(enabled));
    }

    public boolean toggleStrikethrough(TextSelection selection) {
        return toggle(selection, style -> style.strikethrough(), (style, enabled) -> style.withStrikethrough(enabled));
    }

    public boolean setColor(TextSelection selection, int rgbColor) {
        return applyStyle(selection, style -> style.withColor(rgbColor));
    }

    public boolean setFontSize(TextSelection selection, int fontSize) {
        return applyStyle(selection, style -> style.withFontSize(fontSize));
    }

    /** Assigns one internal document target to the selected text. An empty target removes the link. */
    public boolean setLinkTarget(TextSelection selection, String target) {
        Objects.requireNonNull(selection, "selection");
        NormalizedSelection normalized = normalizeSelection(selection);
        if (normalized == null || normalized.isEmpty()) {
            return false;
        }
        String normalizedTarget = target == null || target.isBlank() ? null : target.trim();
        List<Block> result = new ArrayList<>(document.blocks().size());
        boolean changed = false;
        for (int line = 0; line < document.blocks().size(); line++) {
            Block block = document.blocks().get(line);
            if (line < normalized.startLine || line > normalized.endLine
                    || !(block instanceof TextualBlock textualBlock)) {
                result.add(block);
                continue;
            }
            int start = line == normalized.startLine ? normalized.startColumn : 0;
            int end = line == normalized.endLine ? normalized.endColumn : blockText(block).length();
            Block updated = withRuns(textualBlock, linkRuns(block.runs(), start, end, normalizedTarget));
            result.add(updated);
            changed |= !updated.equals(block);
        }
        return changed && replaceDocument(new WikiDocument(
                document.formatVersion(), document.title(), result, document.metadata()), false);
    }

    /** Turns selected text blocks into quotes, or restores them when all are already quotes. */
    public boolean toggleQuoteBlocks(TextSelection selection) {
        Objects.requireNonNull(selection, "selection");
        NormalizedSelection normalized = normalizeSelection(selection);
        if (normalized == null) {
            return false;
        }
        boolean sawTextBlock = false;
        boolean allQuotes = true;
        for (int line = normalized.startLine; line <= normalized.endLine; line++) {
            Block block = document.blocks().get(line);
            if (!(block instanceof TextualBlock)) {
                continue;
            }
            sawTextBlock = true;
            allQuotes &= block instanceof QuoteBlock;
        }
        if (!sawTextBlock) {
            return false;
        }

        List<Block> result = new ArrayList<>(document.blocks().size());
        for (int line = 0; line < document.blocks().size(); line++) {
            Block block = document.blocks().get(line);
            if (line < normalized.startLine || line > normalized.endLine || !(block instanceof TextualBlock)) {
                result.add(block);
            } else if (allQuotes) {
                result.add(new Paragraph(block.runs()));
            } else if (block instanceof QuoteBlock) {
                result.add(block);
            } else {
                result.add(new QuoteBlock("", block.runs()));
            }
        }
        return replaceDocument(new WikiDocument(
                document.formatVersion(), document.title(), result, document.metadata()), false);
    }

    /**
     * Inserts one block image after the current text line. Images are real
     * document blocks rather than a special character in text, so a saved
     * document keeps its asset hash and cannot accidentally turn an image into
     * ordinary text during codec/network handling.
     */
    public boolean insertImageAfter(TextSelection selection, ImageBlock image) {
        Objects.requireNonNull(selection, "selection");
        Objects.requireNonNull(image, "image");
        if (document.blocks().isEmpty()) {
            return false;
        }
        int afterLine = Math.max(0, Math.min(selection.endLine(), document.blocks().size() - 1));
        List<Block> result = new ArrayList<>(document.blocks());
        result.add(afterLine + 1, image);
        return replaceDocument(new WikiDocument(
                document.formatVersion(), document.title(), result, document.metadata()), false);
    }

    /**
     * Inserts a block image at one text caret (or in place of one selected
     * range) without ever serialising the picture as a replacement character.
     *
     * <p>The surrounding text is split into real text blocks on both sides of
     * the image. This is the native editor's equivalent of Word's ordinary
     * in-flow image insertion: typing before or after the image continues to
     * edit text, while the picture retains its separate asset reference and
     * layout properties.</p>
     */
    public boolean insertImageAtSelection(TextSelection selection, ImageBlock image) {
        Objects.requireNonNull(selection, "selection");
        Objects.requireNonNull(image, "image");
        NormalizedSelection normalized = normalizeSelection(selection);
        if (normalized == null || normalized.startLine != normalized.endLine) {
            return false;
        }
        Block selectedBlock = document.blocks().get(normalized.startLine);
        if (!(selectedBlock instanceof TextualBlock textualBlock)) {
            return false;
        }
        BlockFrame frame = BlockFrame.forInsertion(textualBlock, document.blocks());
        if (frame != null) textualBlock = RichTextBlock.from(textualBlock).withFrame(frame);

        int textLength = blockText(selectedBlock).length();
        List<TextRun> before = new ArrayList<>();
        List<TextRun> after = new ArrayList<>();
        addSlice(before, textualBlock.runs(), 0, normalized.startColumn, null);
        addSlice(after, textualBlock.runs(), normalized.endColumn, textLength, null);

        List<Block> result = new ArrayList<>(document.blocks().size() + 2);
        result.addAll(document.blocks().subList(0, normalized.startLine));
        if (!before.isEmpty()) {
            result.add(withRuns(textualBlock, before));
        }
        result.add(image.withFrame(frame));
        if (!after.isEmpty()) {
            result.add(withRuns(textualBlock, after));
        } else {
            // Always leave a place to continue typing after a picture. A
            // paragraph is used deliberately, even when the source was a
            // heading or quote, so the image does not create a blank heading.
            result.add(paragraphInFrame(List.of(), frame));
        }
        result.addAll(document.blocks().subList(normalized.startLine + 1, document.blocks().size()));
        return replaceDocument(new WikiDocument(
                document.formatVersion(), document.title(), result, document.metadata()), false);
    }

    /**
     * Inserts a rectangular rich-text table at one text caret (or in place of
     * a selected range).  Like an in-flow image, the table is a real document
     * node: surrounding text is split into normal blocks and the table never
     * passes through a plain-text editing path.
     */
    public boolean insertTableAtSelection(TextSelection selection, int rows, int columns) {
        Objects.requireNonNull(selection, "selection");
        if (rows < 1 || rows > 16 || columns < 1 || columns > 16) {
            return false;
        }
        NormalizedSelection normalized = normalizeSelection(selection);
        if (normalized == null || normalized.startLine != normalized.endLine) {
            return false;
        }
        Block selectedBlock = document.blocks().get(normalized.startLine);
        if (!(selectedBlock instanceof TextualBlock textualBlock)) {
            return false;
        }
        BlockFrame frame = BlockFrame.forInsertion(textualBlock, document.blocks());
        if (frame != null) textualBlock = RichTextBlock.from(textualBlock).withFrame(frame);

        int textLength = blockText(selectedBlock).length();
        List<TextRun> before = new ArrayList<>();
        List<TextRun> after = new ArrayList<>();
        addSlice(before, textualBlock.runs(), 0, normalized.startColumn, null);
        addSlice(after, textualBlock.runs(), normalized.endColumn, textLength, null);

        List<Block> result = new ArrayList<>(document.blocks().size() + 2);
        result.addAll(document.blocks().subList(0, normalized.startLine));
        if (!before.isEmpty()) {
            result.add(withRuns(textualBlock, before));
        }
        result.add(emptyTable(rows, columns).withFrame(frame));
        if (!after.isEmpty()) {
            result.add(withRuns(textualBlock, after));
        } else {
            // Leave a real paragraph after the table, matching the image
            // insertion rule and giving the author somewhere to keep typing.
            result.add(paragraphInFrame(List.of(), frame));
        }
        result.addAll(document.blocks().subList(normalized.startLine + 1, document.blocks().size()));
        return replaceDocument(new WikiDocument(
                document.formatVersion(), document.title(), result, document.metadata()), false);
    }

    /**
     * Replaces one range inside one table cell while preserving the styles of
     * the untouched runs.  Table-cell typing coalesces into one undo step just
     * like normal paragraph typing.
     */
    public boolean replaceTextInTableCell(int blockIndex, int rowIndex, int columnIndex,
            int startColumn, int endColumn, String replacement) {
        return replaceTextInTableCell(blockIndex, rowIndex, columnIndex, startColumn, endColumn, replacement, null);
    }

    public boolean replaceTextInTableCell(int blockIndex, int rowIndex, int columnIndex,
            int startColumn, int endColumn, String replacement, TextStyle insertionStyle) {
        Objects.requireNonNull(replacement, "replacement");
        if (blockIndex < 0 || blockIndex >= document.blocks().size()
                || !(document.blocks().get(blockIndex) instanceof TableBlock table)
                || rowIndex < 0 || rowIndex >= table.rows().size()
                || columnIndex < 0 || columnIndex >= table.columnCount()) {
            return false;
        }
        TableCell oldCell = table.rows().get(rowIndex).cells().get(columnIndex);
        String oldText = runsText(oldCell.runs());
        int start = safeBoundary(oldText, startColumn, true);
        int end = safeBoundary(oldText, endColumn, false);
        if (start > end) {
            int previousStart = start;
            start = end;
            end = previousStart;
        }
        List<TextRun> updatedRuns = new ArrayList<>();
        addSlice(updatedRuns, oldCell.runs(), 0, start, null);
        append(updatedRuns, new TextRun(replacement,
                insertionStyle != null ? insertionStyle : styleAt(oldCell.runs(), start)));
        addSlice(updatedRuns, oldCell.runs(), end, oldText.length(), null);
        TableCell updatedCell = new TableCell(updatedRuns);
        if (updatedCell.equals(oldCell)) {
            return false;
        }
        List<TableRow> rows = new ArrayList<>(table.rows());
        List<TableCell> cells = new ArrayList<>(rows.get(rowIndex).cells());
        cells.set(columnIndex, updatedCell);
        rows.set(rowIndex, new TableRow(cells));
        List<Block> blocks = new ArrayList<>(document.blocks());
        blocks.set(blockIndex, new TableBlock(rows, table.frame()));
        return replaceDocument(new WikiDocument(
                document.formatVersion(), document.title(), blocks, document.metadata()), true);
    }

    /** Applies the same inline-format command to a selected range inside a table cell. */
    public boolean applyTableCellStyle(int block, int row, int column, int start, int end,
            UnaryOperator<TextStyle> operation) {
        Objects.requireNonNull(operation, "operation");
        if (block < 0 || block >= document.blocks().size()
                || !(document.blocks().get(block) instanceof TableBlock table)
                || row < 0 || row >= table.rows().size() || column < 0 || column >= table.columnCount()) return false;
        TableCell cell = table.rows().get(row).cells().get(column);
        String text = runsText(cell.runs());
        int from = safeBoundary(text, Math.min(start, end), true);
        int to = safeBoundary(text, Math.max(start, end), false);
        if (from == to) return false;
        List<TableRow> rows = new ArrayList<>(table.rows());
        List<TableCell> cells = new ArrayList<>(rows.get(row).cells());
        cells.set(column, new TableCell(styleRuns(cell.runs(), from, to, operation)));
        rows.set(row, new TableRow(cells));
        List<Block> blocks = new ArrayList<>(document.blocks());
        blocks.set(block, new TableBlock(rows, table.frame()));
        return replaceDocument(new WikiDocument(document.formatVersion(), document.title(), blocks, document.metadata()), false);
    }

    /** History roots let local view state share the exact same ten-step lifetime. */
    public List<WikiDocument> retainedDocuments() {
        List<WikiDocument> documents = new ArrayList<>(undo);
        documents.addAll(redo);
        documents.add(document);
        return List.copyOf(documents);
    }

    /** Tab in the final table cell appends a row as a structural undoable edit. */
    public boolean appendTableRow(int blockIndex) {
        if (blockIndex < 0 || blockIndex >= document.blocks().size()
                || !(document.blocks().get(blockIndex) instanceof TableBlock table)) return false;
        List<TableRow> rows = new ArrayList<>(table.rows());
        List<TableCell> cells = new ArrayList<>();
        for (int i = 0; i < table.columnCount(); i++) cells.add(new TableCell(List.of()));
        rows.add(new TableRow(cells));
        List<Block> blocks = new ArrayList<>(document.blocks());
        blocks.set(blockIndex, new TableBlock(rows, table.frame()));
        return replaceDocument(new WikiDocument(document.formatVersion(), document.title(), blocks, document.metadata()), false);
    }

    /** Style at an insertion point; typing at a run boundary inherits the left run. */
    public TextStyle insertionStyle(int blockIndex, int column) {
        if (blockIndex < 0 || blockIndex >= document.blocks().size()) return TextStyle.DEFAULT;
        return styleAt(document.blocks().get(blockIndex).runs(), Math.max(0, column));
    }

    /**
     * Inserts an image inside one physical page, immediately after its
     * indicated authored line.  A trailing UI-only empty row is clamped to the
     * real final block of that page, so inserting an image at the bottom of a
     * page never accidentally places it after the next {@link PageBreak}.
     */
    public boolean insertImageOnPage(int pageIndex, int localLine, ImageBlock image) {
        Objects.requireNonNull(image, "image");
        final DocumentPages.PageRange range;
        try {
            range = DocumentPages.range(document, pageIndex);
        } catch (IndexOutOfBoundsException exception) {
            return false;
        }
        int pageBlockCount = range.endExclusive() - range.startInclusive();
        int insertionIndex = pageBlockCount == 0
                ? range.startInclusive()
                : range.startInclusive() + Math.max(0, Math.min(localLine, pageBlockCount - 1)) + 1;
        List<Block> result = new ArrayList<>(document.blocks());
        result.add(insertionIndex, image);
        return replaceDocument(new WikiDocument(
                document.formatVersion(), document.title(), result, document.metadata()), false);
    }

    /**
     * Replaces the properties of one image block without rewriting its asset
     * bytes or turning it into text.  The block index is deliberately an
     * explicit document address: UI code may select a picture visually, while
     * future table/image renderers can use the same command without relying on
     * a fake character selection.
     */
    public boolean updateImageBlock(int blockIndex, ImageBlock replacement) {
        Objects.requireNonNull(replacement, "replacement");
        if (blockIndex < 0 || blockIndex >= document.blocks().size()
                || !(document.blocks().get(blockIndex) instanceof ImageBlock)) {
            return false;
        }
        List<Block> result = new ArrayList<>(document.blocks());
        result.set(blockIndex, replacement.withFrame(document.blocks().get(blockIndex).frame()));
        return replaceDocument(new WikiDocument(
                document.formatVersion(), document.title(), result, document.metadata()), false);
    }

    /**
     * Removes one selected image as a single document command.  A completely
     * empty document is normalised to one empty paragraph so the player still
     * has an immediately editable page after deleting its only picture.
     */
    public boolean deleteImageBlock(int blockIndex) {
        if (blockIndex < 0 || blockIndex >= document.blocks().size()
                || !(document.blocks().get(blockIndex) instanceof ImageBlock)) {
            return false;
        }
        List<Block> result = new ArrayList<>(document.blocks());
        result.remove(blockIndex);
        if (result.isEmpty()) {
            result.add(new Paragraph(List.of()));
        }
        return replaceDocument(new WikiDocument(
                document.formatVersion(), document.title(), result, document.metadata()), false);
    }

    /** Deletes a row; removing the last row removes the table. */
    public boolean deleteTableRow(int blockIndex, int rowIndex) {
        if (blockIndex < 0 || blockIndex >= document.blocks().size()
                || !(document.blocks().get(blockIndex) instanceof TableBlock table)
                || rowIndex < 0 || rowIndex >= table.rows().size()) return false;
        if (table.rows().size() == 1) return deleteTableBlock(blockIndex);
        List<TableRow> rows = new ArrayList<>(table.rows());
        rows.remove(rowIndex);
        List<Block> blocks = new ArrayList<>(document.blocks());
        blocks.set(blockIndex, new TableBlock(rows, table.frame()));
        return replaceDocument(new WikiDocument(
                document.formatVersion(), document.title(), blocks, document.metadata()), false);
    }

    /** Deletes the same column from every row, preserving remaining rich text. */
    public boolean deleteTableColumn(int blockIndex, int columnIndex) {
        if (blockIndex < 0 || blockIndex >= document.blocks().size()
                || !(document.blocks().get(blockIndex) instanceof TableBlock table)
                || columnIndex < 0 || columnIndex >= table.columnCount()) return false;
        if (table.columnCount() == 1) return deleteTableBlock(blockIndex);
        List<TableRow> rows = new ArrayList<>();
        for (TableRow row : table.rows()) {
            List<TableCell> cells = new ArrayList<>(row.cells());
            cells.remove(columnIndex);
            rows.add(new TableRow(cells));
        }
        List<Block> blocks = new ArrayList<>(document.blocks());
        blocks.set(blockIndex, new TableBlock(rows, table.frame()));
        return replaceDocument(new WikiDocument(
                document.formatVersion(), document.title(), blocks, document.metadata()), false);
    }

    /** Removes a whole table in one undo step, keeping an emptied page editable. */
    public boolean deleteTableBlock(int blockIndex) {
        if (blockIndex < 0 || blockIndex >= document.blocks().size()
                || !(document.blocks().get(blockIndex) instanceof TableBlock)) {
            return false;
        }
        List<Block> result = new ArrayList<>(document.blocks());
        result.remove(blockIndex);
        boolean pageStart = blockIndex == 0 || result.get(blockIndex - 1) instanceof PageBreak;
        boolean pageEnd = blockIndex == result.size() || result.get(blockIndex) instanceof PageBreak;
        if (pageStart && pageEnd) {
            result.add(blockIndex, new Paragraph(List.of()));
        }
        return replaceDocument(new WikiDocument(
                document.formatVersion(), document.title(), result, document.metadata()), false);
    }

    /**
     * Gives an independently positioned image a real text paragraph directly
     * before or after it.  A picture is never a character in a text run, so
     * the editor must create a neighbouring paragraph instead of trying to
     * place a caret "inside" the image node.  Returning the new block address
     * lets a native UI put its caret there immediately.
     *
     * @return the new paragraph's block index, or {@code -1} when the image
     *         address is no longer valid
     */
    public int insertParagraphAdjacentToImage(int blockIndex, boolean before) {
        if (blockIndex < 0 || blockIndex >= document.blocks().size()
                || !(document.blocks().get(blockIndex) instanceof ImageBlock)) {
            return -1;
        }
        int insertionIndex = before ? blockIndex : blockIndex + 1;
        List<Block> result = new ArrayList<>(document.blocks().size() + 1);
        result.addAll(document.blocks());
        result.add(insertionIndex, paragraphInFrame(List.of(), document.blocks().get(blockIndex).frame()));
        if (!replaceDocument(new WikiDocument(
                document.formatVersion(), document.title(), result, document.metadata()), false)) {
            return -1;
        }
        return insertionIndex;
    }

    /** Inserts an explicit reader/editor page boundary after the selected line. */
    public boolean insertPageBreakAfter(TextSelection selection) {
        Objects.requireNonNull(selection, "selection");
        if (document.blocks().isEmpty()) {
            return false;
        }
        int afterLine = Math.max(0, Math.min(selection.endLine(), document.blocks().size() - 1));
        if (document.blocks().get(afterLine) instanceof PageBreak) {
            return false;
        }
        List<Block> result = new ArrayList<>(document.blocks());
        result.add(afterLine + 1, PageBreak.INSTANCE);
        return replaceDocument(new WikiDocument(
                document.formatVersion(), document.title(), result, document.metadata()), false);
    }

    /**
     * Adds one real, empty page after the final authored page. This is the
     * “next page” behaviour of a book-and-quill, not a textual page-break
     * character that the player has to insert manually.
     */
    public boolean appendEmptyPage() {
        List<Block> result = new ArrayList<>(document.blocks().size() + 2);
        result.addAll(document.blocks());
        if (!result.isEmpty()) {
            result.add(PageBreak.INSTANCE);
        }
        result.add(new Paragraph(List.of()));
        return replaceDocument(new WikiDocument(
                document.formatVersion(), document.title(), result, document.metadata()), false);
    }

    /**
     * Deletes one authored page and one adjacent boundary. A document always
     * retains at least one editable page.
     */
    public boolean deletePage(int pageIndex) {
        if (DocumentPages.count(document) <= 1) {
            return false;
        }
        final DocumentPages.PageRange range;
        try {
            range = DocumentPages.range(document, pageIndex);
        } catch (IndexOutOfBoundsException exception) {
            return false;
        }
        List<Block> oldBlocks = document.blocks();
        int removeFrom;
        int removeTo;
        // Prefer a trailing boundary. For the final page remove its preceding
        // boundary instead, keeping all surviving pages intact.
        if (range.endExclusive() < oldBlocks.size()
                && oldBlocks.get(range.endExclusive()) instanceof PageBreak) {
            removeFrom = range.startInclusive();
            removeTo = range.endExclusive() + 1;
        } else if (range.startInclusive() > 0
                && oldBlocks.get(range.startInclusive() - 1) instanceof PageBreak) {
            removeFrom = range.startInclusive() - 1;
            removeTo = range.endExclusive();
        } else {
            return false;
        }
        List<Block> result = new ArrayList<>(oldBlocks.size() - (removeTo - removeFrom));
        result.addAll(oldBlocks.subList(0, removeFrom));
        result.addAll(oldBlocks.subList(removeTo, oldBlocks.size()));
        return replaceDocument(new WikiDocument(
                document.formatVersion(), document.title(), result, document.metadata()), false);
    }

    /** Changes every selected block into a normal paragraph or a heading. */
    public boolean setBlockType(TextSelection selection, int headingLevel) {
        if (headingLevel < 0 || headingLevel > 3) {
            throw new IllegalArgumentException("headingLevel must be from 0 to 3");
        }
        NormalizedSelection normalized = normalizeSelection(selection);
        if (normalized == null) {
            return false;
        }
        List<Block> result = new ArrayList<>(document.blocks().size());
        for (int line = 0; line < document.blocks().size(); line++) {
            Block block = document.blocks().get(line);
            if (line < normalized.startLine || line > normalized.endLine || !(block instanceof TextualBlock)) {
                result.add(block);
            } else if (block instanceof RichTextBlock rich) {
                result.add(new RichTextBlock(RichTextBlock.Kind.NORMAL, rich.alignment(), headingLevel, false, rich.runs(), rich.frame()));
            } else if (headingLevel == 0) {
                result.add(new Paragraph(block.runs()));
            } else {
                result.add(new Heading(headingLevel, block.runs()));
            }
        }
        return replaceDocument(new WikiDocument(document.formatVersion(), document.title(), result, document.metadata()), false);
    }

    /**
     * Sorts selected consecutive text blocks by their complete paragraph text.
     * This mirrors Word's paragraph sort: an image, table or page boundary in
     * the selected range rejects the command instead of detaching structure
     * from its surrounding prose.
     */
    public boolean sortTextBlocks(TextSelection selection, boolean ascending) {
        Objects.requireNonNull(selection, "selection");
        NormalizedSelection normalized = normalizeSelection(selection);
        if (normalized == null || normalized.startLine == normalized.endLine) {
            return false;
        }
        List<Block> sorted = new ArrayList<>();
        for (int index = normalized.startLine; index <= normalized.endLine; index++) {
            Block block = document.blocks().get(index);
            if (!(block instanceof TextualBlock)) {
                return false;
            }
            sorted.add(block);
        }
        Collator collator = Collator.getInstance(Locale.SIMPLIFIED_CHINESE);
        Comparator<Block> comparator = (left, right) -> collator.compare(blockText(left), blockText(right));
        sorted.sort(ascending ? comparator : comparator.reversed());
        List<Block> result = new ArrayList<>(document.blocks());
        for (int index = 0; index < sorted.size(); index++) {
            result.set(normalized.startLine + index, sorted.get(index));
        }
        return replaceDocument(new WikiDocument(document.formatVersion(), document.title(), result, document.metadata()), false);
    }

    public boolean undo() {
        if (undo.isEmpty()) {
            return false;
        }
        finishTextEdit();
        push(redo, document);
        document = undo.removeFirst();
        return true;
    }

    public boolean redo() {
        if (redo.isEmpty()) {
            return false;
        }
        finishTextEdit();
        push(undo, document);
        document = redo.removeFirst();
        return true;
    }

    private boolean toggle(TextSelection selection, StyleFlag flag, StyleSetter setter) {
        NormalizedSelection normalized = normalizeSelection(selection);
        if (normalized == null || normalized.isEmpty()) {
            return false;
        }
        boolean allEnabled = allSelectedStylesMatch(normalized, flag);
        return applyStyle(selection, style -> setter.withValue(style, !allEnabled));
    }

    private boolean allSelectedStylesMatch(NormalizedSelection selection, StyleFlag flag) {
        boolean sawText = false;
        for (int line = selection.startLine; line <= selection.endLine; line++) {
            Block block = document.blocks().get(line);
            if (!(block instanceof TextualBlock)) {
                continue;
            }
            int start = line == selection.startLine ? selection.startColumn : 0;
            int end = line == selection.endLine ? selection.endColumn : blockText(block).length();
            int offset = 0;
            for (TextRun run : block.runs()) {
                int runEnd = offset + run.text().length();
                if (Math.max(offset, start) < Math.min(runEnd, end)) {
                    sawText = true;
                    if (!flag.isEnabled(run.style())) {
                        return false;
                    }
                }
                offset = runEnd;
            }
        }
        return sawText;
    }

    private boolean replaceDocument(WikiDocument next, boolean textEdit) {
        if (next.equals(document)) {
            return false;
        }
        if (!textEdit || !coalescingTextEdit) {
            push(undo, document);
        }
        redo.clear();
        document = next;
        coalescingTextEdit = textEdit;
        return true;
    }

    private void push(Deque<WikiDocument> stack, WikiDocument value) {
        stack.addFirst(value);
        while (stack.size() > historyLimit) {
            stack.removeLast();
        }
    }

    private NormalizedSelection normalizeSelection(TextSelection selection) {
        if (document.blocks().isEmpty()) {
            return null;
        }
        int startLine = Math.min(selection.startLine(), document.blocks().size() - 1);
        int endLine = Math.min(selection.endLine(), document.blocks().size() - 1);
        int startColumn = safeBoundary(blockText(document.blocks().get(startLine)), selection.startColumn(), true);
        int endColumn = safeBoundary(blockText(document.blocks().get(endLine)), selection.endColumn(), false);
        if (startLine > endLine || (startLine == endLine && startColumn > endColumn)) {
            int oldStartLine = startLine;
            int oldStartColumn = startColumn;
            startLine = endLine;
            startColumn = endColumn;
            endLine = oldStartLine;
            endColumn = oldStartColumn;
        }
        return new NormalizedSelection(startLine, startColumn, endLine, endColumn);
    }

    private static int equalLinePrefix(List<Block> blocks, List<String> lines) {
        int limit = Math.min(blocks.size(), lines.size());
        int index = 0;
        while (index < limit && blockText(blocks.get(index)).equals(lines.get(index))) {
            index++;
        }
        return index;
    }

    private static int equalLineSuffix(List<Block> blocks, List<String> lines, int prefix) {
        int suffix = 0;
        while (suffix < blocks.size() - prefix && suffix < lines.size() - prefix
                && blockText(blocks.get(blocks.size() - suffix - 1))
                .equals(lines.get(lines.size() - suffix - 1))) {
            suffix++;
        }
        return suffix;
    }

    private static void appendRewrittenTextGroup(List<Block> target, List<Block> oldTextBlocks,
            List<String> newTextLines) {
        for (int index = 0; index < newTextLines.size(); index++) {
            Block oldBlock = index < oldTextBlocks.size() ? oldTextBlocks.get(index) : null;
            target.add(replaceBlockText(oldBlock, newTextLines.get(index)));
        }
    }

    private static TableBlock emptyTable(int rows, int columns) {
        List<TableRow> tableRows = new ArrayList<>(rows);
        for (int row = 0; row < rows; row++) {
            List<TableCell> cells = new ArrayList<>(columns);
            for (int column = 0; column < columns; column++) {
                cells.add(new TableCell(List.of()));
            }
            tableRows.add(new TableRow(cells));
        }
        return new TableBlock(tableRows);
    }

    public static TextStyle styleAt(List<TextRun> runs, int position) {
        return TextRunEdits.styleAt(runs, position);
    }

    public static String blockText(Block block) {
        return TextRunEdits.blockText(block);
    }

    private record NormalizedSelection(int startLine, int startColumn, int endLine, int endColumn) {
        boolean isEmpty() {
            return startLine == endLine && startColumn == endColumn;
        }
    }

    @FunctionalInterface
    private interface StyleFlag {
        boolean isEnabled(TextStyle style);
    }

    @FunctionalInterface
    private interface StyleSetter {
        TextStyle withValue(TextStyle style, boolean enabled);
    }
}
