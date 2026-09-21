package com.writablebookwiki.core.document;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class TextFlowLayoutTest {
    private final TextFlowLayout.Metrics metrics = new TextFlowLayout.Metrics() {
        public float advance(String text, TextStyle style, float scale) { return 5.25f * scale; }
        public int height(float scale) { return (int) Math.ceil(10 * scale); }
    };
    private TextFlowLayout.Result flow(String text, int width, List<TextFlowLayout.Exclusion> images) {
        return TextFlowLayout.layout(List.of(new TextRun(text, TextStyle.DEFAULT)), width, 0, 1, 0, 2, images, metrics);
    }

    @Test void emptyRunStillHasACaretLine() {
        var result = flow("", 100, List.of());
        assertEquals(1, result.lines().size());
        assertEquals(0, result.lines().getFirst().hit(50));
        assertEquals(12, result.bottom());
    }

    @Test void finalNewlineKeepsEmptyEditableLine() {
        var result = flow("ab\n", 100, List.of());
        assertEquals(2, result.lines().size());
        assertEquals(3, result.lines().getLast().start());
        assertEquals(3, result.lines().getLast().end());
    }

    @Test void fractionalAdvancesNeverAccumulateRoundingError() {
        var line = flow("一二三四五六七八九十", 100, List.of()).lines().getFirst();
        assertEquals(47.25f, line.glyphs().getLast().x());
        assertEquals(10, line.hit(53));
        assertEquals(1, line.hit(5.4f));
    }

    @Test void textContinuesOnBothSidesOfMiddleImage() {
        var result = flow("一二三四五六七八九十一二三四五六七八", 100,
                List.of(new TextFlowLayout.Exclusion(23, 0, 68, 40)));
        var first = result.lines().getFirst();
        assertTrue(first.glyphs().stream().anyMatch(g -> g.x() < 23));
        assertTrue(first.glyphs().stream().anyMatch(g -> g.x() >= 68));
        assertClear(result, 100, List.of(new TextFlowLayout.Exclusion(23, 0, 68, 40)));
    }

    @Test void supportsThreeTextSpansBetweenTwoPictures() {
        var images = List.of(new TextFlowLayout.Exclusion(20, 0, 35, 100),
                new TextFlowLayout.Exclusion(60, 0, 75, 100));
        var line = flow("一".repeat(30), 100, images).lines().getFirst();
        assertTrue(line.glyphs().stream().anyMatch(g -> g.x() >= 35 && g.x() < 60));
        assertTrue(line.glyphs().stream().anyMatch(g -> g.x() >= 75));
    }

    @Test void fullWidthPictureAdvancesToItsBottomInsteadOfPaintingThroughIt() {
        var result = flow("文字", 100, List.of(new TextFlowLayout.Exclusion(0, 0, 100, 80)));
        assertEquals(80, result.lines().getFirst().y());
    }

    @Test void tallGlyphChecksTheEntireLineHeightAgainstAnImage() {
        var large = new TextStyle(false, false, false, 0, 33);
        var images = List.of(new TextFlowLayout.Exclusion(0, 15, 30, 60));
        var result = TextFlowLayout.layout(List.of(new TextRun("中文", large)), 100, 0, 1, 0, 2, images, metrics);
        assertTrue(result.lines().getFirst().glyphs().getFirst().x() >= 30);
        assertClear(result, 100, images);
    }

    @Test void wrapsWordsAndKeepsGraphemeClustersWhole() {
        var result = flow("ab cde", 28, List.of());
        assertEquals("c", result.lines().getLast().glyphs().getFirst().text());
        var emoji = flow("e\u0301👩‍💻", 100, List.of()).lines().getFirst().glyphs();
        assertEquals(2, emoji.size());
        assertEquals("e\u0301", emoji.getFirst().text());
        assertEquals("👩‍💻", emoji.getLast().text());
    }

    @Test void randomWrappingNeverLosesTextOrOverlapsPictures() {
        Random random = new Random(42);
        for (int trial = 0; trial < 100; trial++) {
            int left = random.nextInt(75);
            var images = List.of(new TextFlowLayout.Exclusion(left, random.nextInt(20), left + 25, 90));
            var result = flow("文".repeat(150), 100, images);
            assertEquals(150, result.lines().stream().mapToInt(line -> line.glyphs().size()).sum());
            assertClear(result, 100, images);
        }
    }

    private static void assertClear(TextFlowLayout.Result result, int width, List<TextFlowLayout.Exclusion> images) {
        for (var line : result.lines()) for (var glyph : line.glyphs()) {
            assertTrue(glyph.x() + glyph.advance() <= width + .001);
            for (var image : images) {
                if (line.y() < image.bottom() && line.y() + line.height() > image.top())
                    assertTrue(glyph.x() + glyph.advance() <= image.left() + .001 || glyph.x() >= image.right());
            }
        }
    }
}
