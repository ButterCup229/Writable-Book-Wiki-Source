package com.writablebookwiki.neoforge;

import com.writablebookwiki.core.document.DocumentMetadata;
import com.writablebookwiki.core.document.Heading;
import com.writablebookwiki.core.document.Paragraph;
import com.writablebookwiki.core.document.PageBreak;
import com.writablebookwiki.core.document.QuoteBlock;
import com.writablebookwiki.core.document.TextRun;
import com.writablebookwiki.core.document.TextStyle;
import com.writablebookwiki.core.document.WikiDocument;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

@EventBusSubscriber(modid = WritableBookWikiMod.MOD_ID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class WritableBookWikiClientGameEvents {
    private static boolean developmentPreviewOpened;

    private WritableBookWikiClientGameEvents() {
    }

    @SubscribeEvent
    public static void onLogout(net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) {
        ClientBookSessions.disconnect();
        BookNetwork.disconnectClient();
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        BookNetwork.expireClientTransfers();
        ClientLecternBooks.tick();
        WikiWindow.tick();
        StandaloneGlfwProofWindow.openForDevelopmentIfRequested();
        if (Boolean.getBoolean("writablebookwiki.nativePreview") && !developmentPreviewOpened) {
            developmentPreviewOpened = true;
            WikiWindow.openReader(exampleDocument());
        }
        while (WritableBookWikiClientModEvents.OPEN_READER.consumeClick()) {
            WikiWindow.openReader(exampleDocument());
        }
        while (WritableBookWikiClientModEvents.TOGGLE_WINDOW_HOST.consumeClick()) {
            WikiWindow.toggleWindowHost();
        }
    }

    private static WikiDocument exampleDocument() {
        UUID player = new UUID(0, 1);
        DocumentMetadata metadata = new DocumentMetadata(
                player, "本地预览", Instant.EPOCH, player, "本地预览", Instant.EPOCH);
        return new WikiDocument(
                WikiDocument.CURRENT_FORMAT_VERSION,
                "Writable Book Wiki 阅读预览",
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
