package com.editbookwiki.local;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalAnnotationStoreTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void keepsEachDocumentAnnotationPrivateAndReplacesOnlyItsOwnText() throws Exception {
        LocalAnnotationStore store = new LocalAnnotationStore(temporaryDirectory.resolve("annotations"));

        assertEquals("", store.load("book_a"));
        store.save("book_a", "读者甲的笔记\n第二行");
        store.save("book_b", "另一本书");
        store.save("book_a", "修改后的笔记");

        assertEquals("修改后的笔记", store.load("book_a"));
        assertEquals("另一本书", store.load("book_b"));
    }

    @Test
    void rejectsPathLikeKeys() {
        LocalAnnotationStore store = new LocalAnnotationStore(temporaryDirectory);
        assertThrows(IllegalArgumentException.class, () -> store.save("../shared-book", "不能逃出注释目录"));
    }

    @Test
    void keepsRangeAnchoredCommentsLocalAndRoundTripsUnicode() throws Exception {
        LocalAnnotationStore store = new LocalAnnotationStore(temporaryDirectory.resolve("annotations"));
        List<LocalTextAnnotation> comments = List.of(
                new LocalTextAnnotation(1, 2, 1, 5, "被选中的文字", "这里需要补充出处。"),
                new LocalTextAnnotation(3, 0, 4, 2, "跨段落引用", "第二条\n允许换行"));

        store.saveTextAnnotations("book_a", comments);

        assertEquals(comments, store.loadTextAnnotations("book_a"));
        assertEquals(List.of(), store.loadTextAnnotations("book_b"));
    }

    @Test
    void copiesPrivateCommentsForwardWithoutDeletingTheOlderBookVersion() throws Exception {
        LocalAnnotationStore store = new LocalAnnotationStore(temporaryDirectory.resolve("annotations"));
        List<LocalTextAnnotation> comments = List.of(
                new LocalTextAnnotation(0, 0, 0, 2, "原文", "我的本地批注"));
        store.saveTextAnnotations("old_book", comments);

        store.copyTextAnnotations("old_book", "forked_book");

        assertEquals(comments, store.loadTextAnnotations("old_book"));
        assertEquals(comments, store.loadTextAnnotations("forked_book"));
    }
}
