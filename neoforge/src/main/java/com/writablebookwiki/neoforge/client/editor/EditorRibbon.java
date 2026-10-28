package com.writablebookwiki.neoforge.client.editor;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import static com.writablebookwiki.neoforge.client.editor.EditorCommand.*;

/** Ribbon presentation and hit geometry only; it never owns a document or runs an edit. */
public final class EditorRibbon {
    public record Palette(int ink, int heading, int disabled, int accent) { }
    public record Button(EditorCommand command, int x, int y, int width) {
        public boolean contains(double mx, double my) {
            return inRect(mx, my, x, y, width, 18);
        }
    }
    private record Tab(String label, List<EditorCommand> commands) { }
    private static final List<Tab> TABS = List.of(
            new Tab("开始", List.of(BOLD, ITALIC, UNDERLINE, STRIKETHROUGH, SUPERSCRIPT, SUBSCRIPT,
                    FONT_SIZE, TEXT_COLOR, HEADING, HIDDEN, INLINE_CODE, UNDO, REDO)),
            new Tab("段落", List.of(ALIGN_LEFT, ALIGN_CENTER, ALIGN_RIGHT, ALIGN_JUSTIFY,
                    BULLET, NUMBERED, QUOTE, TASK, INFO, WARNING, STEP, NORMAL)),
            new Tab("插入", List.of(IMAGE, TABLE, LINK, COMMAND_LINK, RULE)),
            new Tab("文档", List.of(SAVE, IMPORT_DOCX, EXPORT_DOCX, DELETE_PAGE, PAPER_COLOR)));
    private int selectedTab;

    /** Reserve the largest tab so switching tabs never moves the paper under the caret. */
    public static int rows(int width) {
        int maximum = 1;
        for (Tab tab : TABS) {
            int rows = 1, used = 0;
            for (EditorCommand command : tab.commands()) {
                if (used > 0 && used + command.width() > width) { rows++; used = 0; }
                used += command.width() + 3;
            }
            maximum = Math.max(maximum, rows);
        }
        return maximum;
    }

    public List<Button> buttons(int left, int top, int right) {
        List<Button> result = new ArrayList<>();
        int x = left, y = top + 18;
        for (EditorCommand command : TABS.get(selectedTab).commands()) {
            if (x > left && x + command.width() > right) { x = left; y += 20; }
            result.add(new Button(command, x, y, command.width()));
            x += command.width() + 3;
        }
        return List.copyOf(result);
    }

    public boolean selectTabAt(int left, int top, double mx, double my) {
        for (int i = 0; i < TABS.size(); i++) {
            if (inRect(mx, my, left + i * 45, top, 42, 16)) {
                selectedTab = i;
                return true;
            }
        }
        return false;
    }

    public void draw(GuiGraphics g, Font font, Palette colors, int left, int top, int right,
            int mx, int my, Predicate<EditorCommand> active, int textColor) {
        for (int i = 0; i < TABS.size(); i++) {
            int x = left + i * 45;
            if (i == selectedTab) g.fill(x, top + 14, x + 40, top + 16, colors.accent());
            g.drawString(font, TABS.get(i).label(), x + 8, top + 3,
                    i == selectedTab ? colors.heading() : colors.disabled(), false);
        }
        for (Button button : buttons(left, top, right)) {
            int x = button.x(), y = button.y();
            EditorCommand command = button.command();
            boolean enabled = active.test(command);
            g.fill(x, y, x + button.width(), y + 18,
                    enabled ? 0xFFCDD9C1 : button.contains(mx, my) ? 0xFFE7DCBF : 0xFFF3EBD7);
            if (enabled) g.fill(x, y + 17, x + button.width(), y + 18, colors.accent());
            switch (command) {
                case IMAGE, TABLE -> drawInsertIcon(g, font, button, colors.ink());
                case ALIGN_LEFT, ALIGN_CENTER, ALIGN_RIGHT, ALIGN_JUSTIFY, BULLET, NUMBERED ->
                    drawParagraphIcon(g, font, button, colors.ink());
                default -> {
                    Component label = Component.literal(command.label()).withStyle(Style.EMPTY
                            .withBold(command == BOLD).withItalic(command == ITALIC)
                            .withStrikethrough(command == STRIKETHROUGH).withUnderlined(command == UNDERLINE));
                    g.drawString(font, label, x + (button.width() - font.width(label)) / 2, y + 5, colors.ink(), false);
                    if (command == TEXT_COLOR) g.fill(x + 5, y + 14, x + button.width() - 5, y + 16, 0xFF000000 | textColor);
                }
            }
        }
    }

    public void drawTooltip(GuiGraphics g, Font font, int left, int top, int right, int mx, int my) {
        for (Button button : buttons(left, top, right)) if (button.contains(mx, my)) {
            g.renderTooltip(font, Component.literal(button.command().hint()), mx, my);
            return;
        }
    }

    private static void drawInsertIcon(GuiGraphics g, Font font, Button button, int textColor) {
        int x = button.x(), y = button.y(), ix = x + 4, iy = y + 4, ink = 0xFF315D89;
        g.fill(ix, iy, ix + 13, iy + 10, ink);
        g.fill(ix + 1, iy + 1, ix + 12, iy + 9, 0xFFF2F6FA);
        if (button.command() == TABLE) {
            g.fill(ix + 4, iy, ix + 5, iy + 10, ink);
            g.fill(ix + 8, iy, ix + 9, iy + 10, ink);
            g.fill(ix, iy + 3, ix + 13, iy + 4, ink);
            g.fill(ix, iy + 6, ix + 13, iy + 7, ink);
        } else {
            g.fill(ix + 8, iy + 2, ix + 10, iy + 4, 0xFFE4B347);
            for (int k = 0; k < 7; k++)
                g.fill(ix + 2 + k, iy + 7 - Math.min(k, 6 - k), ix + 3 + k, iy + 9, ink);
        }
        g.drawString(font, button.command().label(), x + 21, y + 5, textColor, false);
    }

    private static void drawParagraphIcon(GuiGraphics g, Font font, Button button, int ink) {
        int x = button.x(), y = button.y();
        EditorCommand command = button.command();
        for (int row = 0; row < 4; row++) {
            int length = command == ALIGN_JUSTIFY || row % 2 == 0 ? 13 : 8;
            int left = x + 5 + (command == ALIGN_CENTER ? (13 - length) / 2
                    : command == ALIGN_RIGHT ? 13 - length : 0);
            if (command == BULLET || command == NUMBERED) {
                left = x + 9;
                length = row % 2 == 0 ? 10 : 7;
            }
            g.fill(left, y + 4 + row * 3, left + length, y + 5 + row * 3, ink);
            if (command == BULLET) g.fill(x + 4, y + 4 + row * 3, x + 6, y + 6 + row * 3, ink);
        }
        if (command == NUMBERED) {
            g.pose().pushPose();
            g.pose().translate(x + 2, y + 3, 0);
            g.pose().scale(.55f, .55f, 1);
            g.drawString(font, "1", 0, 0, ink, false);
            g.drawString(font, "2", 0, 10, ink, false);
            g.pose().popPose();
        }
    }

    private static boolean inRect(double x, double y, int left, int top, int width, int height) {
        return x >= left && x < left + width && y >= top && y < top + height;
    }
}
