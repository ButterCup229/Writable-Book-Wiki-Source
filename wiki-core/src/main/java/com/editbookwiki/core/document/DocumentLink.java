package com.editbookwiki.core.document;

import java.net.URI;
import java.util.Locale;

/** Only explicit user clicks dispatch these targets; never evaluate document text. */
public record DocumentLink(Kind kind, String value) {
    public enum Kind { WEB, PAGE, COMMAND }

    public static DocumentLink parse(String target) {
        if (target == null || target.isBlank() || target.length() > 2048
                || target.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("链接为空、过长或包含控制字符");
        String value = target.trim();
        if (value.startsWith("command:")) {
            String command = value.substring(8).strip();
            if (command.startsWith("/")) command = command.substring(1);
            if (command.isBlank() || command.length() > 256 || command.startsWith("/"))
                throw new IllegalArgumentException("请输入一条有效指令（最多 256 字符）");
            return new DocumentLink(Kind.COMMAND, command);
        }
        if (value.startsWith("page:")) {
            try {
                int page = Integer.parseInt(value.substring(5));
                if (page <= 0) throw new NumberFormatException();
                return new DocumentLink(Kind.PAGE, Integer.toString(page));
            } catch (NumberFormatException e) { throw new IllegalArgumentException("页码从 1 开始"); }
        }
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if ((!scheme.equals("http") && !scheme.equals("https")) || uri.getHost() == null || uri.getUserInfo() != null)
                throw new IllegalArgumentException();
            return new DocumentLink(Kind.WEB, uri.toASCIIString());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("网址需以 http:// 或 https:// 开头；书内跳转用 page:页码");
        }
    }
    public String target() {
        return switch (kind) { case WEB -> value; case PAGE -> "page:" + value; case COMMAND -> "command:" + value; };
    }
}
