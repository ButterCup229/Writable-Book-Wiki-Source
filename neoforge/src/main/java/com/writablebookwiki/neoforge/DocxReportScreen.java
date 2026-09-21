package com.writablebookwiki.neoforge;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/** A persistent, scrollable conversion receipt, so simplifications never disappear in a toast. */
final class DocxReportScreen extends Screen {
    private final Screen parent;
    private final List<String> messages;
    private List<FormattedCharSequence> lines = List.of();
    private int scroll;
    DocxReportScreen(Screen parent, String title, String detail, List<String> warnings) {
        super(Component.literal(title)); this.parent=parent;
        messages=new ArrayList<>(); messages.add(detail==null?"无法处理该文件":detail);
        if(!warnings.isEmpty()) { messages.add(""); messages.add("格式转换说明"); messages.addAll(warnings); }
    }
    @Override protected void init() {
        lines=new ArrayList<>();
        for(String text:messages) {lines.addAll(font.split(Component.literal(text),Math.max(80,panelWidth()-28)));lines.add(FormattedCharSequence.EMPTY);}
        addRenderableWidget(Button.builder(Component.literal("返回文档"), b->onClose()).bounds(width/2-50,panelTop()+panelHeight()-28,100,20).build());
    }
    private int panelWidth() { return Math.min(500, Math.max(120, width-32)); }
    private int panelHeight() { return Math.min(300, Math.max(110, height-40)); }
    private int panelTop() { return (height-panelHeight())/2; }
    /** Screen.render calls this before its buttons. Never blur text that has already been drawn. */
    @Override public void renderBackground(GuiGraphics g,int mx,int my,float delta) { }
    @Override public void render(GuiGraphics g,int mx,int my,float delta) {
        g.fill(0,0,width,height,0x70100E0B);
        int left=(width-panelWidth())/2, top=panelTop(), bottom=top+panelHeight();
        BookEditorChrome.cover(g,left,top,left+panelWidth(),bottom);
        g.drawString(font,title,width/2-font.width(title)/2,top+12,0xFF463829,false);
        g.enableScissor(left+10,top+34,left+panelWidth()-10,bottom-38);
        for(int i=0;i<lines.size();i++)g.drawString(font,lines.get(i),left+14,top+36+i*13-scroll,0xFF302C25,false);
        g.disableScissor();super.render(g,mx,my,delta);
    }
    @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical) {
        scroll=Math.max(0,Math.min(Math.max(0,lines.size()*13-panelHeight()+78),scroll-(int)(vertical*26)));return true;
    }
    @Override public void onClose() {
        if (parent instanceof NativeWikiScreen book) book.resumeFromReport();
        minecraft.setScreen(parent);
    }
}
