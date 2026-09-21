package com.editbookwiki.neoforge;

import com.editbookwiki.core.document.DocumentMetadata;
import com.editbookwiki.core.document.Heading;
import com.editbookwiki.core.document.Paragraph;
import com.editbookwiki.core.document.PageBreak;
import com.editbookwiki.core.document.QuoteBlock;
import com.editbookwiki.core.document.TextRun;
import com.editbookwiki.core.document.TextStyle;
import com.editbookwiki.core.document.WikiDocument;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

@EventBusSubscriber(modid = EditBookWikiMod.MOD_ID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class EditBookWikiClientGameEvents {
    private static boolean developmentPreviewOpened;

    private EditBookWikiClientGameEvents() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        BookNetwork.expireClientTransfers();
        StandaloneGlfwProofWindow.openForDevelopmentIfRequested();
        if (Boolean.getBoolean("editbookwiki.nativePreview") && !developmentPreviewOpened) {
            developmentPreviewOpened = true;
            WikiWindow.openReader(exampleDocument());
        }
        while (EditBookWikiClientModEvents.OPEN_READER.consumeClick()) {
            WikiWindow.openReader(exampleDocument());
        }
        while (EditBookWikiClientModEvents.TOGGLE_WINDOW_HOST.consumeClick()) {
            WikiWindow.toggleWindowHost();
        }
    }

    private static WikiDocument exampleDocument() {
        UUID player = new UUID(0, 1);
        DocumentMetadata metadata = new DocumentMetadata(
                player, "本地预览", Instant.EPOCH, player, "本地预览", Instant.EPOCH);
        return new WikiDocument(
                WikiDocument.CURRENT_FORMAT_VERSION,
                "编辑书 Wiki 阅读预览",
                List.of(
                        new Heading(1, List.of(
                                new TextRun("第一章：欢迎", new TextStyle(true, false, false, 0x336699, 22)))),
                        new Paragraph(List.of(
                        new TextRun("这是本地阅读窗口示例；编辑页和阅读页共享同一份文档。", TextStyle.DEFAULT))),
                        new QuoteBlock("本地预览", List.of(
                                new TextRun("引用与正文共用同一页排版；阅读时可以点击引用折叠或展开。",
                                        new TextStyle(false, true, false, 0x7A3E9D, 16)))),
                        new Paragraph(List.of(
                                new TextRun("打开第二章", TextStyle.DEFAULT, "第二章：链接目标"))),
                        PageBreak.INSTANCE,
                        new Heading(2, List.of(
                                new TextRun("第二章：链接目标", new TextStyle(true, false, false, 0x336699, 20)))),
                        new Paragraph(List.of(
                                new TextRun("顶栏保持固定，正文区域独立滚动。", TextStyle.DEFAULT)))),
                metadata);
    }
}
