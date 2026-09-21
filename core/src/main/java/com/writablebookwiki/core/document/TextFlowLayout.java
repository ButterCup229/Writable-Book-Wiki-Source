package com.writablebookwiki.core.document;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Platform-independent text flow. Painting, selection and hit testing consume
 * these exact positions: none of them may measure or wrap the text again.
 * Coordinates retain fractional advances until the final raster operation.
 */
public final class TextFlowLayout {
    private static final Pattern GRAPHEME = Pattern.compile("\\X");

    private TextFlowLayout() { }

    public interface Metrics {
        float advance(String text, TextStyle style, float scale);
        int height(float scale);
    }

    public record Exclusion(float left, int top, float right, int bottom) { }
    public record Glyph(String text, TextStyle style, float scale, boolean link,
            int start, int end, float x, float advance) { }
    public record Line(int start, int end, int y, int height, List<Glyph> glyphs) {
        public Line { glyphs = List.copyOf(glyphs); }
        public int hit(float x) {
            for (Glyph glyph : glyphs) {
                if (x < glyph.x() + glyph.advance() / 2) return glyph.start();
            }
            return end;
        }
    }
    public record Result(List<Line> lines, int bottom) {
        public Result { lines = List.copyOf(lines); }
    }
    private record Token(String text, TextStyle style, float scale, boolean link,
            int start, int end, float width, int height) { }
    private record Measure(String text, TextStyle style, float scale) { }
    private record Span(float left, float right) { }
    private record Attempt(List<Glyph> glyphs, int next, int end, int height, boolean newline) { }

    public static Result layout(List<TextRun> runs, float width, float indent, float blockScale,
            int startY, int lineGap, List<Exclusion> exclusions, Metrics metrics) {
        if (!Float.isFinite(width) || width <= indent || indent < 0 || blockScale <= 0 || lineGap < 0) {
            throw new IllegalArgumentException("Invalid text layout dimensions");
        }
        List<Token> tokens = new ArrayList<>();
        Map<Measure, Float> advances = new HashMap<>();
        int column = 0;
        for (TextRun run : runs) {
            float baseScale = run.style().fontSize() / 11f * blockScale;
            float scale = baseScale * (run.style().script() == 0 ? 1f : 0.72f);
            var matcher = GRAPHEME.matcher(run.text());
            while (matcher.find()) {
                String text = matcher.group();
                float advance = text.equals("\n") ? 0 : advances.computeIfAbsent(
                        new Measure(text, run.style(), scale), key -> Math.max(0,
                                metrics.advance(key.text(), key.style(), key.scale())));
                tokens.add(new Token(text, run.style(), scale, run.isLink(), column + matcher.start(),
                        column + matcher.end(), advance, Math.max(1, metrics.height(baseScale))));
            }
            column += run.text().length();
        }
        List<Line> lines = new ArrayList<>();
        int next = 0;
        int y = startY;
        boolean trailingLine = true;
        while (next < tokens.size() || trailingLine) {
            int start = next < tokens.size() ? tokens.get(next).start() : column;
            int height = Math.max(1, metrics.height(blockScale));
            Attempt attempt;
            while (true) {
                List<Span> spans = spans(indent, width, y, height, exclusions);
                attempt = fill(tokens, next, spans, height, width - indent);
                if (attempt.height() > height) {
                    height = attempt.height();
                    continue; // A large glyph can intersect an image below the line's top.
                }
                if (attempt.next() == next && next < tokens.size()) {
                    int bottom = nearestBottom(y, height, indent, width, exclusions);
                    if (bottom > y) {
                        y = bottom;
                        continue;
                    }
                    // An oversized character still makes progress on a narrow paper.
                    Token token = tokens.get(next);
                    attempt = new Attempt(List.of(glyph(token, indent)), next + 1, token.end(),
                            Math.max(height, token.height()), false);
                }
                break;
            }
            lines.add(new Line(start, attempt.end(), y, attempt.height(), attempt.glyphs()));
            y += attempt.height() + lineGap;
            next = attempt.next();
            trailingLine = attempt.newline();
            if (tokens.isEmpty()) break;
        }
        return new Result(lines, y);
    }

    private static Attempt fill(List<Token> tokens, int first, List<Span> spans, int height, float fullWidth) {
        List<Glyph> glyphs = new ArrayList<>();
        int next = first;
        int end = first < tokens.size() ? tokens.get(first).start() : tokens.isEmpty() ? 0 : tokens.getLast().end();
        int spanIndex = 0;
        float x = spans.isEmpty() ? 0 : spans.getFirst().left();
        while (next < tokens.size()) {
            Token token = tokens.get(next);
            if (token.text().equals("\n")) return new Attempt(glyphs, next + 1, token.start(), height, true);
            // Keep ordinary Latin words together when possible; long tokens and CJK still hard-wrap.
            boolean wordStart = isLatinWord(token) && (next == 0 || !isLatinWord(tokens.get(next - 1)));
            float needed = token.width();
            if (wordStart) {
                for (int i = next + 1; i < tokens.size() && isLatinWord(tokens.get(i)); i++) needed += tokens.get(i).width();
                if (needed > fullWidth) needed = token.width();
            }
            int fit = spanIndex;
            float fitX = x;
            while (fit < spans.size() && fitX + needed > spans.get(fit).right() + 0.001f) {
                fit++;
                if (fit < spans.size()) fitX = spans.get(fit).left();
            }
            if (fit == spans.size() && glyphs.isEmpty() && needed > token.width()) {
                needed = token.width();
                fit = spanIndex;
                fitX = x;
                while (fit < spans.size() && fitX + needed > spans.get(fit).right() + 0.001f) {
                    fit++;
                    if (fit < spans.size()) fitX = spans.get(fit).left();
                }
            }
            if (fit == spans.size()) break;
            spanIndex = fit;
            x = fitX;
            height = Math.max(height, token.height());
            glyphs.add(glyph(token, x));
            x += token.width();
            end = token.end();
            next++;
        }
        return new Attempt(glyphs, next, end, height, false);
    }

    private static boolean isLatinWord(Token token) {
        int c = token.text().codePointAt(0);
        return c < 128 && (Character.isLetterOrDigit(c) || c == '_');
    }

    private static Glyph glyph(Token token, float x) {
        return new Glyph(token.text(), token.style(), token.scale(), token.link(),
                token.start(), token.end(), x, token.width());
    }

    private static List<Span> spans(float left, float right, int y, int height, List<Exclusion> exclusions) {
        List<Exclusion> active = exclusions.stream().filter(e -> y < e.bottom() && y + height > e.top()
                && e.right() > left && e.left() < right).sorted(Comparator.comparingDouble(Exclusion::left)).toList();
        List<Span> spans = new ArrayList<>();
        float x = left;
        for (Exclusion exclusion : active) {
            if (exclusion.left() > x) spans.add(new Span(x, Math.min(right, exclusion.left())));
            x = Math.max(x, exclusion.right());
        }
        if (x < right) spans.add(new Span(x, right));
        return spans;
    }

    private static int nearestBottom(int y, int height, float left, float right, List<Exclusion> exclusions) {
        return exclusions.stream().filter(e -> y < e.bottom() && y + height > e.top()
                && e.right() > left && e.left() < right).mapToInt(Exclusion::bottom).min().orElse(y);
    }
}
