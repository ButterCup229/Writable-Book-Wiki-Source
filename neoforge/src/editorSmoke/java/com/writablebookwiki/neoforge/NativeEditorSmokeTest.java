package com.writablebookwiki.neoforge;

import com.writablebookwiki.core.document.*;
import com.writablebookwiki.local.*;
import com.writablebookwiki.local.annotation.AnnotationWorkspace;
import com.writablebookwiki.neoforge.client.editor.EditorCommand;
import static com.writablebookwiki.neoforge.client.editor.EditorCommand.*;
import com.mojang.blaze3d.platform.NativeImage;
import java.lang.reflect.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.lwjgl.glfw.GLFW;

/** Opt-in, worldless regression driver through the actual Screen input/render path. */
@EventBusSubscriber(modid = WritableBookWikiMod.MOD_ID, value = Dist.CLIENT)
public final class NativeEditorSmokeTest {
    private static int ticks;
    private static int stage;
    private static int completedAt;
    private static final Path OUTPUT = Path.of("editor-smoke-results");
    private static final List<String> checks = new ArrayList<>();
    private static WikiDocumentEditor editor;
    private static NativeWikiScreen screen;
    private static final DocumentSession SESSION = new DocumentSession() {
        public boolean canEdit() { return true; }
        public String save(WikiDocument document) { return "smoke save"; }
        public String personalAnnotationKey() { return "word-smoke-" + ProcessHandle.current().pid(); }
        public ImportedImageAsset importLocalImage(Path path) throws java.io.IOException {
            return new LocalImageAssetStore(com.writablebookwiki.local.StoragePaths.dataRoot(FMLPaths.CONFIGDIR.get())).importImage(path);
        }
    };

    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        if (!Boolean.getBoolean("writablebookwiki.editorSmoke") || stage < 0) return;
        Minecraft mc = Minecraft.getInstance();
        ticks++;
        try {
            if (stage == 0 && mc.screen != null && mc.getOverlay() == null && ticks > 100) {
                mc.options.guiScale().set(2);
                mc.resizeDisplay();
                Files.createDirectories(OUTPUT);
                editor = new WikiDocumentEditor(document(List.of(paragraph("abcdef"))));
                screen = new NativeWikiScreen(editor, SESSION, DocumentMode.EDIT, 0, () -> {});
                mc.setScreen(screen);
                completedAt = ticks;
                stage = 1;
            } else if (stage == 1 && ticks - completedAt > 20) {
                NetworkSmokeChecks.run(NativeEditorSmokeTest::check, OUTPUT);
                exerciseModIdentityAndStorage();
                exerciseInputs();
                exerciseTableDeletion();
                exerciseUnifiedCommands();
                exerciseLockModalIsolation();
                exerciseAnnotationFailureRecovery();
                exerciseClipboardImage();
                exerciseRichEditing();
                exerciseColorPalette();
                exerciseExitSave();
                exerciseFoldingAndSteps();
                exerciseRichClipboardAndTasks();
                exerciseLayoutAndScrollbar();
                prepareVisualDocument();
                completedAt = ticks;
                stage = 2;
            } else if (stage == 2 && ticks - completedAt > 25) {
                exerciseImages();
                capture("editor.png");
                var bounds = call(screen, "bounds");
                double bx = number(bounds, "bodyX"), by = number(bounds, "bodyTop");
                // Right-click the selected range and type directly into the comment card.
                screen.mouseClicked(bx + 10, by + 5, GLFW.GLFW_MOUSE_BUTTON_RIGHT);
                check(((AnnotationWorkspace) field(screen, "annotationWorkspace")).editing(), "right click opens anchored comment");
                for (char c : "Local comment: editable without Save".toCharArray()) screen.charTyped(c, 0);
                WikiDocument bodyBeforeCommentUndo = editor.document();
                screen.keyPressed(GLFW.GLFW_KEY_A, 0, GLFW.GLFW_MOD_CONTROL);
                screen.charTyped('R', 0);
                check(((AnnotationWorkspace) field(screen, "annotationWorkspace")).input().text().equals("R"),
                        "comment Ctrl+A selects and typing replaces");
                screen.keyPressed(GLFW.GLFW_KEY_Z, 0, GLFW.GLFW_MOD_CONTROL);
                check(((AnnotationWorkspace) field(screen, "annotationWorkspace")).input().text().startsWith("Local comment"),
                        "comment undo restores only comment text");
                check(editor.document() == bodyBeforeCommentUndo, "comment undo never edits shared body");
                screen.keyPressed(GLFW.GLFW_KEY_ESCAPE, 0, 0);
                check(((AnnotationWorkspace) field(screen, "annotationWorkspace")).entries().size() == 1, "comment auto-saved locally");
                var store = new LocalAnnotationStore(com.writablebookwiki.local.StoragePaths.dataRoot(FMLPaths.CONFIGDIR.get()).resolve("annotations"));
                String originalHash = new WikiDocumentCodec().contentHash(editor.document());
                var saved = store.loadVersionedTextAnnotations(SESSION.personalAnnotationKey(), originalHash);
                check(saved.size() == 1, "comment readable from versioned disk store");
                clickText(0, 0);
                screen.charTyped('X', 0);
                check(((AnnotationWorkspace) field(screen, "annotationWorkspace")).entries().getFirst().startColumn() == 1,
                        "typing before comment rebases visible anchor");
                check(store.loadVersionedTextAnnotations(SESSION.personalAnnotationKey(), originalHash).getFirst().startColumn() == 0,
                        "unsaved draft does not mutate saved-version comment");
                screen.keyPressed(GLFW.GLFW_KEY_Z, 0, GLFW.GLFW_MOD_CONTROL);
                check(((AnnotationWorkspace) field(screen, "annotationWorkspace")).entries().getFirst().startColumn() == 0,
                        "body undo restores visible comment anchor");
                completedAt = ticks;
                stage = 3;
            } else if (stage == 3 && ticks - completedAt > 20) {
                capture("comments.png");
                screen.setDocumentMode(DocumentMode.READ);
                completedAt = ticks;
                stage = 4;
            } else if (stage == 4 && ticks - completedAt > 20) {
                capture("reader.png");
                prepareRichVisual();
                completedAt=ticks;stage=5;
            } else if (stage == 5 && ticks-completedAt>20) {
                capture("word-ribbon.png");
                Object bounds=call(screen,"bounds");
                screen.mouseClicked(number(bounds,"modeX")+45+12,number(bounds,"toolbarY")+5,0);
                completedAt=ticks;stage=6;
            } else if (stage == 6 && ticks-completedAt>20) {
                capture("word-paragraph.png");screen.setDocumentMode(DocumentMode.READ);
                completedAt=ticks;stage=7;
            } else if (stage == 7 && ticks-completedAt>20) {
                capture("wiki-rich-reader.png");
                screen.setDocumentMode(DocumentMode.EDIT);
                clickText(1, 0);
                action(0, TEXT_COLOR);
                completedAt = ticks; stage = 8;
            } else if (stage == 8 && ticks-completedAt>20) {
                capture("book-colors.png");
                screen.keyPressed(GLFW.GLFW_KEY_ESCAPE, 0, 0);
                call(screen, "showDocxReport", new Class<?>[]{String.class, String.class, List.class},
                        "导出说明示例", "这里显示导出的文件位置，不是 Word 文件预览。",
                        List.of("格式转换说明会列出不支持或已简化的内容。", "正文应清楚可读，不应被背景模糊。"));
                completedAt = ticks; stage = 9;
            } else if (stage == 9 && ticks-completedAt>20) {
                capture("export-report.png");
                mc.screen.onClose();
                prepareFoldVisual(); completedAt=ticks;stage=10;
            } else if (stage == 10 && ticks-completedAt>20) {
                capture("folded-blocks.png");
                prepareScrollVisual(); completedAt=ticks;stage=11;
            } else if (stage == 11 && ticks-completedAt>20) {
                capture("centered-scrollbar.png");
                exerciseMixedFrames();
                prepareMixedFrameVisual(BlockFrame.Kind.QUOTE); completedAt=ticks;stage=12;
            } else if (stage == 12 && ticks-completedAt>20) {
                capture("quote-mixed-frame.png");
                prepareMixedFrameVisual(BlockFrame.Kind.INFO); completedAt=ticks;stage=13;
            } else if (stage == 13 && ticks-completedAt>20) {
                capture("info-mixed-frame.png");
                prepareMixedFrameVisual(BlockFrame.Kind.WARNING); completedAt=ticks;stage=14;
            } else if (stage == 14 && ticks-completedAt>20) {
                capture("warning-mixed-frame.png");
                screen.setDocumentMode(DocumentMode.READ); completedAt=ticks;stage=15;
            } else if (stage == 15 && ticks-completedAt>20) {
                capture("warning-mixed-reader.png");
                exerciseTypedLists();
                prepareListVisual(); completedAt=ticks;stage=16;
            } else if (stage == 16 && ticks-completedAt>20) {
                capture("typed-lists-editor.png");
                screen.setDocumentMode(DocumentMode.READ); completedAt=ticks;stage=17;
            } else if (stage == 17 && ticks-completedAt>20) {
                capture("typed-lists-reader.png");
                Files.writeString(OUTPUT.resolve("result.txt"), "PASS\n" + String.join("\n", checks));
                System.out.println("EDITOR_SMOKE_PASS " + checks.size() + " checks");
                stage = -1;
                mc.stop();
            }
        } catch (Throwable failure) {
            failure.printStackTrace();
            try { Files.createDirectories(OUTPUT); Files.writeString(OUTPUT.resolve("result.txt"),
                    "FAIL\n" + String.join("\n", checks) + "\n" + failure); } catch (Exception ignored) { }
            System.out.println("EDITOR_SMOKE_FAIL " + failure);
            stage = -1;
            mc.stop();
        }
        if (ticks > 1800 && stage >= 0) {
            System.out.println("EDITOR_SMOKE_FAIL timeout");
            stage = -1;
            mc.stop();
        }
    }

    private static void exerciseModIdentityAndStorage() throws Exception {
        var item = WritableBookWikiItems.EDITABLE_BOOK.get();
        var newId = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("writablebookwiki", "editable_book");
        var previousId = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("editbookwiki", "editable_book");
        check(net.minecraft.core.registries.BuiltInRegistries.ITEM.get(previousId) != item,
                "new mod does not register an old-development item alias");
        check(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).equals(newId),
                "books serialize under writablebookwiki namespace");
        check(item.getDescriptionId().equals("item.writablebookwiki.editable_book"),
                "item translation key uses the current namespace");
        check(net.minecraft.client.resources.language.I18n.get(item.getDescriptionId()).equals("Writable Book Wiki"),
                "in-game item name is Writable Book Wiki");

        var lookup = net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(net.minecraft.core.registries.BuiltInRegistries.REGISTRY);
        var pointer = new com.writablebookwiki.core.book.BookPointer(UUID.randomUUID(), "a".repeat(64), true);
        var stack = new net.minecraft.world.item.ItemStack(item);
        var root = new net.minecraft.nbt.CompoundTag();
        root.putString("unrelated", "preserve me");
        var previousBook = new net.minecraft.nbt.CompoundTag();
        previousBook.putUUID("book_id", pointer.bookId());
        previousBook.putString("content_hash", pointer.contentHash());
        previousBook.putBoolean("locked", pointer.locked());
        root.put("editbookwiki_book", previousBook);
        stack.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                net.minecraft.world.item.component.CustomData.of(root));
        check(BookStackData.read(stack).isEmpty(), "new mod does not interpret old-development custom data");
        BookStackData.write(stack, pointer);
        var restored = net.minecraft.world.item.ItemStack.parse(lookup, stack.save(lookup)).orElseThrow();
        check(restored.is(item) && BookStackData.read(restored).orElseThrow().equals(pointer),
                "current ItemStack NBT round trip preserves book ID, hash and lock");
        check("preserve me".equals(restored.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA).copyTag().getString("unrelated")),
                "pointer write preserves unrelated custom data");

        Path world = Files.createTempDirectory(OUTPUT, "namespace-world-");
        Path data = Files.createDirectories(world.resolve("data"));
        var storage = new net.minecraft.world.level.storage.DimensionDataStorage(data.toFile(),
                net.minecraft.util.datafix.DataFixers.getDataFixer(), lookup);
        var previousIndex = new WorldBookIndexData();
        previousIndex.register(pointer.bookId(), pointer.contentHash());
        storage.set("editbookwiki_book_index", previousIndex);
        storage.save();
        net.neoforged.neoforge.common.IOUtilities.waitUntilIOWorkerComplete();
        byte[] previousIndexBytes = Files.readAllBytes(data.resolve("editbookwiki_book_index.dat"));
        var currentIndex = WorldBookIndexData.get(storage);
        check(currentIndex.contentHash(pointer.bookId()).isEmpty(), "new mod does not adopt an old-development world index");
        currentIndex.register(pointer.bookId(), pointer.contentHash());
        storage.save();
        net.neoforged.neoforge.common.IOUtilities.waitUntilIOWorkerComplete();
        check(Files.isRegularFile(data.resolve("writablebookwiki_book_index.dat"))
                        && Arrays.equals(previousIndexBytes, Files.readAllBytes(data.resolve("editbookwiki_book_index.dat"))),
                "current world index is saved without altering unrelated files");
        var reopened = new net.minecraft.world.level.storage.DimensionDataStorage(data.toFile(),
                net.minecraft.util.datafix.DataFixers.getDataFixer(), lookup);
        check(WorldBookIndexData.get(reopened).contentHash(pointer.bookId()).orElseThrow().equals(pointer.contentHash()),
                "current world index survives save and reopen");
        var savedDocument = document(List.of(paragraph("saved book")));
        var documentPointer = new com.writablebookwiki.core.book.BookPointer(UUID.randomUUID(),
                new WikiDocumentCodec().contentHash(savedDocument), true);
        var repository = WorldBookRepository.forRoot(world, currentIndex);
        repository.saveNew(new com.writablebookwiki.core.book.BookRevision(documentPointer, savedDocument));
        var loaded = repository.load(documentPointer).orElseThrow();
        check(loaded.pointer().equals(documentPointer) && loaded.document().equals(savedDocument),
                "current world document round trip preserves content and pointer");
        check(Files.isRegularFile(world.resolve("writablebookwiki/documents").resolve(documentPointer.bookId() + ".ewd")),
                "world documents are stored in the documented directory");
    }

    private static void exerciseInputs() throws Exception {
        clickText(0, 1);
        screen.keyPressed(GLFW.GLFW_KEY_B, 0, GLFW.GLFW_MOD_CONTROL);
        screen.charTyped('X', 0);
        check(editor.document().blocks().getFirst().runs().stream()
                .anyMatch(run -> run.text().contains("X") && run.style().bold()), "collapsed bold applies to new typing");
        screen.keyPressed(GLFW.GLFW_KEY_A, 0, GLFW.GLFW_MOD_CONTROL);
        screen.keyPressed(GLFW.GLFW_KEY_ENTER, 0, 0);
        check(editor.document().blocks().size() == 2
                && editor.document().blocks().stream().allMatch(b -> WikiDocumentEditor.blockText(b).isEmpty()),
                "Enter replaces selected paragraph text");
        screen.charTyped('z', 0);
        screen.keyPressed(GLFW.GLFW_KEY_LEFT, 0, 0);
        screen.keyPressed(GLFW.GLFW_KEY_LEFT, 0, 0);
        check((int) field(screen, "caretBlock") == 0, "Left crosses paragraph boundary");
        screen.keyPressed(GLFW.GLFW_KEY_DOWN, 0, 0);
        check((int) field(screen, "caretBlock") == 1, "Down follows visual lines");
        screen.keyPressed(GLFW.GLFW_KEY_Z, 0, GLFW.GLFW_MOD_CONTROL);
        check(editor.document().blocks().size() == 2, "undo keeps earlier Enter as separate action");
        clickText(0, 0);
        screen.charTyped('a', 0);
        screen.charTyped('b', 0);
        screen.charTyped('c', 0);
        dragText(0, 0, 2);
        screen.charTyped('Q', 0);
        check(WikiDocumentEditor.blockText(editor.document().blocks().getFirst()).equals("Qc"),
                "mouse drag selection replaced exactly");

        editor.insertTableAtSelection(new TextSelection(0, 2, 0, 2), 1, 1);
        screen.refreshDocument();
        Object bounds = call(screen, "bounds");
        call(screen, "ensureLayout", new Class<?>[]{int.class}, number(bounds, "bodyWidth"));
        Object table = ((List<?>) field(screen, "tableLayouts")).getFirst();
        double x = number(bounds, "bodyX") + 7;
        double y = number(bounds, "bodyTop") + number(table, "y") + 7;
        screen.mouseClicked(x, y, 0);
        screen.mouseReleased(x, y, 0);
        screen.charTyped('a', 0);
        screen.keyPressed(GLFW.GLFW_KEY_ENTER, 0, 0);
        screen.charTyped('b', 0);
        int tableIndex = number(table, "blockIndex");
        TableBlock model = (TableBlock) editor.document().blocks().get(tableIndex);
        check(model.rows().getFirst().cells().getFirst().runs().stream().map(TextRun::text)
                .reduce("", String::concat).equals("a\nb"), "Enter inserts newline inside table cell");
        screen.keyPressed(GLFW.GLFW_KEY_A, 0, GLFW.GLFW_MOD_CONTROL);
        screen.keyPressed(GLFW.GLFW_KEY_I, 0, GLFW.GLFW_MOD_CONTROL);
        model = (TableBlock) editor.document().blocks().get(tableIndex);
        check(model.rows().getFirst().cells().getFirst().runs().stream().allMatch(r -> r.style().italic()),
                "table selection uses shared italic command");
        screen.keyPressed(GLFW.GLFW_KEY_TAB, 0, 0);
        check(((TableBlock) editor.document().blocks().get(tableIndex)).rows().size() == 2,
                "Tab at table end appends row");
        screen.keyPressed(GLFW.GLFW_KEY_Z, 0, GLFW.GLFW_MOD_CONTROL);
        check(((TableBlock) editor.document().blocks().get(tableIndex)).rows().size() == 1,
                "table row append participates in shared undo");
        clickText(0, 1);
        screen.charTyped('R', 0);
        check(WikiDocumentEditor.blockText(editor.document().blocks().getFirst()).contains("R"),
                "click from table returns input focus to prose");
    }

    private static void exerciseImages() throws Exception {
        exerciseResizeCorners();
        ImageBlock original = (ImageBlock) editor.document().blocks().get(2);
        Object bounds = call(screen, "bounds");
        call(screen, "ensureLayout", new Class<?>[]{int.class}, number(bounds, "bodyWidth"));
        Object image = ((List<?>) field(screen, "imageLayouts")).getFirst();
        double x = number(bounds, "bodyX") + number(image, "xOffset") + number(image, "width") / 2.0;
        double y = number(bounds, "bodyTop") + number(image, "y") + number(image, "height") / 2.0;
        screen.mouseClicked(x, y, 0);
        screen.mouseDragged(x + 24, y + 12, 0, 24, 12);
        screen.mouseReleased(x + 24, y + 12, 0);
        ImageBlock moved = (ImageBlock) editor.document().blocks().get(2);
        check(moved.offsetX() > original.offsetX() && moved.offsetY() > original.offsetY(), "picture drags on first press outside its original line");
        editor.updateImageBlock(2, new ImageBlock(original.assetHash(), "", original.alignment(), original.requestedWidth(),
                ImageLayoutMode.FLOATING, original.offsetX(), original.offsetY()));
        screen.refreshDocument();
        clickText(0, 0);
        call(screen, "ensureLayout", new Class<?>[]{int.class}, number(bounds, "bodyWidth"));
        image = ((List<?>) field(screen, "imageLayouts")).getFirst();
        boolean overlapClicked = false;
        for (Object line : (List<?>) field(screen, "layoutLines")) {
            int lineY = number(line, "y");
            if (lineY < number(image, "y") || lineY + 4 >= number(image, "y") + number(image, "height")) continue;
            for (Object glyph : (List<?>) call(line, "fragments")) {
                float glyphX = ((Number) call(glyph, "x")).floatValue();
                if (glyphX > number(image, "xOffset") && glyphX + 5 < number(image, "xOffset") + number(image, "width")) {
                    screen.mouseClicked(number(bounds, "bodyX") + glyphX + 1, number(bounds, "bodyTop") + lineY + 3, 0);
                    screen.mouseReleased(number(bounds, "bodyX") + glyphX + 1, number(bounds, "bodyTop") + lineY + 3, 0);
                    check((int) field(screen, "selectedImageBlock") < 0 && (int) field(screen, "caretBlock") >= 0,
                            "behind-text picture does not steal prose click");
                    overlapClicked = true;
                    break;
                }
            }
            if (overlapClicked) break;
        }
        check(overlapClicked, "behind-text overlap exercised");
        editor.updateImageBlock(2, original);
        screen.refreshDocument();
        Object oldScreen = Minecraft.getInstance().screen;
        screen.mouseClicked(number(bounds, "modeX") + 5, number(bounds, "headerY") + 5, 0);
        screen.mouseClicked(number(bounds, "modeX") + 5, number(bounds, "headerY") + 5, 0);
        check(Minecraft.getInstance().screen == oldScreen, "mode switch retains the same screen instance");
        dragText(0, 0, 4);
    }

    private static void exerciseUnifiedCommands() throws Exception {
        EditorCommand[] commands = {BOLD, ITALIC, UNDERLINE, STRIKETHROUGH, SUPERSCRIPT, SUBSCRIPT,
                HIDDEN, ALIGN_LEFT, ALIGN_CENTER, ALIGN_RIGHT, ALIGN_JUSTIFY};
        int[] keys = {GLFW.GLFW_KEY_B, GLFW.GLFW_KEY_I, GLFW.GLFW_KEY_U, GLFW.GLFW_KEY_X,
                GLFW.GLFW_KEY_EQUAL, GLFW.GLFW_KEY_EQUAL, GLFW.GLFW_KEY_H,
                GLFW.GLFW_KEY_L, GLFW.GLFW_KEY_E, GLFW.GLFW_KEY_R, GLFW.GLFW_KEY_J};
        int[] extra = {0, 0, 0, GLFW.GLFW_MOD_SHIFT, GLFW.GLFW_MOD_SHIFT, 0, GLFW.GLFW_MOD_SHIFT, 0, 0, 0, 0};
        for (int i = 0; i < commands.length; i++) {
            fresh(List.of(paragraph("same selection"))); dragText(0, 0, 4);
            action(i < 7 ? 0 : 1, commands[i]);
            WikiDocument buttonResult = editor.document();
            fresh(List.of(paragraph("same selection"))); dragText(0, 0, 4);
            screen.keyPressed(keys[i], 0, GLFW.GLFW_MOD_CONTROL | extra[i]);
            check(editor.document().equals(buttonResult), commands[i] + " button and shortcut use identical edit");
        }
        fresh(List.of(new TableBlock(List.of(new TableRow(List.of(
                new TableCell(List.of(new TextRun("cell text", TextStyle.DEFAULT))))))), paragraph("tail")));
        Object b = call(screen, "bounds");
        call(screen, "ensureLayout", new Class<?>[]{int.class}, number(b, "bodyWidth"));
        Object table = ((List<?>) field(screen, "tableLayouts")).getFirst();
        double x = number(b, "bodyX") + 7, y = number(b, "bodyTop") + number(table, "y") + 7;
        screen.mouseClicked(x, y, 0); screen.mouseReleased(x, y, 0);
        screen.keyPressed(GLFW.GLFW_KEY_A, 0, GLFW.GLFW_MOD_CONTROL);
        Minecraft.getInstance().keyboardHandler.setClipboard("keep clipboard");
        screen.keyPressed(GLFW.GLFW_KEY_X, 0, GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_SHIFT);
        TableCell cell = ((TableBlock) editor.document().blocks().getFirst()).rows().getFirst().cells().getFirst();
        check(cell.runs().stream().map(TextRun::text).reduce("", String::concat).equals("cell text"),
                "table Ctrl+Shift+X keeps selected text instead of cutting");
        check(cell.runs().stream().allMatch(r -> r.style().strikethrough()),
                "table Ctrl+Shift+X applies strikethrough");
        check(Minecraft.getInstance().keyboardHandler.getClipboard().equals("keep clipboard"),
                "table strikethrough leaves system clipboard unchanged");
        screen.keyPressed(GLFW.GLFW_KEY_Z, 0, GLFW.GLFW_MOD_CONTROL);
        check(((TableBlock) editor.document().blocks().getFirst()).rows().getFirst().cells().getFirst()
                .runs().stream().noneMatch(r -> r.style().strikethrough()), "table format participates in shared undo");
        action(0, STRIKETHROUGH);
        check(((TableBlock) editor.document().blocks().getFirst()).rows().getFirst().cells().getFirst()
                .runs().stream().allMatch(r -> r.style().strikethrough()), "table button retains cell selection");

        PendingSaveSession pending = new PendingSaveSession();
        freshSaveSession(pending, new int[]{0});
        b = call(screen, "bounds");
        screen.mouseClicked(number(b, "bodyX") + 20, number(b, "headerY") + 6, 0);
        screen.keyPressed(GLFW.GLFW_KEY_A, 0, GLFW.GLFW_MOD_CONTROL);
        typeText("Renamed with Ctrl+S");
        screen.keyPressed(GLFW.GLFW_KEY_S, 0, GLFW.GLFW_MOD_CONTROL);
        check(pending.snapshots.size() == 1 && pending.snapshots.getFirst().title().equals("Renamed with Ctrl+S"),
                "title Ctrl+S commits current title and requests a confirmed save");
        check(!(boolean) field(screen, "editingTitle"), "title Ctrl+S returns focus to document");
        pending.replies.getFirst().complete(new DocumentSession.SaveConfirmation(true, "saved"));
    }

    private static void exerciseLockModalIsolation() throws Exception {
        DocumentSession lockable = new DocumentSession() {
            public boolean canEdit() { return true; }
            public boolean canChangeLock() { return true; }
            public String save(WikiDocument doc) { return "saved"; }
            public String personalAnnotationKey() { return "modal-smoke-" + ProcessHandle.current().pid(); }
        };
        List<Block> content = new ArrayList<>();
        for (int i = 0; i < 60; i++) content.add(paragraph("Modal test line " + i));
        editor = new WikiDocumentEditor(document(content));
        screen = new NativeWikiScreen(editor, lockable, DocumentMode.EDIT, 0, () -> {});
        Minecraft.getInstance().setScreen(screen);
        dragText(0, 0, 5);
        Object b = call(screen, "bounds");
        screen.mouseClicked(number(b, "lockButtonX") + 4, number(b, "headerY") + 5, 0);
        check((boolean) field(screen, "lockConfirmationOpen"), "lock click opens confirmation");
        WikiDocument before = editor.document();
        int scroll = (int) field(screen, "bodyScroll");
        screen.charTyped('X', 0);
        screen.keyPressed(GLFW.GLFW_KEY_BACKSPACE, 0, 0);
        screen.keyPressed(GLFW.GLFW_KEY_B, 0, GLFW.GLFW_MOD_CONTROL);
        screen.mouseClicked(number(b, "bodyX") + 8, number(b, "bodyTop") + 5, GLFW.GLFW_MOUSE_BUTTON_RIGHT);
        screen.mouseScrolled(number(b, "bodyX") + 8, number(b, "bodyTop") + 5, 0, -8);
        check(editor.document() == before, "lock modal blocks typing deletion and format shortcuts");
        check(!((AnnotationWorkspace) field(screen, "annotationWorkspace")).editing(),
                "lock modal blocks right-click annotation creation");
        check((int) field(screen, "bodyScroll") == scroll, "lock modal blocks scrolling behind dialog");
        screen.keyPressed(GLFW.GLFW_KEY_ESCAPE, 0, 0);
        check(!(boolean) field(screen, "lockConfirmationOpen") && Minecraft.getInstance().screen == screen,
                "lock modal Escape dismisses only the child dialog");
    }

    private static void exerciseAnnotationFailureRecovery() throws Exception {
        String namespace = "annotation-failure-" + ProcessHandle.current().pid();
        DocumentSession localOnly = new DocumentSession() {
            public boolean canEdit() { return true; }
            public String save(WikiDocument doc) { throw new AssertionError("Comments must not save shared body"); }
            public String personalAnnotationKey() { return namespace; }
        };
        editor = new WikiDocumentEditor(document(List.of(paragraph("annotation failure test"))));
        screen = new NativeWikiScreen(editor, localOnly, DocumentMode.READ, 0, () -> {});
        Minecraft.getInstance().setScreen(screen);
        dragText(0, 0, 5);
        Object b = call(screen, "bounds");
        screen.mouseClicked(number(b, "bodyX") + 10, number(b, "bodyTop") + 5, GLFW.GLFW_MOUSE_BUTTON_RIGHT);
        AnnotationWorkspace notes = (AnnotationWorkspace) field(screen, "annotationWorkspace");
        Path directory = StoragePaths.dataRoot(FMLPaths.CONFIGDIR.get()).resolve("annotations");
        String key = LocalAnnotationStore.versionKey(namespace, new WikiDocumentCodec().contentHash(editor.document()));
        Path file = directory.resolve(key + ".comments");
        // This isolated smoke namespace is created by the constructor above; force a real file-write failure.
        Files.delete(file);
        Files.createDirectory(file);
        WikiDocument original = editor.document();
        try {
            typeText("draft");
            check(notes.editing() && notes.input().text().equals("draft") && notes.draftChanged(),
                    "failed comment autosave retains complete typed draft");
            clickText(0, 12);
            check(notes.editing() && notes.input().text().equals("draft"),
                    "failed focus switch keeps annotation composer open");
            screen.mouseClicked(number(b, "bodyX") + 15, number(b, "bodyTop") + 5, GLFW.GLFW_MOUSE_BUTTON_RIGHT);
            check(notes.input().text().equals("draft"), "failed right-click focus switch retains draft");
            screen.onClose();
            check(Minecraft.getInstance().screen == screen && notes.editing(),
                    "failed annotation close keeps editor available for retry");
        } finally {
            Files.delete(file);
        }
        clickText(0, 12);
        check(!notes.editing() && new LocalAnnotationStore(directory).loadTextAnnotations(key)
                .getFirst().comment().equals("draft"), "restored storage retries draft and permits focus switch");
        check(editor.document() == original, "reader comments and their retry never mutate shared body");
        call(screen, "beginExistingCommentEditing", new Class<?>[]{int.class}, 0);
        screen.keyPressed(GLFW.GLFW_KEY_A, 0, GLFW.GLFW_MOD_CONTROL);
        Minecraft.getInstance().keyboardHandler.setClipboard("first\r\n\r\nlast");
        screen.keyPressed(GLFW.GLFW_KEY_V, 0, GLFW.GLFW_MOD_CONTROL);
        check(notes.input().text().equals("first\n\nlast"), "comment paste preserves blank lines while normalizing CRLF");
        screen.keyPressed(GLFW.GLFW_KEY_ESCAPE, 0, 0);
    }

    private static void exerciseTableDeletion() throws Exception {
        for (int action = 0; action < 3; action++) {
            Object bounds = call(screen, "bounds");
            call(screen, "ensureLayout", new Class<?>[]{int.class}, number(bounds, "bodyWidth"));
            Object table = ((List<?>) field(screen, "tableLayouts")).getFirst();
            double x = number(bounds, "bodyX") + 7;
            double y = number(bounds, "bodyTop") + number(table, "y") + 7;
            WikiDocument before = editor.document();
            screen.mouseClicked(x, y, GLFW.GLFW_MOUSE_BUTTON_RIGHT);
            check(field(screen, "tableContextMenu") != null, "table context menu opens " + action);
            int mx = ((Number) call(screen, "tableContextX", new Class<?>[]{bounds.getClass()}, bounds)).intValue();
            int my = ((Number) call(screen, "tableContextY", new Class<?>[]{bounds.getClass()}, bounds)).intValue();
            screen.mouseClicked(mx + 5, my + action * 18 + 5, 0);
            check(editor.document().blocks().stream().noneMatch(b -> b instanceof TableBlock),
                    "table deletion action removes final row/column/table " + action);
            screen.keyPressed(GLFW.GLFW_KEY_Z, 0, GLFW.GLFW_MOD_CONTROL);
            check(editor.document().equals(before), "table deletion undo restores content " + action);
        }
    }

    private static void exerciseClipboardImage() throws Exception {
        fresh(List.of(paragraph("clipboard image insertion")));
        String script = """
                $ErrorActionPreference = 'Stop'
                [Console]::InputEncoding = New-Object System.Text.UTF8Encoding($false)
                [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)
                [System.IO.File]::AppendAllText($env:WRITABLEBOOKWIKI_FIXTURE_LOG, "started`n")
                Add-Type -AssemblyName System.Windows.Forms
                Add-Type -AssemblyName System.Drawing
                [System.IO.File]::AppendAllText($env:WRITABLEBOOKWIKI_FIXTURE_LOG, "assemblies loaded`n")
                $oldData = [System.Windows.Forms.Clipboard]::GetDataObject()
                $saved = New-Object System.Windows.Forms.DataObject
                if ($null -ne $oldData) {
                    foreach ($format in $oldData.GetFormats($false)) {
                        $saved.SetData($format, $false, $oldData.GetData($format, $false))
                    }
                }
                $picture = New-Object System.Drawing.Bitmap(32, 16)
                [System.IO.File]::AppendAllText($env:WRITABLEBOOKWIKI_FIXTURE_LOG, "snapshot complete`n")
                try {
                    $picture.SetPixel(1, 1, [System.Drawing.Color]::Red)
                    [System.Windows.Forms.Clipboard]::SetImage($picture)
                    [System.IO.File]::AppendAllText($env:WRITABLEBOOKWIKI_FIXTURE_LOG, "image set`n")
                    [Console]::WriteLine('READY')
                    $null = [Console]::ReadLine()
                    [System.Windows.Forms.Clipboard]::SetText('PASTE')
                    [Console]::WriteLine('READY')
                    $null = [Console]::ReadLine()
                } finally {
                    [System.Windows.Forms.Clipboard]::SetDataObject($saved, $true)
                    [System.IO.File]::AppendAllText($env:WRITABLEBOOKWIKI_FIXTURE_LOG, "restored`n")
                    $picture.Dispose()
                }
                """;
        Path shell = Path.of(System.getenv("SystemRoot"), "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
        String encoded = Base64.getEncoder().encodeToString(script.getBytes(java.nio.charset.StandardCharsets.UTF_16LE));
        ProcessBuilder bridgeBuilder = new ProcessBuilder(shell.toString(), "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden",
                "-STA", "-InputFormat", "Text", "-OutputFormat", "Text", "-EncodedCommand", encoded)
                .redirectError(OUTPUT.resolve("clipboard-fixture-error.txt").toFile());
        bridgeBuilder.environment().put("WRITABLEBOOKWIKI_FIXTURE_LOG", OUTPUT.resolve("clipboard-fixture.log").toAbsolutePath().toString());
        Process bridge = bridgeBuilder.start();
        var output = bridge.inputReader();
        var input = bridge.outputWriter();
        try {
            awaitClipboard(output, bridge);
            clickText(0, 1);
            WikiDocument before = editor.document();
            screen.keyPressed(GLFW.GLFW_KEY_V, 0, GLFW.GLFW_MOD_CONTROL);
            check(editor.document().blocks().stream().anyMatch(b -> b instanceof ImageBlock),
                    "system clipboard bitmap pastes through Ctrl+V");
            screen.keyPressed(GLFW.GLFW_KEY_Z, 0, GLFW.GLFW_MOD_CONTROL);
            check(editor.document().equals(before), "clipboard image undo restores paragraph");
            clickText(0, 1);
            input.newLine(); input.flush();
            awaitClipboard(output, bridge);
            screen.keyPressed(GLFW.GLFW_KEY_V, 0, GLFW.GLFW_MOD_CONTROL);
            check(WikiDocumentEditor.blockText(editor.document().blocks().getFirst()).contains("PASTE"),
                    "text clipboard still pastes normally");
        } finally {
            input.close();
            boolean ended = bridge.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
            check(ended && bridge.exitValue() == 0,
                    "system clipboard restored after test; exit=" + (ended ? bridge.exitValue() : "running"));
        }
    }

    private static void awaitClipboard(java.io.BufferedReader reader, Process process) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (!reader.ready() && process.isAlive() && System.nanoTime() < deadline) Thread.sleep(10);
        String response = reader.ready() ? reader.readLine() : "no output";
        check("READY".equals(response), "clipboard fixture ready: " + response);
    }

    private static void exerciseResizeCorners() throws Exception {
        ImageBlock original = (ImageBlock) editor.document().blocks().get(2);
        for (boolean left : new boolean[]{false, true}) {
            for (boolean top : new boolean[]{false, true}) {
                editor.updateImageBlock(2, new ImageBlock(original.assetHash(), "", ImageAlignment.LEFT,
                        200, ImageLayoutMode.COVER, 100, 100));
                screen.refreshDocument();
                Object bounds = call(screen, "bounds");
                call(screen, "ensureLayout", new Class<?>[]{int.class}, number(bounds, "bodyWidth"));
                Object layout = call(screen, "imageLayout", new Class<?>[]{int.class}, 2);
                int ix = number(layout, "xOffset"), iy = number(layout, "y");
                int w = number(layout, "width"), h = number(layout, "height");
                int bx = number(bounds, "bodyX"), by = number(bounds, "bodyTop");
                screen.mouseClicked(bx + ix + w / 2, by + iy + h / 2, 0);
                screen.mouseReleased(bx + ix + w / 2, by + iy + h / 2, 0);
                double x = bx + ix + (left ? 0 : w), y = by + iy + (top ? 0 : h);
                screen.mouseClicked(x, y, 0);
                check(field(screen, "imageResize") != null, "corner begins resize " + left + "/" + top);
                int dx = left ? -20 : 20, dy = top ? -10 : 10;
                screen.mouseDragged(x + dx, y + dy, 0, dx, dy);
                call(screen, "ensureLayout", new Class<?>[]{int.class}, number(bounds, "bodyWidth"));
                Object preview = call(screen, "imageLayout", new Class<?>[]{int.class}, 2);
                check(number(preview, "width") == w + 20, "corner outward drag grows " + left + "/" + top);
                check(number(preview, "xOffset") + (left ? number(preview, "width") : 0) == ix + (left ? w : 0)
                        && number(preview, "y") + (top ? number(preview, "height") : 0) == iy + (top ? h : 0),
                        "opposite corner stays fixed " + left + "/" + top);
                screen.mouseReleased(x + dx, y + dy, 0);
                call(screen, "ensureLayout", new Class<?>[]{int.class}, number(bounds, "bodyWidth"));
                Object committed = call(screen, "imageLayout", new Class<?>[]{int.class}, 2);
                check(number(committed, "width") == number(preview, "width")
                        && number(committed, "xOffset") == number(preview, "xOffset")
                        && number(committed, "y") == number(preview, "y"), "resize does not jump on release");
                call(screen, "clearSelection");
            }
        }
        editor.updateImageBlock(2, original);
        screen.refreshDocument();
    }

    private static void fresh(List<Block> blocks) {
        editor=new WikiDocumentEditor(document(blocks));screen=new NativeWikiScreen(editor,SESSION,DocumentMode.EDIT,0,()->{});
        Minecraft.getInstance().setScreen(screen);
    }
    private static void action(int tab, EditorCommand action) throws Exception {
        Object bounds=call(screen,"bounds");
        screen.mouseClicked(number(bounds,"modeX")+tab*45+8,number(bounds,"toolbarY")+6,0);
        bounds=call(screen,"bounds");
        for(Object button:(List<?>)call(screen,"toolbarButtons",new Class<?>[]{bounds.getClass()},bounds)) {
            if(call(button,"command")==action) {
                screen.mouseClicked(number(button,"x")+4,number(button,"y")+6,0);screen.mouseReleased(number(button,"x")+4,number(button,"y")+6,0);return;
            }
        }
        throw new AssertionError("missing ribbon action "+action);
    }
    private static void exerciseRichEditing() throws Exception {
        fresh(List.of(paragraph("abcd")));dragText(0,0,4);
        action(0, SUPERSCRIPT);action(0, HIDDEN);action(0, INLINE_CODE);action(0, UNDERLINE);action(0, BOLD);
        TextStyle style=editor.document().blocks().getFirst().runs().getFirst().style();
        check(style.script()==1&&style.hidden()&&style.code()&&style.underline()&&style.bold(),"ribbon formats compose without losing inline attributes");
        action(1, ALIGN_CENTER);check(((RichTextBlock)editor.document().blocks().getFirst()).alignment()==RichTextBlock.Alignment.CENTER,"center alignment changes paragraph");
        clickText(0,2);check((int)field(screen,"caretColumn")==2,"centered text caret uses painted positions");
        action(1, BULLET);screen.keyPressed(GLFW.GLFW_KEY_END,0,0);screen.keyPressed(GLFW.GLFW_KEY_ENTER,0,0);
        check(editor.document().blocks().get(1) instanceof RichTextBlock r&&r.kind()==RichTextBlock.Kind.BULLET,"Enter continues bullet list");
        screen.keyPressed(GLFW.GLFW_KEY_ENTER,0,0);
        check(editor.document().blocks().get(1) instanceof RichTextBlock r&&r.kind()==RichTextBlock.Kind.NORMAL,"empty list Enter exits list");
        fresh(List.of(paragraph("task")));clickText(0,0);action(1, TASK);
        Object bounds=call(screen,"bounds");screen.mouseClicked(number(bounds,"bodyX")+5,number(bounds,"bodyTop")+7,0);
        check(((RichTextBlock)editor.document().blocks().getFirst()).checked(),"task checkbox toggles in edit mode");
        screen.keyPressed(GLFW.GLFW_KEY_Z,0,GLFW.GLFW_MOD_CONTROL);
        check(!((RichTextBlock)editor.document().blocks().getFirst()).checked(),"task checkbox supports undo");
        screen.setDocumentMode(DocumentMode.READ);bounds=call(screen,"bounds");screen.mouseClicked(number(bounds,"bodyX")+5,number(bounds,"bodyTop")+7,0);
        check(!((RichTextBlock)editor.document().blocks().getFirst()).checked(),"reader task click does not mutate shared document");
        fresh(List.of(paragraph("beforeafter")));clickText(0,6);action(2, RULE);
        check(editor.document().blocks().get(1) instanceof HorizontalRule,"horizontal rule is a structural block");
        screen.keyPressed(GLFW.GLFW_KEY_BACKSPACE,0,0);check(editor.document().blocks().size()==2,"backspace after rule removes only the rule");
        fresh(List.of(paragraph("open link")));dragText(0,0,4);screen.keyPressed(GLFW.GLFW_KEY_K,0,GLFW.GLFW_MOD_CONTROL);
        ((net.minecraft.client.gui.components.EditBox)field(screen,"linkInput")).setValue("https://example.com");
        screen.keyPressed(GLFW.GLFW_KEY_ENTER,0,0);
        check("https://example.com".equals(editor.document().blocks().getFirst().runs().getFirst().linkTarget()),"Ctrl+K dialog applies hyperlink to selected text");
        fresh(List.of(new Paragraph(List.of(new TextRun("secret",TextStyle.DEFAULT.withHidden(true))))));
        screen.setDocumentMode(DocumentMode.READ);clickText(0,1);
        check(((Set<?>)field(screen,"revealedSpoilers")).size()==1,"reader click reveals one spoiler run");clickText(0,1);
        check(((Set<?>)field(screen,"revealedSpoilers")).isEmpty(),"reader click conceals revealed spoiler");
        fresh(List.of(new Paragraph(List.of(new TextRun("run",TextStyle.DEFAULT,"command:say hi")))));clickText(0,1);
        check(((String)field(screen,"statusText")).isEmpty(),"command text remains editable without executing");
        screen.setDocumentMode(DocumentMode.READ);clickText(0,1);
        check(((String)field(screen,"statusText")).contains("进入世界"),"command click requires an actual player connection");
        fresh(List.of(paragraph("layout")));
        for(int width:new int[]{360,800}) {
            screen.resize(Minecraft.getInstance(),width,500);
            for(int tab=0;tab<4;tab++) {
                bounds=call(screen,"bounds");screen.mouseClicked(number(bounds,"modeX")+tab*45+8,number(bounds,"toolbarY")+5,0);
                bounds=call(screen,"bounds");
                for(Object button:(List<?>)call(screen,"toolbarButtons",new Class<?>[]{bounds.getClass()},bounds))
                    if(number(button,"x")+number(button,"width")>number(bounds,"right") || number(button,"y")+18>number(bounds,"bodyTop"))throw new AssertionError("ribbon overlaps document");
            }
        }
        check(true,"all ribbon tabs fit both narrow and normal GUI sizes");
    }
    private static void clickColor(int index) throws Exception {
        Object bounds = call(screen, "bounds");
        Object popup = call(screen, "colorPaletteBounds", new Class<?>[]{bounds.getClass()}, bounds);
        int x = number(popup, "x") + 6 + index % number(popup, "columns") * 18;
        int y = number(popup, "y") + 20 + index / number(popup, "columns") * 18;
        screen.mouseClicked(x + 8, y + 8, 0);
        screen.mouseReleased(x + 8, y + 8, 0);
    }

    private static void exerciseColorPalette() throws Exception {
        fresh(List.of(paragraph("abcd"))); dragText(0, 1, 3);
        Object bounds = call(screen, "bounds");
        int bodyTop = number(bounds, "bodyTop");
        check(number(bounds, "bottom") - number(bounds, "top") <= 380 && number(bounds, "top") >= 40,
                "book workspace leaves visible world margins at normal size");
        check(number(bounds, "bodyWidth") == 310, "book styling preserves default text width");
        action(0, TEXT_COLOR);
        check(((int[]) field(screen, "COLOR_PRESETS")).length == 60, "palette contains 60 preset colours");
        check(number(call(screen, "bounds"), "bodyTop") == bodyTop, "palette does not push document down");
        clickColor(23);
        check(editor.document().blocks().getFirst().runs().stream().anyMatch(r -> r.text().equals("bc") && r.style().rgbColor() == 0xFF55FF),
                "preset applies exactly to selected text without losing selection");
        screen.keyPressed(GLFW.GLFW_KEY_Z, 0, GLFW.GLFW_MOD_CONTROL);
        check(editor.document().blocks().getFirst().runs().stream().allMatch(r -> r.style().rgbColor() == TextStyle.DEFAULT.rgbColor()),
                "preset colour change participates in undo");
        clickText(0, 4); action(0, TEXT_COLOR);
        var input = (net.minecraft.client.gui.components.EditBox) field(screen, "colorInput");
        for (char c : "#0A31FF".toCharArray()) screen.charTyped(c, 0);
        check(input.getValue().equals("#0A31FF"), "typing custom colour replaces selected hex field");
        screen.keyPressed(GLFW.GLFW_KEY_ENTER, 0, 0); screen.charTyped('Z', 0);
        check(editor.document().blocks().getFirst().runs().stream().anyMatch(r -> r.text().equals("Z") && r.style().rgbColor() == 0x0A31FF),
                "custom hex colour applies to subsequent typing");
        WikiDocument before = editor.document();
        action(0, TEXT_COLOR); ((net.minecraft.client.gui.components.EditBox) field(screen, "colorInput")).setValue("#oops");
        screen.keyPressed(GLFW.GLFW_KEY_ENTER, 0, 0);
        check((boolean) call(screen, "isColorMenu") && editor.document().equals(before), "invalid colour keeps popup open without editing text");
        screen.keyPressed(GLFW.GLFW_KEY_ESCAPE, 0, 0);
        check(!(boolean) call(screen, "isColorMenu") && Minecraft.getInstance().screen == screen, "Escape dismisses palette without closing book");
        action(0, TEXT_COLOR); screen.setDocumentMode(DocumentMode.READ);
        check(!(boolean) call(screen, "isColorMenu") && editor.document().equals(before),
                "forced reader mode dismisses editing palette without mutating text");
        screen.setDocumentMode(DocumentMode.EDIT);
        action(3, PAPER_COLOR); clickColor(49);
        check((int) field(screen, "paperColorRgb") == 0xFFF1E6 && editor.document().equals(before),
                "paper colour is independent of text and shared document hash");
        var appearance = new LocalAppearanceStore(com.writablebookwiki.local.StoragePaths.dataRoot(FMLPaths.CONFIGDIR.get()).resolve("appearance.properties"));
        check(appearance.loadPaperColor() == 0xFFF1E6, "paper preference is saved only in local configuration");
        screen.setDocumentMode(DocumentMode.READ);
        check((int) field(screen, "paperColorRgb") == 0xFFF1E6, "reader uses same selected paper colour");
        fresh(List.of(paragraph("new book")));
        check((int) field(screen, "paperColorRgb") == 0xFFF1E6, "paper preference survives reopening a book");
        screen.resize(Minecraft.getInstance(), 360, 240); clickText(0, 0); action(0, TEXT_COLOR);
        bounds = call(screen, "bounds");
        Object popup = call(screen, "colorPaletteBounds", new Class<?>[]{bounds.getClass()}, bounds);
        check(number(popup, "x") + number(popup, "width") <= number(bounds, "right")
                        && number(popup, "y") + number(popup, "height") <= number(bounds, "bottom"),
                "full palette fits narrow game viewport");
        screen.keyPressed(GLFW.GLFW_KEY_ESCAPE, 0, 0);
        action(3, PAPER_COLOR);
        bounds = call(screen, "bounds"); popup = call(screen, "colorPaletteBounds", new Class<?>[]{bounds.getClass()}, bounds);
        screen.mouseClicked(number(popup, "x") + 140, number(popup, "inputY") + 6, 0);
        check(appearance.loadPaperColor() == LocalAppearanceStore.DEFAULT_PAPER_RGB, "default button restores paper preference");
        fresh(List.of(new TableBlock(List.of(new TableRow(List.of(new TableCell(List.of(new TextRun("cell", TextStyle.DEFAULT)))))))));
        bounds = call(screen, "bounds");
        screen.mouseClicked(number(bounds, "bodyX") + 7, number(bounds, "bodyTop") + 7, 0);
        screen.mouseReleased(number(bounds, "bodyX") + 7, number(bounds, "bodyTop") + 7, 0);
        screen.keyPressed(GLFW.GLFW_KEY_A, 0, GLFW.GLFW_MOD_CONTROL); action(0, TEXT_COLOR); clickColor(31);
        TableBlock table = (TableBlock) editor.document().blocks().getFirst();
        check(table.rows().getFirst().cells().getFirst().runs().stream().allMatch(r -> r.style().rgbColor() == 0x1F5E99),
                "palette formats selected table-cell text");
    }

    private static final class PendingSaveSession implements DocumentSession {
        final List<WikiDocument> snapshots = new ArrayList<>();
        final List<java.util.concurrent.CompletableFuture<SaveConfirmation>> replies = new ArrayList<>();
        public boolean canEdit() { return true; }
        public String personalAnnotationKey() { return "exit-save-"+ProcessHandle.current().pid(); }
        public String save(WikiDocument doc) { throw new AssertionError("must wait for confirmed save"); }
        public java.util.concurrent.CompletableFuture<SaveConfirmation> saveConfirmed(WikiDocument doc) {
            snapshots.add(doc);
            var reply = new java.util.concurrent.CompletableFuture<SaveConfirmation>(); replies.add(reply); return reply;
        }
    }

    private static void freshSaveSession(DocumentSession session, int[] closed) {
        editor = new WikiDocumentEditor(document(List.of(paragraph("save"))));
        screen = new NativeWikiScreen(editor, session, DocumentMode.EDIT, 0, () -> closed[0]++);
        Minecraft.getInstance().setScreen(screen);
    }

    private static void exerciseExitSave() throws Exception {
        PendingSaveSession pending = new PendingSaveSession(); int[] closed = {0};
        freshSaveSession(pending, closed); clickText(0, 4); screen.charTyped('A', 0);
        action(0, TEXT_COLOR); screen.keyPressed(GLFW.GLFW_KEY_ESCAPE, 0, 0);
        check(pending.snapshots.isEmpty() && Minecraft.getInstance().screen == screen, "child Escape does not save or close editor");
        call(screen,"showDocxReport",new Class<?>[]{String.class,String.class,List.class},"导出说明","路径与格式说明",List.of("说明"));
        check(closed[0] == 0 && Minecraft.getInstance().screen instanceof DocxReportScreen,
                "export report does not end document session");
        Minecraft.getInstance().screen.onClose();
        check(Minecraft.getInstance().screen == screen && closed[0] == 0 && pending.snapshots.isEmpty(),
                "return from export report preserves draft without triggering exit-save");
        screen.keyPressed(GLFW.GLFW_KEY_ESCAPE, 0, 0);
        check(pending.snapshots.size() == 1 && Minecraft.getInstance().screen == screen,
                "root Escape submits save and waits for confirmation");
        screen.charTyped('X',0); screen.keyPressed(GLFW.GLFW_KEY_ESCAPE,0,0);
        check(pending.snapshots.size() == 1 && WikiDocumentEditor.blockText(editor.document().blocks().getFirst()).equals("saveA"),
                "pending exit does not duplicate save or accept unsaved typing");
        pending.replies.getFirst().complete(new DocumentSession.SaveConfirmation(false,"模拟保存失败"));
        check(Minecraft.getInstance().screen == screen && closed[0] == 0 && !(boolean)field(screen,"closeAfterSave"),
                "failed save retains draft and restores editing");
        screen.keyPressed(GLFW.GLFW_KEY_ESCAPE,0,0);
        check(pending.snapshots.size() == 2, "Escape retries failed save");
        pending.replies.getLast().complete(new DocumentSession.SaveConfirmation(true,"saved"));
        check(Minecraft.getInstance().screen != screen && closed[0] == 1, "confirmed save closes document exactly once");

        pending = new PendingSaveSession(); closed = new int[]{0}; freshSaveSession(pending,closed);
        screen.keyPressed(GLFW.GLFW_KEY_ESCAPE,0,0);
        check(pending.snapshots.isEmpty() && closed[0] == 1, "unchanged document exits without rewriting or forking");

        pending = new PendingSaveSession(); closed = new int[]{0}; freshSaveSession(pending,closed);
        clickText(0,4); screen.charTyped('A',0); screen.keyPressed(GLFW.GLFW_KEY_S,0,GLFW.GLFW_MOD_CONTROL);
        screen.charTyped('B',0); screen.keyPressed(GLFW.GLFW_KEY_ESCAPE,0,0);
        pending.replies.getFirst().complete(new DocumentSession.SaveConfirmation(true,"saved A"));
        check(pending.snapshots.size() == 2 && WikiDocumentEditor.blockText(pending.snapshots.getLast().blocks().getFirst()).equals("saveAB"),
                "exit saves edits made while earlier manual save was pending");
        pending.replies.getLast().complete(new DocumentSession.SaveConfirmation(true,"saved AB"));
        check(closed[0] == 1, "latest confirmed draft closes after queued exit-save");

        Constructor<?> localCtor = Class.forName("com.writablebookwiki.neoforge.WikiWindow$LocalPreviewSession").getDeclaredConstructor();
        localCtor.setAccessible(true);
        freshSaveSession((DocumentSession)localCtor.newInstance(),new int[]{0});clickText(0,4);screen.charTyped('L',0);
        WikiDocument expected=editor.document(); screen.keyPressed(GLFW.GLFW_KEY_ESCAPE,0,0);
        WikiDocument stored=new LocalDocumentStore(com.writablebookwiki.local.StoragePaths.dataRoot(FMLPaths.CONFIGDIR.get()).resolve("preview-document.ewd")).load().orElseThrow();
        check(stored.equals(expected), "Escape through real preview session writes a reloadable document");
    }

    private static RichTextBlock rich(RichTextBlock.Kind kind, String text) {
        return new RichTextBlock(kind,RichTextBlock.Alignment.LEFT,0,false,List.of(new TextRun(text,TextStyle.DEFAULT)));
    }

    private static void clickFold(int index) throws Exception {
        Object b=call(screen,"bounds");call(screen,"ensureLayout",new Class<?>[]{int.class},number(b,"bodyWidth"));
        for(Object line:(List<?>)field(screen,"layoutLines")) if(number(line,"blockIndex")==index && number(line,"startColumn")==0) {
            int x=number(b,"bodyX")+number(b,"bodyWidth")-7, y=number(b,"bodyTop")+number(line,"y")-(int)field(screen,"bodyScroll")+3;
            screen.mouseClicked(x,y,0);screen.mouseReleased(x,y,0);return;
        }
        throw new AssertionError("fold target missing");
    }

    private static void exerciseFoldingAndSteps() throws Exception {
        fresh(List.of(rich(RichTextBlock.Kind.STEP,"first"),rich(RichTextBlock.Kind.STEP,"second"),paragraph("between"),rich(RichTextBlock.Kind.STEP,"new")));
        check((int)call(screen,"blockNumber",new Class<?>[]{int.class,RichTextBlock.Kind.class},1,RichTextBlock.Kind.STEP)==2
                && (int)call(screen,"blockNumber",new Class<?>[]{int.class,RichTextBlock.Kind.class},3,RichTextBlock.Kind.STEP)==1,
                "intervening prose restarts step numbering, consecutive steps continue");
        Object b=call(screen,"bounds");
        for(int tab=0;tab<4;tab++) {
            screen.mouseClicked(number(b,"modeX")+tab*45+8,number(b,"toolbarY")+6,0);
            for(Object button:(List<?>)call(screen,"toolbarButtons",new Class<?>[]{b.getClass()},b))
                if(call(button,"command")==SORT || call(button,"command")==CODE_BLOCK)throw new AssertionError("removed toolbar action visible");
        }
        check(true,"sort and code-block toolbar buttons removed");
        for(RichTextBlock.Kind kind:List.of(RichTextBlock.Kind.QUOTE,RichTextBlock.Kind.INFO,RichTextBlock.Kind.WARNING,RichTextBlock.Kind.STEP,RichTextBlock.Kind.TASK,RichTextBlock.Kind.QUOTE_TASK)) {
            fresh(List.of(rich(kind,"first")));clickText(0,5);
            screen.keyPressed(GLFW.GLFW_KEY_ENTER,0,0);screen.charTyped('N',0);
            check(editor.document().blocks().size()==1 && WikiDocumentEditor.blockText(editor.document().blocks().getFirst()).equals("first\nN"),
                    "Enter keeps multiline text in same "+kind+" container");
            screen.keyPressed(GLFW.GLFW_KEY_ENTER,0,GLFW.GLFW_MOD_SHIFT);screen.charTyped('S',0);
            check(editor.document().blocks().size()==2 && ((RichTextBlock)editor.document().blocks().getLast()).kind()==kind,
                    "Shift Enter starts next "+kind+" block");
            WikiDocument before=editor.document();b=call(screen,"bounds");call(screen,"ensureLayout",new Class<?>[]{int.class},number(b,"bodyWidth"));
            if (((RichTextBlock)before.blocks().getFirst()).isTask()) {
                check(!(boolean)call(screen,"canFold",new Class<?>[]{int.class},0),"multiline "+kind+" has no fold control");
                continue;
            }
            int originalHeight=(int)field(screen,"contentHeight");clickFold(0);
            check(((Set<?>)field(screen,"collapsedBlocks")).contains(0) && (int)field(screen,"contentHeight")<originalHeight && editor.document().equals(before),
                    "fold shrinks view without changing "+kind+" text");
            clickText(0,2);
            check(((Set<?>)field(screen,"collapsedBlocks")).contains(0),"clicking collapsed "+kind+" text does not expand");
            clickFold(0);
            check(!((Set<?>)field(screen,"collapsedBlocks")).contains(0),"same arrow expands "+kind+" in editor");
            clickFold(0);screen.setDocumentMode(DocumentMode.READ);clickText(0,2);
            check(((Set<?>)field(screen,"collapsedBlocks")).contains(0),"reader text click leaves "+kind+" collapsed");
            screen.setDocumentMode(DocumentMode.READ);clickFold(0);
            check(!((Set<?>)field(screen,"collapsedBlocks")).contains(0) && editor.document().equals(before),
                    "reader unfolds complete "+kind+" text");
        }
    }

    private static void exerciseRichClipboardAndTasks() throws Exception {
        RichTextBlock quoted = new RichTextBlock(RichTextBlock.Kind.QUOTE,RichTextBlock.Alignment.LEFT,0,false,
                List.of(new TextRun("quoted",TextStyle.DEFAULT.withBold(true).withColor(0x365F81))));
        fresh(List.of(quoted,paragraph("")));dragText(0,0,6);
        screen.keyPressed(GLFW.GLFW_KEY_C,0,GLFW.GLFW_MOD_CONTROL);
        check(NativeRichClipboard.matching("quoted")!=null,"Windows clipboard ownership retains rich whole-line copy");
        clickText(1,0);screen.keyPressed(GLFW.GLFW_KEY_V,0,GLFW.GLFW_MOD_CONTROL);
        check(editor.document().blocks().get(1).equals(quoted),"Ctrl V keeps full quote line presentation and run styles");
        screen.keyPressed(GLFW.GLFW_KEY_Z,0,GLFW.GLFW_MOD_CONTROL);
        check(editor.document().blocks().get(1).equals(paragraph("")),"rich paste is one undo step");
        dragText(0,1,4);screen.keyPressed(GLFW.GLFW_KEY_C,0,GLFW.GLFW_MOD_CONTROL);
        clickText(1,0);screen.keyPressed(GLFW.GLFW_KEY_V,0,GLFW.GLFW_MOD_CONTROL);
        check(editor.document().blocks().get(1) instanceof Paragraph && WikiDocumentEditor.blockText(editor.document().blocks().get(1)).equals("uot"),
                "partial quote text does not copy quote frame");
        check(editor.document().blocks().get(1).runs().getFirst().style().bold(),"partial text retains inline formatting");
        dragText(0,0,6);screen.setDocumentMode(DocumentMode.READ);screen.keyPressed(GLFW.GLFW_KEY_C,0,GLFW.GLFW_MOD_CONTROL);
        fresh(List.of(paragraph("")));clickText(0,0);screen.keyPressed(GLFW.GLFW_KEY_V,0,GLFW.GLFW_MOD_CONTROL);
        check(editor.document().blocks().getFirst().equals(quoted),"rich copy works from reader across reopened screens");
        Minecraft.getInstance().keyboardHandler.setClipboard("quoted");
        check(NativeRichClipboard.matching("quoted")==null,"external same-text copy cannot reuse stale quote formatting");
        check(!NativeRichClipboard.copy(ignored->{},()->"unchanged","not written",null),
                "failed clipboard write is reported instead of allowing destructive cut");

        fresh(List.of(rich(RichTextBlock.Kind.QUOTE,"intro\ntask\nafter")));clickText(0,8);action(1, TASK);
        check(editor.document().blocks().size()==3 && ((RichTextBlock)editor.document().blocks().get(1)).isTask()
                && ((RichTextBlock)editor.document().blocks().get(1)).isQuote(),"task toolbar adds current line inside quote without replacing surrounding prose");
        check((boolean)call(screen,"toolActive",new Class<?>[]{EditorCommand.class},QUOTE) && (boolean)call(screen,"toolActive",new Class<?>[]{EditorCommand.class},TASK),
                "quote and task toolbar states can both be active");
        action(1, TASK);
        check(((RichTextBlock)editor.document().blocks().get(1)).kind()==RichTextBlock.Kind.QUOTE,"removing task keeps quote frame");

        for(RichTextBlock.Kind kind:List.of(RichTextBlock.Kind.TASK,RichTextBlock.Kind.QUOTE_TASK))
            for(RichTextBlock.Alignment align:List.of(RichTextBlock.Alignment.LEFT,RichTextBlock.Alignment.CENTER,RichTextBlock.Alignment.RIGHT)) {
                fresh(List.of(new RichTextBlock(kind,align,0,false,List.of(new TextRun("Task",TextStyle.DEFAULT.withFontSize(18))))));
                Object b=call(screen,"bounds");call(screen,"ensureLayout",new Class<?>[]{int.class},number(b,"bodyWidth"));
                Object line=((List<?>)field(screen,"layoutLines")).getFirst();
                Object box=call(screen,"taskBox",new Class<?>[]{line.getClass()},line);
                double x=number(b,"bodyX")+number(box,"x"), y=number(b,"bodyTop")+number(box,"y");
                screen.mouseClicked(x+9,y+9,0);screen.mouseReleased(x+9,y+9,0);
                check(((RichTextBlock)editor.document().blocks().getFirst()).checked(),"checkbox bottom-right hit matches visible box "+kind+" "+align);
                screen.mouseClicked(x+11,y+4,0);screen.mouseReleased(x+11,y+4,0);
                check(((RichTextBlock)editor.document().blocks().getFirst()).checked(),"outside checkbox does not toggle "+kind+" "+align);
                screen.setDocumentMode(DocumentMode.READ);screen.mouseClicked(x+4,y+4,0);
                check(((RichTextBlock)editor.document().blocks().getFirst()).checked(),"reader cannot mutate checkbox "+kind+" "+align);
            }
        fresh(List.of(rich(RichTextBlock.Kind.INFO,"hint"),rich(RichTextBlock.Kind.WARNING,"warn")));
        Object b=call(screen,"bounds");call(screen,"ensureLayout",new Class<?>[]{int.class},number(b,"bodyWidth"));
        check(((List<?>)field(screen,"layoutLines")).stream().allMatch(line->{try{return number(line,"indent")==14;}catch(Exception e){throw new RuntimeException(e);}}),
                "info and warning reserve compact 14-unit icon gutters");
    }

    private static Object frameTaskLine(int index) throws Exception {
        Object b = call(screen, "bounds");
        call(screen, "ensureLayout", new Class<?>[]{int.class}, number(b, "bodyWidth"));
        for (Object line : (List<?>)field(screen, "layoutLines"))
            if (number(line,"blockIndex") == index && number(line,"startColumn") == 0) return line;
        throw new AssertionError("Task line missing");
    }

    private static void typeText(String text) {
        for (char c : text.toCharArray()) screen.charTyped(c, 0);
    }

    private static int itemNumber(int index) throws Exception {
        return (int)call(screen, "blockNumber", new Class<?>[]{int.class, RichTextBlock.Kind.class}, index, RichTextBlock.Kind.NUMBERED);
    }

    private static void exerciseTypedLists() throws Exception {
        for (RichTextBlock.Kind parent : List.of(RichTextBlock.Kind.NORMAL, RichTextBlock.Kind.QUOTE,
                RichTextBlock.Kind.INFO, RichTextBlock.Kind.WARNING)) for (String prefix : List.of("1. ", "- ", "* ")) {
            String label = parent + " typed " + prefix;
            fresh(List.of(rich(parent, ""))); clickText(0, 0);
            typeText(prefix.substring(0, prefix.length()-1));
            check(((RichTextBlock)editor.document().blocks().getFirst()).kind() == parent, label+" waits for space");
            screen.charTyped(' ',0);
            RichTextBlock.Kind kind = prefix.equals("1. ") ? RichTextBlock.Kind.NUMBERED : RichTextBlock.Kind.BULLET;
            var item = (RichTextBlock)editor.document().blocks().getFirst();
            check(item.kind() == kind && WikiDocumentEditor.blockText(item).isEmpty(), label+" keyboard space creates structural marker without literal prefix");
            check(parent == RichTextBlock.Kind.NORMAL ? item.frame() == null : item.frame().kind().name().equals(parent.name()),
                    label+" retains parent container");
            screen.keyPressed(GLFW.GLFW_KEY_Z,0,GLFW.GLFW_MOD_CONTROL);
            check(WikiDocumentEditor.blockText(editor.document().blocks().getFirst()).equals(prefix)
                    && ((RichTextBlock)editor.document().blocks().getFirst()).kind() == parent, label+" undo restores literal prefix and space");
            check((int)field(screen,"caretBlock")==0 && (int)field(screen,"caretColumn")==prefix.length(),
                    label+" undo places caret after literal prefix");
            screen.keyPressed(GLFW.GLFW_KEY_Y,0,GLFW.GLFW_MOD_CONTROL);
            typeText("first"); screen.keyPressed(GLFW.GLFW_KEY_ENTER,0,0); typeText("second");
            screen.keyPressed(GLFW.GLFW_KEY_ENTER,0,GLFW.GLFW_MOD_SHIFT); typeText("continued");
            check(editor.document().blocks().size() == 2 && WikiDocumentEditor.blockText(editor.document().blocks().get(1)).equals("second\ncontinued"),
                    label+" Enter creates next item and Shift Enter stays within item");
            screen.keyPressed(GLFW.GLFW_KEY_ENTER,0,0); typeText("third");
            check(editor.document().blocks().stream().allMatch(b -> b instanceof RichTextBlock r && r.kind() == kind && Objects.equals(r.frame(),item.frame())),
                    label+" all new items retain list and frame");
            if (kind == RichTextBlock.Kind.NUMBERED) {
                check(itemNumber(0) == 1 && itemNumber(1) == 2 && itemNumber(2) == 3, label+" numbers advance automatically");
                clickText(1,0); screen.keyPressed(GLFW.GLFW_KEY_ENTER,0,0);
                check(itemNumber(3) == 4, label+" middle insertion renumbers later items");
                clickText(2,0); screen.keyPressed(GLFW.GLFW_KEY_BACKSPACE,0,0);
                check(editor.document().blocks().size() == 3 && itemNumber(2) == 3, label+" deleting middle boundary renumbers later items");
            }
            clickText(2,5); screen.keyPressed(GLFW.GLFW_KEY_ENTER,0,0); screen.keyPressed(GLFW.GLFW_KEY_ENTER,0,0);
            check(editor.document().blocks().get(3) instanceof RichTextBlock r && !r.isList() && Objects.equals(item.frame(), r.frame()),
                    label+" empty item exits list without removing parent frame");
            Object line = frameTaskLine(0);
            Object marker = call(screen,"listMarkerBox",new Class<?>[]{line.getClass()},line);
            check(number(marker,"x") + number(marker,"width") + 4 == number(line,"indent"), label+" marker has clear gap before text");
            List<?> editLines = List.copyOf((List<?>)field(screen,"layoutLines"));
            screen.setDocumentMode(DocumentMode.READ); frameTaskLine(0);
            check(editLines.equals(field(screen,"layoutLines")), label+" read and edit share identical text layout");
            if (item.frame() != null) check(((List<?>)field(screen,"frameLayouts")).size() == 1,label+" all items remain in one continuous frame");
        }
        for (var frameKind : BlockFrame.Kind.values()) for (EditorCommand action : List.of(BULLET, NUMBERED)) {
            fresh(List.of(rich(RichTextBlock.Kind.valueOf(frameKind.name()),"intro\nitem\nafter")));
            clickText(0,8); action(1,action);
            check(editor.document().blocks().size()==3 && ((RichTextBlock)editor.document().blocks().get(1)).isList()
                    && editor.document().blocks().stream().allMatch(b-> b.frame()!=null && b.frame().kind()==frameKind),
                    frameKind+" list toolbar formats current logical line and keeps surrounding prose "+action);
        }
        fresh(List.of(rich(RichTextBlock.Kind.INFO,"intro\n\nafter")));clickText(0,6);typeText("1. ");
        check(editor.document().blocks().stream().map(WikiDocumentEditor::blockText).toList().equals(List.of("intro","","after")),
                "typed prefix on middle soft line keeps intro and outro");
        screen.keyPressed(GLFW.GLFW_KEY_Z,0,GLFW.GLFW_MOD_CONTROL);typeText("literal");
        check(WikiDocumentEditor.blockText(editor.document().blocks().getFirst()).equals("intro\n1. literal\nafter"),
                "undo middle-line autoformat permits immediate literal typing at restored caret");
        fresh(List.of(paragraph("version 1.")));clickText(0,10);typeText(" ");
        check(editor.document().blocks().getFirst() instanceof Paragraph,"inline version number is not auto formatted");
        fresh(List.of(paragraph("1.x")));dragText(0,2,3);typeText(" ");
        check(editor.document().blocks().getFirst() instanceof Paragraph,"space replacing selection does not trigger automatic list");
        fresh(List.of(paragraph("")));clickText(0,0);
        Minecraft.getInstance().keyboardHandler.setClipboard("1. pasted");
        screen.keyPressed(GLFW.GLFW_KEY_V,0,GLFW.GLFW_MOD_CONTROL);
        check(editor.document().blocks().getFirst() instanceof Paragraph,"pasted literal list prefix is not converted by typing rule");

        BlockFrame one = new BlockFrame(BlockFrame.Kind.QUOTE,1), two = new BlockFrame(BlockFrame.Kind.QUOTE,2);
        fresh(List.of(rich(RichTextBlock.Kind.NUMBERED,"a").withFrame(one),rich(RichTextBlock.Kind.NUMBERED,"b").withFrame(one),
                rich(RichTextBlock.Kind.NUMBERED,"c").withFrame(two),rich(RichTextBlock.Kind.NUMBERED,"d"),paragraph("break"),
                rich(RichTextBlock.Kind.NUMBERED,"e")));
        check(itemNumber(1)==2 && itemNumber(2)==1 && itemNumber(3)==1 && itemNumber(5)==1,
                "numbering restarts at independent frame, outside frame, and after ordinary prose");
        fresh(List.of(paragraph("")));clickText(0,0);
        screen.keyPressed(GLFW.GLFW_KEY_B,0,GLFW.GLFW_MOD_CONTROL);typeText("1. styled");
        check(editor.document().blocks().getFirst().runs().stream().allMatch(r->r.style().bold()),
                "typed list conversion preserves chosen typing style");
        var asset=SESSION.importLocalImage(OUTPUT.resolve("grid.png"));
        for(var kind:List.of(RichTextBlock.Kind.NUMBERED,RichTextBlock.Kind.BULLET)) {
            fresh(List.of(new ImageBlock(asset.assetHash(),"",ImageAlignment.LEFT,90,ImageLayoutMode.WRAP_LEFT,0,0).withFrame(one),
                    rich(kind,"Text must flow beside the wrapped picture without covering its list marker. ".repeat(3)).withFrame(one)));
            Object line=frameTaskLine(1), marker=call(screen,"listMarkerBox",new Class<?>[]{line.getClass()},line);
            Object picture=((List<?>)field(screen,"imageLayouts")).getFirst();
            check(number(marker,"x")>=number(picture,"xOffset")+number(picture,"width")
                            || number(line,"y")>=number(picture,"y")+number(picture,"height"),
                    kind+" wrapped image avoids list marker as well as text");
        }
        for(var alignment : List.of(RichTextBlock.Alignment.LEFT, RichTextBlock.Alignment.CENTER, RichTextBlock.Alignment.RIGHT)) {
            List<Block> many = new ArrayList<>();
            for(int i=0;i<105;i++) many.add(new RichTextBlock(RichTextBlock.Kind.NUMBERED,alignment,0,false,
                    List.of(new TextRun("item",TextStyle.DEFAULT)),one));
            fresh(many);Object line = frameTaskLine(104), first=frameTaskLine(0);
            Object marker=call(screen,"listMarkerBox",new Class<?>[]{line.getClass()},line);
            Object fragment=((List<?>)call(line,"fragments")).getFirst();
            int textX=Math.round(((Number)call(fragment,"x")).floatValue());
            check(number(marker,"x")+number(marker,"width")+4==textX && number(marker,"x")>=8,
                    "three digit list marker stays before aligned text and inside frame "+alignment);
            check(number(line,"indent")==number(first,"indent") && itemNumber(104)==105,
                    "long list shares stable indentation and reaches 105 "+alignment);
        }
    }

    private static void prepareListVisual() throws Exception {
        List<Block> blocks = new ArrayList<>();
        blocks.add(new Heading(1,paragraph("手动输入 1. 空格 · 自动列表").runs()));
        for(var kind:BlockFrame.Kind.values()) {
            BlockFrame frame=new BlockFrame(kind,kind.ordinal()+1);
            blocks.add(rich(RichTextBlock.Kind.NORMAL,switch(kind){case QUOTE->"引用中的列表";case INFO->"提示中的列表";case WARNING->"警告中的列表";}).withFrame(frame));
            blocks.add(rich(RichTextBlock.Kind.NUMBERED,"输入 1. 后按空格，开始自动编号。\nShift+Enter 在同一项内换行。").withFrame(frame));
            blocks.add(rich(RichTextBlock.Kind.NUMBERED,"Enter 新建下一项，增删项目自动重新编号。").withFrame(frame));
            blocks.add(rich(RichTextBlock.Kind.BULLET,"输入 - 或 * 后按空格，开始无序列表。").withFrame(frame));
        }
        fresh(blocks);clickText(2,0);
    }

    private static void exerciseMixedFrames() throws Exception {
        var imported = SESSION.importLocalImage(OUTPUT.resolve("grid.png"));
        for (BlockFrame.Kind kind : BlockFrame.Kind.values()) {
            fresh(List.of(rich(RichTextBlock.Kind.valueOf(kind.name()), "intro\ntask\nafter")));
            clickText(0,8); action(1, TASK);
            Object line = frameTaskLine(1), b = call(screen,"bounds");
            Object box = call(screen,"taskBox",new Class<?>[]{line.getClass()},line);
            int inset = kind == BlockFrame.Kind.QUOTE ? 8 : 14;
            check(number(box,"x") == inset && number(line,"indent") == inset + 14, kind+" task box aligns to frame content edge, text follows with 4-unit gap");
            check(((List<?>)field(screen,"frameLayouts")).size() == 1, kind+" task and surrounding prose share one continuous frame");
            check((boolean)call(screen,"toolActive",new Class<?>[]{EditorCommand.class},kind == BlockFrame.Kind.QUOTE ? QUOTE : kind == BlockFrame.Kind.INFO ? INFO : WARNING),
                    kind+" parent toolbar state survives task insertion");
            double tx=number(b,"bodyX")+number(box,"x")+5, ty=number(b,"bodyTop")+number(box,"y")+5;
            screen.mouseClicked(tx,ty,0);screen.mouseReleased(tx,ty,0);
            check(((RichTextBlock)editor.document().blocks().get(1)).checked(),kind+" visible nested checkbox is clickable");
            clickText(1,4);screen.keyPressed(GLFW.GLFW_KEY_ENTER,0,0);screen.charTyped('x',0);
            check(WikiDocumentEditor.blockText(editor.document().blocks().get(1)).equals("task\nx"),kind+" Enter stays in task and preserves surrounding frame");
            screen.keyPressed(GLFW.GLFW_KEY_ENTER,0,GLFW.GLFW_MOD_SHIFT);
            check(((RichTextBlock)editor.document().blocks().get(2)).isTask() && !((RichTextBlock)editor.document().blocks().get(2)).checked(),
                    kind+" Shift Enter creates unchecked task in same frame");
            screen.keyPressed(GLFW.GLFW_KEY_Z,0,GLFW.GLFW_MOD_CONTROL);
            clickText(2,2);
            call(screen,"insertImportedImage",new Class<?>[]{ImportedImageAsset.class},imported);
            int imageIndex=(int)field(screen,"selectedImageBlock");
            check(imageIndex == 3 && editor.document().blocks().get(imageIndex).frame()!=null,kind+" image insertion keeps container and surrounding text");
            editor.updateImageBlock(imageIndex,new ImageBlock(imported.assetHash(),"",ImageAlignment.LEFT,90));
            screen.refreshDocument();
            clickText(4,0); call(screen,"insertTable",new Class<?>[]{int.class,int.class},1,2);
            screen.charTyped('A',0);screen.keyPressed(GLFW.GLFW_KEY_ENTER,0,0);screen.charTyped('B',0);
            Object table=((List<?>)field(screen,"tableLayouts")).getFirst();
            int tableIndex=number(table,"blockIndex");
            check(number(table,"xOffset")==inset && number(table,"width")==number(b,"bodyWidth")-inset-12,
                    kind+" table respects both frame insets");
            check(((TableBlock)editor.document().blocks().get(tableIndex)).frame().kind()==kind,kind+" editing table preserves frame");
            b=call(screen,"bounds");call(screen,"ensureLayout",new Class<?>[]{int.class},number(b,"bodyWidth"));
            table=((List<?>)field(screen,"tableLayouts")).getFirst();
            double cellX=number(b,"bodyX")+number(table,"xOffset")+number(table,"baseColumnWidth")+6;
            double cellY=number(b,"bodyTop")+number(table,"y")+6-(int)field(screen,"bodyScroll");
            screen.mouseClicked(cellX,cellY,0);screen.mouseReleased(cellX,cellY,0);screen.charTyped('C',0);
            TableBlock model=(TableBlock)editor.document().blocks().get(tableIndex);
            check(model.rows().getFirst().cells().get(1).runs().stream().map(TextRun::text).reduce("",String::concat).equals("C"),
                    kind+" table hit test uses the same inset as drawing");
            int last=editor.document().blocks().size()-1;clickText(last,0);screen.charTyped('Z',0);
            check(WikiDocumentEditor.blockText(editor.document().blocks().getLast()).startsWith("Z"),kind+" click from table returns to trailing frame prose");
            b=call(screen,"bounds");call(screen,"ensureLayout",new Class<?>[]{int.class},number(b,"bodyWidth"));
            Object frame=((List<?>)field(screen,"frameLayouts")).getFirst();
            Object image=((List<?>)field(screen,"imageLayouts")).getFirst();
            check(number(image,"xOffset")==inset && number(image,"y")+number(image,"height")<=number(frame,"y")+number(frame,"height"),
                    kind+" picture is contained by continuous background");
            WikiDocument saved=editor.document();
            check(saved.equals(new WikiDocumentCodec().decode(new WikiDocumentCodec().encode(saved))),kind+" mixed container roundtrips stored document");
            List<?> editLines=List.copyOf((List<?>)field(screen,"layoutLines"));
            screen.setDocumentMode(DocumentMode.READ);b=call(screen,"bounds");call(screen,"ensureLayout",new Class<?>[]{int.class},number(b,"bodyWidth"));
            check(editLines.equals(field(screen,"layoutLines")),kind+" reader and editor use identical mixed-frame layout");
            clickFold(0);
            check(((List<?>)field(screen,"imageLayouts")).isEmpty() && ((List<?>)field(screen,"tableLayouts")).isEmpty(),kind+" frame arrow folds images and tables together");
            clickFold(0);
            check(((List<?>)field(screen,"imageLayouts")).size()==1 && ((List<?>)field(screen,"tableLayouts")).size()==1 && saved.equals(editor.document()),
                    kind+" frame arrow expands all objects without modifying content");
            screen.setDocumentMode(DocumentMode.EDIT);
            b=call(screen,"bounds");call(screen,"ensureLayout",new Class<?>[]{int.class},number(b,"bodyWidth"));
            Object initial=((List<?>)field(screen,"imageLayouts")).getFirst();
            call(screen,"setImageLayoutMode",new Class<?>[]{int.class,ImageLayoutMode.class},imageIndex,ImageLayoutMode.COVER);
            call(screen,"ensureLayout",new Class<?>[]{int.class},number(b,"bodyWidth"));
            image=((List<?>)field(screen,"imageLayouts")).getFirst();
            check(number(image,"xOffset")==number(initial,"xOffset") && number(image,"y")==number(initial,"y"),
                    kind+" inline to cover preserves visible position relative to frame");
            double ix=number(b,"bodyX")+number(image,"xOffset")+number(image,"width")/2d;
            double iy=number(b,"bodyTop")+number(image,"y")+number(image,"height")/2d-(int)field(screen,"bodyScroll");
            screen.mouseClicked(ix,iy,0);screen.mouseDragged(ix+20,iy+12,0,20,12);screen.mouseReleased(ix+20,iy+12,0);
            call(screen,"ensureLayout",new Class<?>[]{int.class},number(b,"bodyWidth"));
            image=((List<?>)field(screen,"imageLayouts")).getFirst();
            check(number(image,"xOffset")==number(initial,"xOffset")+20 && number(image,"y")==number(initial,"y")+12,
                    kind+" frame picture drag commits without coordinate jump");
            double rx=number(b,"bodyX")+number(image,"xOffset")+number(image,"width")-1;
            double ry=number(b,"bodyTop")+number(image,"y")+number(image,"height")-1-(int)field(screen,"bodyScroll");
            int priorWidth=number(image,"width");
            screen.mouseClicked(rx,ry,0);screen.mouseDragged(rx+18,ry+9,0,18,9);screen.mouseReleased(rx+18,ry+9,0);
            call(screen,"ensureLayout",new Class<?>[]{int.class},number(b,"bodyWidth"));
            image=((List<?>)field(screen,"imageLayouts")).getFirst();
            check(number(image,"width")>priorWidth && number(image,"xOffset")>=inset
                    && number(image,"xOffset")+number(image,"width")<=number(b,"bodyWidth")-12,
                    kind+" frame image corner resizes inside content bounds");
            BlockFrame membership=editor.document().blocks().getFirst().frame();
            fresh(List.of(new RichTextBlock(RichTextBlock.Kind.TASK,RichTextBlock.Alignment.LEFT,0,false,
                            List.of(new TextRun("Task text beside wrapped image. ".repeat(5),TextStyle.DEFAULT)),membership),
                    new ImageBlock(imported.assetHash(),"",ImageAlignment.LEFT,90,ImageLayoutMode.WRAP_LEFT,0,0,membership),
                    rich(RichTextBlock.Kind.NORMAL,"").withFrame(membership)));
            b=call(screen,"bounds");line=frameTaskLine(0);
            box=call(screen,"taskBox",new Class<?>[]{line.getClass()},line);
            image=((List<?>)field(screen,"imageLayouts")).getFirst();
            check(number(box,"x")>=number(image,"xOffset")+number(image,"width")+4,
                    kind+" wrapped picture reserves checkbox gutter instead of covering task control");
            clickText(2,0);screen.keyPressed(GLFW.GLFW_KEY_ENTER,0,GLFW.GLFW_MOD_SHIFT);
            check(editor.document().blocks().getLast().frame()==null && editor.document().blocks().getFirst().frame()!=null,
                    kind+" Shift Enter on trailing blank exits frame without removing its contents");
        }
    }

    private static void prepareMixedFrameVisual(BlockFrame.Kind kind) throws Exception {
        var imported=SESSION.importLocalImage(OUTPUT.resolve("grid.png"));
        BlockFrame frame=new BlockFrame(kind,1);
        String title=switch(kind){case QUOTE->"引用";case INFO->"提示";case WARNING->"警告";};
        fresh(List.of(new Heading(1,List.of(new TextRun(title+"里面的文字、任务、图片与表格",TextStyle.DEFAULT))),
                rich(RichTextBlock.Kind.NORMAL,"这是一整个"+title+"框。任务方框与正文左边缘对齐。").withFrame(frame),
                new RichTextBlock(RichTextBlock.Kind.TASK,RichTextBlock.Alignment.LEFT,0,true,
                        List.of(new TextRun("已完成：检查位置与勾选\n多行任务也保留相同缩进。",TextStyle.DEFAULT)),frame),
                new ImageBlock(imported.assetHash(),"",ImageAlignment.LEFT,180).withFrame(frame),
                new TableBlock(List.of(new TableRow(List.of(new TableCell(List.of(new TextRun("框内表格",TextStyle.DEFAULT))),
                        new TableCell(List.of(new TextRun("可以继续输入\n并自动换行",TextStyle.DEFAULT))))))).withFrame(frame),
                rich(RichTextBlock.Kind.NORMAL,"图片和表格之后，可以继续写框内文字。").withFrame(frame),
                paragraph("这里是框外正文，不会被背景和框内图片遮住。")));
        call(screen,"status",new Class<?>[]{String.class},"");
    }

    private static void prepareFoldVisual() throws Exception {
        fresh(List.of(new Heading(1,List.of(new TextRun("多行内容与折叠",TextStyle.DEFAULT))),
                rich(RichTextBlock.Kind.INFO,"提示：Enter 在同一块内换行。\n右上角箭头负责折叠和展开。"),
                rich(RichTextBlock.Kind.WARNING,"警告标题\n这是被折叠的完整内容，不会从文件中删除。"),
                rich(RichTextBlock.Kind.STEP,"第一组步骤"),paragraph("普通文字隔开步骤组"),rich(RichTextBlock.Kind.STEP,"这里重新从步骤 1 开始"),
                rich(RichTextBlock.Kind.QUOTE,"引用里面也可以添加任务："),
                new RichTextBlock(RichTextBlock.Kind.QUOTE_TASK,RichTextBlock.Alignment.LEFT,0,true,List.of(new TextRun("已完成的引用内任务\n任务多行但不折叠",TextStyle.DEFAULT))),
                rich(RichTextBlock.Kind.TASK,"普通任务，文字与方框对齐")));
        clickFold(2);
    }

    private static void exerciseLayoutAndScrollbar() throws Exception {
        LocalLayoutStore preferences=new LocalLayoutStore(com.writablebookwiki.local.StoragePaths.dataRoot(FMLPaths.CONFIGDIR.get()).resolve("layout.properties"));
        LocalLayoutStore.Settings original=preferences.load();
        screen.onClose(); preferences.save(LocalLayoutStore.DEFAULT);
        try {
            List<Block> content=new ArrayList<>();for(int i=0;i<80;i++)content.add(paragraph("Line "+i+" with stable wrapping and enough content to scroll"));
            fresh(content);Object b=call(screen,"bounds");
            int fixed=number(b,"bodyWidth");WikiDocument before=editor.document();
            check(fixed==310,"body text width is fixed to 310 units");
            int groupLeft=number(b,"directoryX"), groupRight=number(b,"bodyPaperX")+number(b,"bodyPaperWidth");
            check(Math.abs(groupLeft+groupRight-number(b,"left")-number(b,"right"))<=1,"directory and body group is centred");
            int dx=number(b,"directoryResizeX"), dy=number(b,"bodyTop")+36;
            screen.mouseClicked(dx,dy,0);screen.mouseDragged(dx+10,dy,0,10,0);screen.mouseReleased(dx+10,dy,0);
            b=call(screen,"bounds");
            check(number(b,"directoryWidth")==116 && number(b,"bodyWidth")==fixed,"resizing directory keeps fixed body width");
            groupLeft=number(b,"directoryX");groupRight=number(b,"bodyPaperX")+number(b,"bodyPaperWidth");
            check(Math.abs(groupLeft+groupRight-number(b,"left")-number(b,"right"))<=1,"resized group recentres without stretching body");
            dragText(0,0,4);screen.mouseClicked(number(b,"bodyX")+22,number(b,"bodyTop")+4,GLFW.GLFW_MOUSE_BUTTON_RIGHT);
            screen.charTyped('N',0);screen.keyPressed(GLFW.GLFW_KEY_ESCAPE,0,0);b=call(screen,"bounds");
            check((boolean)call(b,"annotationVisible") && number(b,"bodyWidth")==fixed,"adding annotation creates side paper without reflow");
            groupLeft=number(b,"directoryX");groupRight=number(b,"annotationX")+number(b,"annotationWidth");
            check(Math.abs(groupLeft+groupRight-number(b,"left")-number(b,"right"))<=1,"all three modules form a centred group");
            int ax=number(b,"annotationResizeX"), ay=number(b,"bodyTop")+36;
            screen.mouseClicked(ax,ay,0);screen.mouseDragged(ax+10,ay,0,10,0);screen.mouseReleased(ax+10,ay,0);b=call(screen,"bounds");
            check(number(b,"annotationWidth")==130 && number(b,"directoryWidth")==116 && number(b,"bodyWidth")==fixed,
                    "annotation width changes independently of directory and body widths");
            check(number(b,"annotationX")+number(b,"annotationWidth")<=number(b,"right")-5,
                    "centred modules remain inside outer UI");
            screen.mouseClicked(number(b,"annotationToggleX")+3,number(b,"moduleToggleY")+3,0);b=call(screen,"bounds");
            screen.mouseClicked(number(b,"directoryToggleX")+3,number(b,"moduleToggleY")+3,0);
            b=call(screen,"bounds");
            check(!(boolean)call(b,"directoryVisible") && number(b,"bodyWidth")==fixed,"hiding sidebar does not reflow document");
            check(Math.abs(2*number(b,"bodyPaperX")+number(b,"bodyPaperWidth")-number(b,"left")-number(b,"right"))<=1,"body alone is centred");
            screen.onClose();fresh(content);b=call(screen,"bounds");
            check(!(boolean)field(screen,"directoryVisible") && (int)field(screen,"directoryModuleWidth")==116,
                    "Esc exit and reopen restore sidebar visibility and width from disk");
            check(!(boolean)field(screen,"annotationVisible") && (int)field(screen,"annotationModuleWidth")==130,
                    "annotation visibility and width are restored independently");
            check(editor.document().equals(before),"UI preferences never mutate document or hash");
            Object bar=call(screen,"bodyScrollbar",new Class<?>[]{b.getClass()},b);Object track=call(bar,"track");
            int sx=number(track,"x")+1, sy=number(bar,"thumbY")+2;
            screen.mouseClicked(sx,sy,0);screen.mouseDragged(sx,number(track,"y")+number(track,"height")+50,0,0,500);
            screen.mouseReleased(sx,number(track,"y")+number(track,"height")+50,0);
            check((int)field(screen,"bodyScroll")==number(bar,"maximum"),"scrollbar thumb drags exactly to page bottom");
            bar=call(screen,"bodyScrollbar",new Class<?>[]{b.getClass()},b);track=call(bar,"track");
            check(number(bar,"thumbY")+number(bar,"thumbHeight")==number(track,"y")+number(track,"height"),"thumb bottom indicates content bottom exactly");
            screen.mouseClicked(sx,number(bar,"thumbY")+2,0);screen.mouseDragged(sx,number(track,"y")-30,0,0,-500);screen.mouseReleased(sx,number(track,"y")-30,0);
            check((int)field(screen,"bodyScroll")==0,"scrollbar thumb drags exactly to page top");
            screen.setDocumentMode(DocumentMode.READ);b=call(screen,"bounds");bar=call(screen,"bodyScrollbar",new Class<?>[]{b.getClass()},b);track=call(bar,"track");
            screen.mouseClicked(number(track,"x")+1,number(track,"y")+number(track,"height")/2,0);screen.mouseReleased(0,0,0);
            check((int)field(screen,"bodyScroll")>0 && editor.document().equals(before),"reader scrollbar track jumps without changing content");
            int oldWidth=screen.width, oldHeight=screen.height;
            call(screen,"ensureLayout",new Class<?>[]{int.class},fixed);
            int oldLines=((List<?>)field(screen,"layoutLines")).size();
            screen.init(Minecraft.getInstance(),280,300);b=call(screen,"bounds");call(screen,"ensureLayout",new Class<?>[]{int.class},number(b,"bodyWidth"));
            check(number(b,"bodyWidth")==fixed && ((List<?>)field(screen,"layoutLines")).size()==oldLines,
                    "tiny window clips viewport without changing text wrapping");
            screen.init(Minecraft.getInstance(),oldWidth,oldHeight);screen.onClose();
            preferences.save(LocalLayoutStore.DEFAULT);fresh(List.of(paragraph("short")));b=call(screen,"bounds");
            check(call(screen,"bodyScrollbar",new Class<?>[]{b.getClass()},b)==null,"short page has no unnecessary scrollbar");
        } finally { screen.onClose();preferences.save(original); }
    }

    private static void prepareScrollVisual() throws Exception {
        List<Block> content=new ArrayList<>();
        content.add(new Heading(1,List.of(new TextRun("固定正文与页面滚动",TextStyle.DEFAULT))));
        for(int i=1;i<=36;i++)content.add(paragraph("第 "+i+" 行：正文纸宽固定，侧栏改变位置但不改变排版。"));
        fresh(content);dragText(1,0,3);Object b=call(screen,"bounds");
        screen.mouseClicked(number(b,"bodyX")+24,number(b,"bodyTop")+22,GLFW.GLFW_MOUSE_BUTTON_RIGHT);
        for(char c:"本机批注，退出后仍保留。".toCharArray())screen.charTyped(c,0);
        screen.keyPressed(GLFW.GLFW_KEY_ESCAPE,0,0);call(screen,"status",new Class<?>[]{String.class},"");
        screen.setDocumentMode(DocumentMode.READ);b=call(screen,"bounds");
        screen.mouseScrolled(number(b,"bodyX")+20,number(b,"bodyTop")+30,0,-8);
    }

    private static void prepareRichVisual() throws Exception {
        List<Block> blocks=new ArrayList<>();
        blocks.add(new Heading(1,List.of(new TextRun("像 Word 一样写，像 Wiki 一样读",TextStyle.DEFAULT.withBold(true)))));
        blocks.add(new Paragraph(List.of(new TextRun("文字格式：",TextStyle.DEFAULT),new TextRun("粗体 ",TextStyle.DEFAULT.withBold(true)),new TextRun("斜体 ",TextStyle.DEFAULT.withItalic(true)),new TextRun("下划线 ",TextStyle.DEFAULT.withUnderline(true)),new TextRun("删除线",TextStyle.DEFAULT.withStrikethrough(true)))));
        blocks.add(new Paragraph(List.of(new TextRun("公式示例：x",TextStyle.DEFAULT),new TextRun("2",TextStyle.DEFAULT.withScript(1)),new TextRun(" + H",TextStyle.DEFAULT),new TextRun("2",TextStyle.DEFAULT.withScript(-1)),new TextRun("O   minecraft:stone",TextStyle.DEFAULT.withCode(true)))));
        blocks.add(new RichTextBlock(RichTextBlock.Kind.INFO,RichTextBlock.Alignment.LEFT,0,false,List.of(new TextRun("提示：选中文字设置格式；段落工具改变整段排版。",TextStyle.DEFAULT))));
        blocks.add(new RichTextBlock(RichTextBlock.Kind.NUMBERED,RichTextBlock.Alignment.LEFT,0,false,List.of(new TextRun("先写正文，再插入图片与表格。",TextStyle.DEFAULT))));
        blocks.add(new RichTextBlock(RichTextBlock.Kind.NUMBERED,RichTextBlock.Alignment.LEFT,0,false,List.of(new TextRun("在文档选项卡导入或导出 Word 文件。",TextStyle.DEFAULT))));
        blocks.add(new RichTextBlock(RichTextBlock.Kind.TASK,RichTextBlock.Alignment.LEFT,0,true,List.of(new TextRun("检查文字、段落和文件互通",TextStyle.DEFAULT))));
        blocks.add(new RichTextBlock(RichTextBlock.Kind.QUOTE,RichTextBlock.Alignment.LEFT,0,false,List.of(new TextRun("引用：知识可以被记录，也可以被分享。",TextStyle.DEFAULT))));
        blocks.add(new RichTextBlock(RichTextBlock.Kind.STEP,RichTextBlock.Alignment.LEFT,0,false,List.of(new TextRun("准备材料\n在同一步内用 Enter 换行。",TextStyle.DEFAULT))));
        blocks.add(new RichTextBlock(RichTextBlock.Kind.CODE,RichTextBlock.Alignment.LEFT,0,false,List.of(new TextRun("/say Hello Minecraft\n/time query daytime",TextStyle.DEFAULT))));
        blocks.add(HorizontalRule.INSTANCE);
        blocks.add(new Paragraph(List.of(new TextRun("隐藏的答案（点击揭示）",TextStyle.DEFAULT.withHidden(true)),new TextRun("  点击执行指令",TextStyle.DEFAULT.withColor(0x426D9E),"command:say hello"))));
        fresh(blocks);clickText(1,0);
    }

    private static void prepareVisualDocument() throws Exception {
        Path fixture = OUTPUT.resolve("grid.png");
        try (NativeImage image = new NativeImage(512, 256, false)) {
            for (int y = 0; y < 256; y++) for (int x = 0; x < 512; x++)
                image.setPixelRGBA(x, y, x % 32 < 2 || y % 32 < 2 ? 0xff292929 : 0xffe6b76c);
            image.writeToFile(fixture);
        }
        var imported = new LocalImageAssetStore(com.writablebookwiki.local.StoragePaths.dataRoot(FMLPaths.CONFIGDIR.get())).importImage(fixture);
        List<Block> blocks = new ArrayList<>();
        blocks.add(new Heading(1, List.of(new TextRun("Word-style document", new TextStyle(true, false, false, 0x284660, 16)))));
        blocks.add(new Paragraph(List.of(new TextRun("Smaller text ", new TextStyle(false, false, false, 0, 8)),
                new TextRun("Large text ", new TextStyle(true, false, false, 0x7A3E9D, 18)),
                new TextRun("normal 11pt.", TextStyle.DEFAULT))));
        blocks.add(new ImageBlock(imported.assetHash(), "", ImageAlignment.LEFT, 170, ImageLayoutMode.WRAP_LEFT, 170, 128));
        blocks.add(paragraph("Text flows on both sides of a middle picture. 中文自动换行，图片旁边的文字继续显示。 ".repeat(6)));
        blocks.add(new TableBlock(List.of(new TableRow(List.of(new TableCell(List.of(new TextRun("Table with multiline\ncontent", TextStyle.DEFAULT))),
                new TableCell(List.of(new TextRun("Shared layout", TextStyle.DEFAULT))))))));
        editor = new WikiDocumentEditor(document(blocks));
        screen = new NativeWikiScreen(editor, SESSION, DocumentMode.EDIT, 0, () -> {});
        Minecraft.getInstance().setScreen(screen);
        dragText(0, 0, 4);
    }

    private static void clickText(int block, int column) throws Exception {
        double[] p = textPoint(block, column);
        screen.mouseClicked(p[0], p[1], 0);
        screen.mouseReleased(p[0], p[1], 0);
    }
    private static void dragText(int block, int start, int end) throws Exception {
        double[] a = textPoint(block, start), b = textPoint(block, end);
        screen.mouseClicked(a[0], a[1], 0);
        screen.mouseDragged(b[0], b[1], 0, b[0] - a[0], b[1] - a[1]);
        screen.mouseReleased(b[0], b[1], 0);
    }
    private static double[] textPoint(int block, int column) throws Exception {
        Object bounds = call(screen, "bounds");
        call(screen, "ensureLayout", new Class<?>[]{int.class}, number(bounds, "bodyWidth"));
        for (Object line : (List<?>) field(screen, "layoutLines")) {
            if (number(line, "blockIndex") != block || column < number(line, "startColumn") || column > number(line, "endColumn")) continue;
            double x = number(bounds, "bodyX") + number(line, "indent");
            for (Object glyph : (List<?>) call(line, "fragments")) {
                double gx = ((Number) call(glyph, "x")).doubleValue();
                if (column <= number(glyph, "startColumn")) { x = number(bounds, "bodyX") + gx; break; }
                x = number(bounds, "bodyX") + gx + ((Number) call(glyph, "advance")).doubleValue();
            }
            return new double[]{x + .05, number(bounds, "bodyTop") + number(line, "y") + 3 - (int) field(screen, "bodyScroll")};
        }
        throw new AssertionError("No visible caret line for " + block + ":" + column);
    }
    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target);
    }
    private static Object call(Object target, String name) throws Exception { return call(target, name, new Class<?>[0]); }
    private static Object call(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name, types); method.setAccessible(true); return method.invoke(target, args);
    }
    private static int number(Object target, String method) throws Exception { return ((Number) call(target, method)).intValue(); }
    private static void check(boolean result, String label) {
        if (!result) throw new AssertionError(label);
        checks.add(label);
    }
    private static void capture(String name) throws Exception {
        try (NativeImage image = Screenshot.takeScreenshot(Minecraft.getInstance().getMainRenderTarget())) {
            image.writeToFile(OUTPUT.resolve(name));
        }
    }
    private static Paragraph paragraph(String text) { return new Paragraph(List.of(new TextRun(text, TextStyle.DEFAULT))); }
    private static WikiDocument document(List<Block> blocks) {
        UUID author = new UUID(0, 1);
        return new WikiDocument(WikiDocument.CURRENT_FORMAT_VERSION, "Word editor regression", blocks,
                new DocumentMetadata(author, "smoke", Instant.EPOCH, author, "smoke", Instant.EPOCH));
    }
}
