package com.writablebookwiki.neoforge.client.editor;

import com.writablebookwiki.core.document.RichTextBlock;
import org.lwjgl.glfw.GLFW;

/** A command has one identity across ribbon buttons, hints and keyboard bindings. */
public enum EditorCommand {
    BOLD("B", "粗体  Ctrl+B", 23),
    ITALIC("I", "斜体  Ctrl+I", 23),
    STRIKETHROUGH("S", "删除线  Ctrl+Shift+X", 23),
    HEADING("样式", "正文 / 标题", 38),
    FONT_SIZE("字号", "字号  Ctrl+Shift+P", 38),
    TEXT_COLOR("A", "文字颜色", 23),
    IMAGE("图片", "插入图片（也可 Ctrl+V 粘贴）", 50),
    TABLE("表格", "插入表格", 50),
    SAVE("保存", "保存  Ctrl+S", 38),
    DELETE_PAGE("删页", "删除当前页", 38),
    SORT("排序", "排序选中段落", 38),
    UNDERLINE("U", "下划线  Ctrl+U", 23),
    SUPERSCRIPT("x²", "上标", 23),
    SUBSCRIPT("x₂", "下标", 23),
    HIDDEN("隐藏", "隐藏文字  Ctrl+Shift+H（阅读时点击揭示）", 38),
    INLINE_CODE("</>", "行内代码", 23),
    ALIGN_LEFT("", "左对齐  Ctrl+L", 23),
    ALIGN_CENTER("", "居中  Ctrl+E", 23),
    ALIGN_RIGHT("", "右对齐  Ctrl+R", 23),
    ALIGN_JUSTIFY("", "两端对齐  Ctrl+J", 23),
    BULLET("", "无序列表", 23),
    NUMBERED("", "有序列表", 23),
    QUOTE("引用", "引用块", 38),
    TASK("任务", "任务列表（编辑时点击方框勾选）", 38),
    INFO("提示", "信息提示框", 38),
    WARNING("警告", "警告提示框", 38),
    STEP("步骤", "编号步骤", 38),
    CODE_BLOCK("代码块", "多行代码块", 38),
    LINK("链接", "超链接  Ctrl+K", 38),
    COMMAND_LINK("指令", "点击执行 MC 指令", 38),
    RULE("分隔线", "插入水平分隔线", 38),
    UNDO("撤销", "撤销  Ctrl+Z", 38),
    REDO("重做", "重做  Ctrl+Y", 38),
    NORMAL("正文", "清除段落样式", 38),
    IMPORT_DOCX("导入 Word", "导入 .docx 文档（可撤销）", 66),
    EXPORT_DOCX("导出 Word", "导出 .docx 文档", 66),
    PAPER_COLOR("纸色", "纸张背景颜色（本地偏好，不上传）", 38);

    private final String label;
    private final String hint;
    private final int width;

    EditorCommand(String label, String hint, int width) {
        this.label = label;
        this.hint = hint;
        this.width = width;
    }

    public String label() { return label; }
    public String hint() { return hint; }
    public int width() { return width; }

    public RichTextBlock.Kind paragraphKind() {
        return switch (this) {
            case BULLET -> RichTextBlock.Kind.BULLET;
            case NUMBERED -> RichTextBlock.Kind.NUMBERED;
            case QUOTE -> RichTextBlock.Kind.QUOTE;
            case TASK -> RichTextBlock.Kind.TASK;
            case INFO -> RichTextBlock.Kind.INFO;
            case WARNING -> RichTextBlock.Kind.WARNING;
            case STEP -> RichTextBlock.Kind.STEP;
            case CODE_BLOCK -> RichTextBlock.Kind.CODE;
            default -> RichTextBlock.Kind.NORMAL;
        };
    }

    public RichTextBlock.Alignment alignment() {
        return switch (this) {
            case ALIGN_LEFT -> RichTextBlock.Alignment.LEFT;
            case ALIGN_CENTER -> RichTextBlock.Alignment.CENTER;
            case ALIGN_RIGHT -> RichTextBlock.Alignment.RIGHT;
            case ALIGN_JUSTIFY -> RichTextBlock.Alignment.JUSTIFY;
            default -> throw new IllegalStateException("Not an alignment command: " + this);
        };
    }

    /** Text navigation and clipboard stay with their focused editor, not this shared map. */
    public static EditorCommand shortcut(int key, int modifiers) {
        if ((modifiers & GLFW.GLFW_MOD_CONTROL) == 0
                || (modifiers & (GLFW.GLFW_MOD_ALT | GLFW.GLFW_MOD_SUPER)) != 0) return null;
        boolean shift = (modifiers & GLFW.GLFW_MOD_SHIFT) != 0;
        return switch (key) {
            case GLFW.GLFW_KEY_B -> BOLD;
            case GLFW.GLFW_KEY_I -> ITALIC;
            case GLFW.GLFW_KEY_U -> UNDERLINE;
            case GLFW.GLFW_KEY_X -> shift ? STRIKETHROUGH : null;
            case GLFW.GLFW_KEY_P -> shift ? FONT_SIZE : null;
            case GLFW.GLFW_KEY_H -> shift ? HIDDEN : null;
            case GLFW.GLFW_KEY_EQUAL -> shift ? SUPERSCRIPT : SUBSCRIPT;
            case GLFW.GLFW_KEY_L -> shift ? BULLET : ALIGN_LEFT;
            case GLFW.GLFW_KEY_E -> ALIGN_CENTER;
            case GLFW.GLFW_KEY_R -> ALIGN_RIGHT;
            case GLFW.GLFW_KEY_J -> ALIGN_JUSTIFY;
            case GLFW.GLFW_KEY_K -> LINK;
            case GLFW.GLFW_KEY_S -> SAVE;
            case GLFW.GLFW_KEY_Z -> shift ? REDO : UNDO;
            case GLFW.GLFW_KEY_Y -> REDO;
            default -> null;
        };
    }
}
