package com.writablebookwiki.neoforge;

import com.writablebookwiki.core.document.Block;
import com.writablebookwiki.core.document.BlockFrame;
import com.writablebookwiki.core.document.RichTextBlock;
import com.writablebookwiki.core.document.TextFragment;
import com.writablebookwiki.core.document.DocumentLink;
import com.writablebookwiki.core.document.HorizontalRule;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.ClickEvent;
import java.util.HashSet;
import java.util.Set;
import com.writablebookwiki.core.document.DocumentPages;
import com.writablebookwiki.core.document.Heading;
import com.writablebookwiki.core.document.ImageAlignment;
import com.writablebookwiki.core.document.ImageBlock;
import com.writablebookwiki.core.document.ImageLayoutMode;
import com.writablebookwiki.core.document.ImageResizeGeometry;
import com.writablebookwiki.core.document.Paragraph;
import com.writablebookwiki.core.document.QuoteBlock;
import com.writablebookwiki.core.document.TableBlock;
import com.writablebookwiki.core.document.TableCell;
import com.writablebookwiki.core.document.TableRow;
import com.writablebookwiki.core.document.TextRun;
import com.writablebookwiki.core.document.TextFlowLayout;
import com.writablebookwiki.core.document.TextNavigation;
import com.writablebookwiki.core.document.PlainTextEditor;
import java.util.function.UnaryOperator;
import com.writablebookwiki.core.document.TextSelection;
import com.writablebookwiki.core.document.TextStyle;
import com.writablebookwiki.core.document.TextualBlock;
import com.writablebookwiki.core.document.WikiDocument;
import com.writablebookwiki.core.document.WikiDocumentEditor;
import com.writablebookwiki.core.document.WikiDocumentCodec;
import com.writablebookwiki.local.ImportedImageAsset;
import com.writablebookwiki.local.DocxIO;
import com.writablebookwiki.local.ClipboardImageImport;
import com.writablebookwiki.local.LocalLayoutStore;
import com.writablebookwiki.local.WindowsClipboardImageImport;
import com.writablebookwiki.local.LocalAnnotationStore;
import com.writablebookwiki.local.LocalAppearanceStore;
import com.writablebookwiki.local.LocalTextAnnotation;
import com.writablebookwiki.local.AnnotationEditHistory;
import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.IntConsumer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.neoforged.fml.loading.FMLPaths;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

/**
 * The in-game host for the native Wiki document canvas.
 *
 * <p>It is a normal Minecraft {@link Screen}; document rendering, input and
 * layout are entirely owned by this project. The screen shows authored pages (explicit
 * {@code PageBreak}s), while each page is an unlimited vertically-scrolling
 * fixed-width document column. No visual zoom is implemented here.</p>
 */
final class NativeWikiScreen extends Screen {
    /** A fixed, compact working canvas. Modules never resize this outer UI. */
    private static final int UI_CANVAS_WIDTH = 620;
    private static final int UI_CANVAS_HEIGHT = 380;
    /** Default width is a sheet of paper, not the remaining width of a grid. */
    private static final int BODY_COLUMN_WIDTH = 320;
    private static final int DIRECTORY_WIDTH = 96;
    private static final int ANNOTATION_WIDTH = 110;
    private static final int MODULE_MIN_WIDTH = 64;
    private static final int MODULE_MAX_WIDTH = 160;
    /** Tight hit areas for the unobtrusive edge arrows. */
    private static final int MODULE_TOGGLE_WIDTH = 7;
    private static final int MODULE_TOGGLE_HEIGHT = 12;
    private static final int TABLE_CELL_PADDING = 4;
    private static final int TABLE_ROW_HEIGHT = 20;
    private static final int OUTER_PADDING = 5;
    private static final int PAPER_INSET = 5;
    private static final int COLUMN_GAP = 10;
    private static final int READER_HEADER_HEIGHT = 22;
    // Header, tool row and an optional palette each receive their own vertical
    // band.  The body starts below these bands; it must never sit behind a
    // button even on a large in-game window.
    private static final int EDITOR_HEADER_HEIGHT = 48;
    private static final int EDITOR_MENU_HEADER_HEIGHT = 68;
    private static final int FOOTER_HEIGHT = 19;
    private static final int BODY_TOP_GAP = 3;
    private static final int BODY_BOTTOM_GAP = 3;
    private static final int IMAGE_CONTEXT_ROW_HEIGHT = 18;
    private static final int IMAGE_CONTEXT_WIDTH = 62;
    private static final int BODY_TEXT_COLOR = 0xFF302C25;
    private static final int LINK_COLOR = 0xFF245F9F;
    private static final int HEADING_COLOR = 0xFF463829;
    private static final int PAPER_COLOR = 0xFFFFF8E8;
    private static final int PANEL_BORDER_COLOR = BookEditorChrome.BORDER;
    private static final int DIRECTORY_BACKGROUND = BookEditorChrome.SIDE_PAPER;
    private static final int QUOTE_BACKGROUND = 0xFFE9EEF4;
    private static final int DISABLED_COLOR = 0xFF746955;
    private static final int BUTTON_COLOR = 0xFF416F64;
    private static final int BUTTON_HOVER_COLOR = 0xFF558878;
    // The browser-like blue overlay used by the first prototype made selected
    // text look as if its authored colour had changed. Selection is UI chrome,
    // not document formatting, so keep it a neutral warm tint instead.
    private static final int SELECTION_COLOR = 0x80D8BF83;
    private static final int ANNOTATION_HIGHLIGHT_COLOR = 0x40D67B73;
    private static final int ACTIVE_ANNOTATION_HIGHLIGHT_COLOR = 0x70D65D57;
    private static final int ANNOTATION_CONNECTOR_COLOR = 0xFFB84D48;
    private static final int CARET_COLOR = 0xFF17130F;
    private static final int[] FONT_SIZE_PRESETS = {8, 9, 10, 11, 12, 14, 16, 18};
    private static final int[] COLOR_PRESETS = {
            0x000000, 0x333333, 0x555555, 0x777777, 0xAAAAAA, 0xBBBBBB,
            0xDDDDDD, 0xEEEEEE, 0xF5F5F5, 0xFFFFFF, 0xFFF8E8, 0xF1E4C4,
            0xAA0000, 0xFF5555, 0xFFAA00, 0xFFFF55, 0x00AA00, 0x55FF55,
            0x00AAAA, 0x55FFFF, 0x0000AA, 0x5555FF, 0xAA00AA, 0xFF55FF,
            0x8B1E1E, 0xC0392B, 0xB56300, 0xB7950B, 0x3B6E22, 0x1B5E40,
            0x146B70, 0x1F5E99, 0x283593, 0x663B8D, 0xAD427B, 0x6A4F31,
            0xF5B7B1, 0xF8C9A3, 0xF9E79F, 0xEAF2B5, 0xC5E1A5, 0xA9DFBF,
            0xA2D9CE, 0xAED6F1, 0xC5CAE9, 0xD7BDE2, 0xF5C6DE, 0xD7C4A3,
            0xFFF0F0, 0xFFF1E6, 0xFFF9D9, 0xF4F7DF, 0xEDF5E8, 0xE6F3EB,
            0xE7F4F2, 0xEAF2F8, 0xEEEFF8, 0xF3EBF7, 0xF9EDF4, 0x6D6D6D
    };
    /** The old left/right values remain readable, but new documents expose one movable wrap mode. */
    private static final ImageLayoutMode[] IMAGE_LAYOUT_CHOICES = {
            ImageLayoutMode.INLINE, ImageLayoutMode.WRAP_LEFT, ImageLayoutMode.COVER, ImageLayoutMode.FLOATING
    };

    private final WikiDocumentEditor editor;
    // Autoformat changes paragraph indices; retain its two caret positions only as
    // long as their document versions remain in the shared undo/redo history.
    private final java.util.Map<WikiDocument, Caret> automaticListCarets = new java.util.IdentityHashMap<>();
    private final DocumentSession session;
    private final IntConsumer pageChanged;
    private final Runnable onClosed;
    private final LocalAnnotationStore annotationStore;
    private DocumentMode mode;
    private int pageIndex;
    private int bodyScroll;
    private int directoryScroll;
    private int annotationScroll;
    private boolean closeNotified;
    private boolean showingChildReport;
    private boolean saveInProgress;
    private boolean closeAfterSave;
    private String savedContentHash;
    private final Set<Integer> collapsedBlocks = new HashSet<>();
    private int caretBlock = -1;
    private int caretColumn;
    private int selectionAnchorBlock = -1;
    private int selectionAnchorColumn;
    private boolean selectingWithMouse;
    private TextStyle typingStyle;
    private Float preferredCaretX;
    private int tableSelectionAnchor;
    private boolean selectingTableText;
    private boolean editingTitle;
    private int titleCaret;
    private int titleSelectionAnchor;
    /** A child footer control, so Escape dismisses it before closing the book. */
    private boolean pagePickerOpen;
    private String pagePickerInput = "";
    private ToolbarMenu toolbarMenu = ToolbarMenu.NONE;
    private int ribbonTab;
    private EditBox linkInput;
    private EditBox colorInput;
    private final LocalAppearanceStore appearanceStore;
    private final LocalLayoutStore layoutStore;
    private LocalLayoutStore.Settings savedLayoutSettings;
    private int paperColorRgb = LocalAppearanceStore.DEFAULT_PAPER_RGB;
    private String statusText = "";
    private long statusUntil;
    private final Set<String> revealedSpoilers = new HashSet<>();
    /** A lock action is deliberately two-step, just like a physical seal. */
    private boolean lockConfirmationOpen;
    private boolean requestedLockState;
    private String fontSizeInput = "";
    private String tableRowsInput = "2";
    private String tableColumnsInput = "2";
    private TableSizeInput tableSizeInput = TableSizeInput.ROWS;
    private boolean replaceTableSizeInputOnType;
    private int selectedImageBlock = -1;
    private ImageResize imageResize;
    private ImageMove imageMove;
    private ImageContextMenu imageContextMenu;
    private TableContextMenu tableContextMenu;
    /** A table cell has its own text caret; tables never masquerade as paragraphs. */
    private TableCellCaret tableCellCaret;
    private boolean directoryVisible = true;
    private boolean annotationVisible = true;
    private int directoryModuleWidth = DIRECTORY_WIDTH;
    private int annotationModuleWidth = ANNOTATION_WIDTH;
    private ModuleDividerDrag moduleDividerDrag = ModuleDividerDrag.NONE;
    private int moduleDragStartX;
    private int moduleDragStartWidth;
    private int bodyScrollDragOffset = -1;
    /** Local-only comments always point at a selected document range. */
    private List<LocalTextAnnotation> annotations = List.of();
    private final AnnotationEditHistory annotationHistory = new AnnotationEditHistory();
    private WikiDocument annotationDocument;
    private WikiDocument annotationKeyDocument;
    private String annotationKeyNamespace;
    private String cachedAnnotationKey;
    private boolean deselectImageOnClick;
    private boolean commentComposerOpen;
    private PendingAnnotation pendingAnnotation;
    private final PlainTextEditor commentEditor = new PlainTextEditor();
    private boolean selectingCommentText;
    private int selectedAnnotationIndex = -1;
    /** -1 is a newly created local comment; otherwise this is an in-place edit. */
    private int editingAnnotationIndex = -1;

    private WikiDocument laidOutDocument;
    private int laidOutPage = -1;
    private int laidOutWidth = -1;
    /** A late image transfer changes real image dimensions and must reflow the page. */
    private long laidOutImageTextureGeneration = -1L;
    private List<LayoutLine> layoutLines = List.of();
    private List<ImageLayout> imageLayouts = List.of();
    private List<TableLayout> tableLayouts = List.of();
    private List<FrameLayout> frameLayouts = List.of();
    private int contentHeight;

    /**
     * Creates a native game screen with callbacks for the owning window. The
     * callbacks make host replacement unnecessary for page changes: changing
     * a page redraws this exact screen and therefore does not flash or move
     * the player's pointer to the centre.
     */
    NativeWikiScreen(WikiDocumentEditor editor, DocumentSession session, DocumentMode mode, int initialPage,
            IntConsumer pageChanged, Runnable onClosed) {
        super(Component.literal("Writable Book Wiki"));
        this.editor = Objects.requireNonNull(editor, "editor");
        this.savedContentHash = new WikiDocumentCodec().contentHash(editor.document());
        this.session = Objects.requireNonNull(session, "session");
        this.mode = Objects.requireNonNull(mode, "mode");
        this.pageChanged = Objects.requireNonNull(pageChanged, "pageChanged");
        this.onClosed = Objects.requireNonNull(onClosed, "onClosed");
        this.pageIndex = clampPage(initialPage);
        this.annotationStore = new LocalAnnotationStore(com.writablebookwiki.local.StoragePaths.dataRoot(FMLPaths.CONFIGDIR.get()).resolve("annotations"));
        this.appearanceStore = new LocalAppearanceStore(com.writablebookwiki.local.StoragePaths.dataRoot(FMLPaths.CONFIGDIR.get()).resolve("appearance.properties"));
        try { this.paperColorRgb = appearanceStore.loadPaperColor(); }
        catch (IOException ignored) { /* A local preference must never stop a book opening. */ }
        this.layoutStore = new LocalLayoutStore(com.writablebookwiki.local.StoragePaths.dataRoot(FMLPaths.CONFIGDIR.get()).resolve("layout.properties"));
        try { savedLayoutSettings = layoutStore.load(); }
        catch (IOException ignored) { savedLayoutSettings = LocalLayoutStore.DEFAULT; }
        directoryVisible = savedLayoutSettings.directoryVisible();
        annotationVisible = savedLayoutSettings.annotationVisible();
        directoryModuleWidth = savedLayoutSettings.directoryWidth();
        annotationModuleWidth = savedLayoutSettings.annotationWidth();
        this.annotations = loadAnnotations();
        this.annotationDocument = editor.document();
    }

    /** Convenience constructor for a reader that does not need host notifications. */
    NativeWikiScreen(WikiDocumentEditor editor, DocumentSession session, DocumentMode mode, int initialPage,
            Runnable onClosed) {
        this(editor, session, mode, initialPage, ignored -> { }, onClosed);
    }

    int pageIndex() {
        return pageIndex;
    }

    void showPage(int requestedPage) {
        int nextPage = clampPage(requestedPage);
        if (nextPage == pageIndex) {
            return;
        }
        editor.finishTextEdit();
        clearSelection();
        pageIndex = nextPage;
        bodyScroll = 0;
        invalidateLayout();
        pageChanged.accept(pageIndex);
    }

    void setDocumentMode(DocumentMode mode) {
        revealedSpoilers.clear();
        this.mode = Objects.requireNonNull(mode, "mode");
        if (!mode.isEditable()) closeToolbarMenu();
    }

    void refreshDocument() {
        collapsedBlocks.clear();
        revealedSpoilers.clear();
        tableContextMenu = null;
        rebaseAnnotations();
        pageIndex = clampPage(pageIndex);
        bodyScroll = Math.max(0, bodyScroll);
        invalidateLayout();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Keep the world visible around the book instead of presenting a desktop-sized backdrop.
        graphics.fill(0, 0, width, height, 0x50100E0B);

        Bounds bounds = bounds();
        ensureLayout(bounds.bodyWidth());
        bodyScroll = clamp(bodyScroll, 0, maxBodyScroll(bounds));
        drawPanel(graphics, bounds);
        drawHeader(graphics, bounds, mouseX, mouseY);
        drawDirectory(graphics, bounds, mouseX, mouseY);
        drawDocument(graphics, bounds);
        drawAnnotationConnectors(graphics, bounds);
        drawAnnotation(graphics, bounds, mouseX, mouseY);
        drawBodyScrollbar(graphics, bounds, mouseX, mouseY);
        drawFooter(graphics, bounds, mouseX, mouseY);
        drawModuleToggles(graphics, bounds, mouseX, mouseY);
        drawPagePicker(graphics, bounds, mouseX, mouseY);
        drawImageContextMenu(graphics, bounds, mouseX, mouseY);
        drawTableContextMenu(graphics, bounds, mouseX, mouseY);
        if (isColorMenu()) drawColorPalette(graphics, bounds, mouseX, mouseY);
        drawRibbonTooltip(graphics, bounds, mouseX, mouseY);
        drawContentTooltip(graphics, bounds, mouseX, mouseY);
        if (System.currentTimeMillis() < statusUntil && !statusText.isBlank()) {
            String message = truncateToWidth(statusText, bounds.right()-bounds.left()-24);
            int x = bounds.left()+12, y = bounds.footerY()-16;
            graphics.fill(x-4,y-3,x+font.width(message)+4,y+12,0xED263D53);
            graphics.drawString(font,message,x,y,0xFFFFFFFF,false);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (closeAfterSave) return true;
        if (isColorMenu()) {
            if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) return handleColorPaletteClick(bounds(), mouseX, mouseY);
            if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) closeToolbarMenu();
            return true;
        }
        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            tableContextMenu = null;
            imageContextMenu = null;
            Bounds bounds = bounds();
            if (mode.isEditable()) {
                ImageLayout image = imageAt(bounds, mouseX, mouseY, true);
                if (image != null) {
                    clearSelection();
                    selectedImageBlock = image.blockIndex();
                    imageContextMenu = new ImageContextMenu(image.blockIndex(), (int) mouseX, (int) mouseY);
                    return true;
                }
                if (mouseY >= bounds.bodyTop() && mouseY < bounds.footerY()) {
                    TableCellHit table = tableCellAt(bounds, mouseX, mouseY);
                    if (table != null) {
                        closeCommentComposer();
                        finishTitleEditing();
                        closeToolbarMenu();
                        editor.finishTextEdit();
                        clearSelection();
                        tableContextMenu = new TableContextMenu(table.blockIndex(), table.row(), table.column(),
                                (int) mouseX, (int) mouseY);
                        return true;
                    }
                }
            }
            if (hasSelection()) {
                beginCommentComposer();
                return true;
            }
        }
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            return super.mouseClicked(mouseX, mouseY, button);
        }
        Bounds bounds = bounds();
        // A right-hand note is an independent local text area. Clicking any
        // other part of the canvas returns keyboard focus to that part (most
        // importantly, a paragraph) instead of leaving the note editor to
        // consume the next character.
        if (commentComposerOpen && !inAnnotation(bounds, mouseX, mouseY)) {
            closeCommentComposer();
        }
        if (lockConfirmationOpen) {
            return handleLockConfirmationClick(bounds, mouseX, mouseY);
        }
        if (imageContextMenu != null) {
            if (handleImageContextMenuClick(bounds, mouseX, mouseY)) {
                return true;
            }
            imageContextMenu = null;
        }
        if (tableContextMenu != null) {
            if (handleTableContextMenuClick(bounds, mouseX, mouseY)) {
                return true;
            }
            tableContextMenu = null;
        }
        if (editingTitle && !inRect(mouseX, mouseY, bounds.bodyX(), bounds.headerY(), bounds.bodyWidth(), 20)) {
            finishTitleEditing();
        }
        if (pagePickerOpen) {
            if (handlePagePickerClick(bounds, mouseX, mouseY)) {
                return true;
            }
            pagePickerOpen = false;
        }
        if (inRect(mouseX, mouseY, bounds.directoryToggleX(), bounds.moduleToggleY(),
                MODULE_TOGGLE_WIDTH, MODULE_TOGGLE_HEIGHT)) {
            directoryVisible = !directoryVisible;
            directoryScroll = 0;
            invalidateLayout();
            saveLayoutSettings();
            return true;
        }
        if (hasAnnotationModule() && inRect(mouseX, mouseY, bounds.annotationToggleX(), bounds.moduleToggleY(),
                MODULE_TOGGLE_WIDTH, MODULE_TOGGLE_HEIGHT)) {
            if (!annotationVisible && !canShowAnnotationPaper()) {
                status("请先缩小或隐藏目录，为备注留出空间；正文纸宽固定");
                return true;
            }
            annotationVisible = !annotationVisible;
            annotationScroll = 0;
            invalidateLayout();
            saveLayoutSettings();
            return true;
        }
        if (bounds.directoryVisible() && inRect(mouseX, mouseY, bounds.directoryResizeX() - 3, bounds.bodyTop(),
                6, bounds.footerY() - bounds.bodyTop())) {
            moduleDividerDrag = ModuleDividerDrag.DIRECTORY;
            moduleDragStartX = (int) mouseX;
            moduleDragStartWidth = directoryModuleWidth;
            return true;
        }
        if (bounds.annotationVisible() && inRect(mouseX, mouseY, bounds.annotationResizeX() - 3, bounds.bodyTop(),
                6, bounds.footerY() - bounds.bodyTop())) {
            moduleDividerDrag = ModuleDividerDrag.ANNOTATION;
            moduleDragStartX = (int) mouseX;
            moduleDragStartWidth = annotationModuleWidth;
            return true;
        }
        if (inRect(mouseX, mouseY, bounds.previousX(), bounds.footerY(), 50, 20)) {
            showPage(pageIndex - 1);
            return true;
        }
        if (inRect(mouseX, mouseY, bounds.nextX(), bounds.footerY(), 50, 20)) {
            nextPage();
            return true;
        }
        if (inRect(mouseX, mouseY, bounds.pageNumberX(), bounds.footerY(), bounds.pageNumberWidth(), 20)) {
            pagePickerOpen = true;
            pagePickerInput = Integer.toString(pageIndex + 1);
            return true;
        }
        if (inRect(mouseX, mouseY, bounds.modeX(), bounds.headerY(), 46, 20)) {
            requestModeToggle();
            return true;
        }
        if (session.canChangeLock() && inRect(mouseX, mouseY, bounds.lockButtonX(), bounds.headerY(),
                34, 20)) {
            return openLockConfirmation();
        }
        if (mode.isEditable() && inRect(mouseX, mouseY, bounds.bodyX(), bounds.headerY(), bounds.bodyWidth(), 20)) {
            beginTitleEditing(bounds, mouseX);
            return true;
        }
        if (mode.isEditable() && handleRibbonTabClick(bounds, mouseX, mouseY)) return true;
        if (mode.isEditable() && toolbarMenu != ToolbarMenu.NONE) {
            // A palette is a child UI. A click cannot fall through it into the
            // document, otherwise choosing a size/colour would move the
            // caret and silently lose the selected text.
            return handleToolbarMenuClick(bounds, mouseX, mouseY);
        }
        if (mode.isEditable() && handleToolbarClick(bounds, mouseX, mouseY)) {
            return true;
        }
        if (handleBodyScrollbarClick(bounds, mouseX, mouseY)) return true;
        if (handleBlockFoldClick(bounds, mouseX, mouseY)) return true;
        if (bounds.directoryVisible()
                && mouseX >= bounds.directoryX() && mouseX < bounds.directoryX() + bounds.directoryWidth()
                && mouseY >= bounds.bodyTop() && mouseY < bounds.footerY()) {
            int row = (int) ((mouseY - bounds.bodyTop() + directoryScroll) / directoryLineHeight());
            List<NativeDocumentPresentation.TableOfContentsEntry> directory = directory();
            if (row >= 0 && row < directory.size()) {
                showDirectoryEntry(directory.get(row));
            }
            return true;
        }
        if (inAnnotation(bounds, mouseX, mouseY)) {
            selectingCommentText = false;
            beginAnnotationInteraction(bounds, mouseX, mouseY);
            selectingCommentText = commentComposerOpen;
            return true;
        }
        if (mode.isEditable()) {
            ImageLayout image = imageAt(bounds, mouseX, mouseY);
            if (image != null) {
                boolean alreadySelected = selectedImageBlock == image.blockIndex();
                ResizeHandle handle = alreadySelected ? resizeHandleAt(bounds, image, mouseX, mouseY) : null;
                clearSelection();
                selectedImageBlock = image.blockIndex();
                editor.finishTextEdit();
                if (handle != null) {
                    imageResize = new ImageResize(image.blockIndex(), handle, (int) mouseX, (int) mouseY,
                            image.width(), image.height(), image.xOffset(), image.y(), image.width());
                } else if (image.layoutMode() != ImageLayoutMode.INLINE) {
                    ImageVisual visual = imageVisual(image);
                    imageMove = new ImageMove(image.blockIndex(), (int) mouseX, (int) mouseY,
                            visual.xOffset(), visual.y());
                    deselectImageOnClick = alreadySelected;
                } else if (alreadySelected) {
                    selectedImageBlock = -1;
                }
                return true;
            }
        }
        if (mode.isEditable()) {
            TableCellHit tableCell = tableCellAt(bounds, mouseX, mouseY);
            if (tableCell != null) {
                editor.finishTextEdit();
                typingStyle = null;
                boolean sameCell = tableCellCaret != null && tableCellCaret.blockIndex() == tableCell.blockIndex()
                        && tableCellCaret.row() == tableCell.row() && tableCellCaret.tableColumn() == tableCell.column();
                if (!hasShiftDown() || !sameCell) tableSelectionAnchor = tableCell.caretColumn();
                caretBlock = selectionAnchorBlock = -1;
                selectedImageBlock = -1;
                tableCellCaret = new TableCellCaret(tableCell.blockIndex(), tableCell.row(), tableCell.column(),
                        tableCell.caretColumn());
                selectingTableText = true;
                return true;
            }
        }
        if (mouseX >= bounds.bodyX() && mouseX < bounds.bodyX() + bounds.bodyWidth()
                && mouseY >= bounds.bodyTop() && mouseY < bounds.footerY()) {
            if (handleRichContentClick(bounds, mouseX, mouseY)) return true;
            Caret hit = caretAt(bounds, mouseX, mouseY);
            if (hit != null) {
                if (collapsedBlocks.contains(hit.blockIndex())) return true;
                placeCaret(hit, hasShiftDown());
                selectingWithMouse = true;
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (closeAfterSave) return true;
        if (bodyScrollDragOffset >= 0 && button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            updateBodyScrollbar(bounds(), mouseY);
            return true;
        }
        if (isColorMenu()) {
            if (colorInput != null) colorInput.mouseDragged(mouseX, mouseY, button, dragX, dragY);
            return true;
        }
        if (selectingCommentText && commentComposerOpen && button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            beginAnnotationInteraction(bounds(), mouseX, mouseY);
            return true;
        }
        if (selectingTableText && tableCellCaret != null && button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            TableCellHit hit = tableCellAt(bounds(), mouseX, mouseY);
            if (hit != null && hit.blockIndex() == tableCellCaret.blockIndex()
                    && hit.row() == tableCellCaret.row() && hit.column() == tableCellCaret.tableColumn())
                tableCellCaret = tableCellCaret.withColumn(hit.caretColumn());
            return true;
        }
        if (moduleDividerDrag != ModuleDividerDrag.NONE && button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            updateModuleDivider((int) mouseX);
            return true;
        }
        if (mode.isEditable() && imageResize != null && button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            updateImageResize((int) mouseX, (int) mouseY);
            return true;
        }
        if (mode.isEditable() && imageMove != null && button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            imageMove = imageMove.withCurrent((int) mouseX, (int) mouseY);
            invalidateLayout();
            return true;
        }
        if (selectingWithMouse && button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            Caret hit = caretAt(bounds(), mouseX, mouseY);
            if (hit != null) {
                placeCaret(hit, true);
            }
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && bodyScrollDragOffset >= 0) {
            bodyScrollDragOffset = -1;
            return true;
        }
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            selectingTableText = false;
            selectingCommentText = false;
        }
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && moduleDividerDrag != ModuleDividerDrag.NONE) {
            moduleDividerDrag = ModuleDividerDrag.NONE;
            saveLayoutSettings();
            return true;
        }
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && imageResize != null) {
            finishImageResize();
            return true;
        }
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && imageMove != null) {
            finishImageMove((int) mouseX, (int) mouseY);
            return true;
        }
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && selectingWithMouse) {
            selectingWithMouse = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (isColorMenu()) return true;
        tableContextMenu = null;
        Bounds bounds = bounds();
        int delta = (int) Math.round(verticalAmount * 18.0);
        if (delta == 0 && verticalAmount != 0) {
            delta = verticalAmount > 0 ? 1 : -1;
        }
        if (bounds.directoryVisible()
                && mouseX >= bounds.directoryX() && mouseX < bounds.directoryX() + bounds.directoryWidth()
                && mouseY >= bounds.bodyTop() && mouseY < bounds.footerY()) {
            directoryScroll = clamp(directoryScroll - delta, 0, maxDirectoryScroll(bounds));
        } else if (inAnnotation(bounds, mouseX, mouseY)) {
            List<AnnotationCardLayout> cards = annotationCardLayouts(Math.max(20, bounds.annotationWidth() - 12));
            int annotationHeight = cards.isEmpty() ? 0
                    : cards.getLast().endRow() * directoryLineHeight();
            int availableHeight = Math.max(1, bounds.footerY() - 4
                    - (bounds.bodyTop() + 5 + directoryLineHeight() + 3));
            annotationScroll = clamp(annotationScroll - delta, 0, Math.max(0, annotationHeight - availableHeight));
        } else if (mouseX >= bounds.bodyX() && mouseX < bounds.bodyX() + bounds.bodyVisibleWidth() + PAPER_INSET
                && mouseY >= bounds.bodyTop() && mouseY < bounds.footerY()) {
            ensureLayout(bounds.bodyWidth());
            bodyScroll = clamp(bodyScroll - delta, 0, maxBodyScroll(bounds));
        } else {
            return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
        }
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (closeAfterSave) return true;
        if (tableContextMenu != null) {
            tableContextMenu = null;
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) return true;
        }
        if (commentComposerOpen && handleCommentComposerKey(keyCode, modifiers)) {
            return true;
        }
        if (lockConfirmationOpen) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                lockConfirmationOpen = false;
                return true;
            }
            return true;
        }
        if (imageContextMenu != null && keyCode == GLFW.GLFW_KEY_ESCAPE) {
            imageContextMenu = null;
            return true;
        }
        if (mode.isEditable() && selectedImageBlock >= 0 && keyCode == GLFW.GLFW_KEY_ESCAPE) {
            clearSelection();
            return true;
        }
        if (mode.isEditable() && selectedImageBlock >= 0
                && (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER)) {
            return insertTextAdjacentToSelectedImage((modifiers & GLFW.GLFW_MOD_SHIFT) != 0);
        }
        if (pagePickerOpen && handlePagePickerKey(keyCode)) {
            return true;
        }
        if (mode.isEditable() && editingTitle && handleTitleKey(keyCode, modifiers)) {
            return true;
        }
        if (mode.isEditable() && toolbarMenu != ToolbarMenu.NONE
                && handleToolbarMenuKey(keyCode, modifiers)) {
            return true;
        }
        if (mode.isEditable() && tableCellCaret != null && handleTableCellKey(keyCode, modifiers)) {
            return true;
        }
        if (mode.isEditable() && selectedImageBlock >= 0
                && (keyCode == GLFW.GLFW_KEY_DELETE || keyCode == GLFW.GLFW_KEY_BACKSPACE)) {
            int deletingBlock = selectedImageBlock;
            if (editor.deleteImageBlock(deletingBlock)) {
                clearSelection();
                refreshAfterEdit();
                status("已删除图片");
            } else {
                // Never consume the key silently: an invalid visual address
                // is a UI defect, not an instruction to delete nearby text.
                selectedImageBlock = -1;
                status("图片选择已失效，请重新点击图片后删除");
            }
            return true;
        }
        if (mode.isEditable() && toolbarMenu != ToolbarMenu.NONE
                && handleToolbarMenuKey(keyCode, modifiers)) {
            return true;
        }
        if (mode.isEditable() && handleEditKey(keyCode, modifiers)) {
            return true;
        }
        if (!mode.isEditable() && (modifiers & GLFW.GLFW_MOD_CONTROL) != 0) {
            if (keyCode == GLFW.GLFW_KEY_C) return copySelection();
            if (keyCode == GLFW.GLFW_KEY_A) return selectCurrentPageText();
        }
        if (keyCode == GLFW.GLFW_KEY_LEFT || keyCode == GLFW.GLFW_KEY_PAGE_UP) {
            showPage(pageIndex - 1);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_RIGHT || keyCode == GLFW.GLFW_KEY_PAGE_DOWN) {
            nextPage();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_HOME) {
            bodyScroll = 0;
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_END) {
            Bounds bounds = bounds();
            ensureLayout(bounds.bodyWidth());
            bodyScroll = maxBodyScroll(bounds);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            if (pagePickerOpen) {
                pagePickerOpen = false;
                return true;
            }
            onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (closeAfterSave) return true;
        if (tableContextMenu != null) return true;
        if (commentComposerOpen) {
            if ((modifiers & GLFW.GLFW_MOD_CONTROL) == 0 && !Character.isISOControl(codePoint)) {
                replaceCommentComposerAtCaret(String.valueOf(codePoint));
            }
            return true;
        }
        if (pagePickerOpen) {
            if (Character.isDigit(codePoint) && pagePickerInput.length() < 6) {
                pagePickerInput += codePoint;
            }
            return true;
        }
        if (mode.isEditable() && editingTitle) {
            if ((modifiers & GLFW.GLFW_MOD_CONTROL) == 0 && !Character.isISOControl(codePoint)) {
                replaceTitleSelection(String.valueOf(codePoint));
            }
            return true;
        }
        if (mode.isEditable() && isLinkMenu()) {
            if (linkInput != null) linkInput.charTyped(codePoint, modifiers);
            return true;
        }
        if (mode.isEditable() && isColorMenu()) {
            if (colorInput != null) colorInput.charTyped(codePoint, modifiers);
            return true;
        }
        if (mode.isEditable() && toolbarMenu == ToolbarMenu.FONT_SIZE) {
            if (Character.isDigit(codePoint) && fontSizeInput.length() < 2) {
                fontSizeInput += codePoint;
            }
            return true;
        }
        if (mode.isEditable() && toolbarMenu == ToolbarMenu.TABLE) {
            if (Character.isDigit(codePoint)) {
                if (replaceTableSizeInputOnType) {
                    if (tableSizeInput == TableSizeInput.ROWS) {
                        tableRowsInput = "";
                    } else {
                        tableColumnsInput = "";
                    }
                    replaceTableSizeInputOnType = false;
                }
                if (tableSizeInput == TableSizeInput.ROWS && tableRowsInput.length() < 2) {
                    tableRowsInput += codePoint;
                } else if (tableSizeInput == TableSizeInput.COLUMNS && tableColumnsInput.length() < 2) {
                    tableColumnsInput += codePoint;
                }
            }
            return true;
        }
        if (toolbarMenu != ToolbarMenu.NONE) {
            return true;
        }
        if (!mode.isEditable() || (modifiers & GLFW.GLFW_MOD_CONTROL) != 0 || Character.isISOControl(codePoint)) {
            return super.charTyped(codePoint, modifiers);
        }
        if (tableCellCaret != null) {
            return replaceTableCellAtCaret(String.valueOf(codePoint));
        }
        boolean tryListPrefix = codePoint == ' ' && hasEditableCaret() && !hasTextSelection();
        boolean handled = replaceCurrentSelection(String.valueOf(codePoint));
        if (tryListPrefix) {
            WikiDocument typed = editor.document();
            Caret literalCaret = new Caret(caretBlock, caretColumn);
            WikiDocumentEditor.PasteResult converted = editor.convertTypedListPrefix(currentSelection());
            if (converted != null) {
                TextStyle style = typingStyle;
                automaticListCarets.put(typed, literalCaret);
                automaticListCarets.put(editor.document(), new Caret(converted.blockIndex(), converted.column()));
                refreshAfterEdit();
                typingStyle = style;
            }
        }
        return handled;
    }

    @Override
    public void removed() {
        closeCommentComposer();
        saveLayoutSettings();
        super.removed();
        if (!showingChildReport && !closeNotified) {
            closeNotified = true;
            onClosed.run();
        }
    }

    /** Escape from the root saves; Escape from a child menu never reaches this method. */
    @Override
    public void onClose() {
        if (closeAfterSave) return;
        saveLayoutSettings();
        finishTitleEditing();
        closeCommentComposer();
        editor.finishTextEdit();
        if (hasUnsavedContent() || saveInProgress) {
            if (!session.canEdit()) { status("没有保存权限，编辑内容仍保留"); return; }
            closeAfterSave = true;
            if (!saveInProgress) saveCurrentDocument();
            return;
        }
        super.onClose();
    }

    void resumeFromReport() { showingChildReport = false; }

    private void saveLayoutSettings() {
        LocalLayoutStore.Settings current = new LocalLayoutStore.Settings(directoryVisible, annotationVisible,
                directoryModuleWidth, annotationModuleWidth);
        if (current.equals(savedLayoutSettings)) return;
        try { layoutStore.save(current); savedLayoutSettings = current; }
        catch (IOException failure) {
            com.mojang.logging.LogUtils.getLogger().warn("Could not save local book UI settings", failure);
            status("本机界面设置暂未保存，正文不受影响");
        }
    }

    private boolean hasUnsavedContent() {
        return !savedContentHash.equals(new WikiDocumentCodec().contentHash(editor.document()));
    }

    private void requestModeToggle() {
        if (mode == DocumentMode.READ && !session.canEdit()) {
            status("此书当前没有编辑权限");
            return;
        }
        finishTitleEditing();
        editor.finishTextEdit();
        revealedSpoilers.clear();
        mode = mode == DocumentMode.READ ? DocumentMode.EDIT : DocumentMode.READ;
        closeToolbarMenu();
        if (mode == DocumentMode.READ) {
            clearSelection();
        } else {
            status("编辑模式：点击一段文字后即可输入或框选");
        }
    }

    private boolean openLockConfirmation() {
        if (!session.canChangeLock()) {
            status("只有创造模式可以锁定或解除锁定");
            return true;
        }
        finishTitleEditing();
        editor.finishTextEdit();
        closeToolbarMenu();
        requestedLockState = !session.isLocked();
        lockConfirmationOpen = true;
        status(requestedLockState ? "再次确认后只锁定当前这一副本" : "再次确认后只解除当前这一副本的锁定");
        return true;
    }

    private boolean handleLockConfirmationClick(Bounds bounds, double mouseX, double mouseY) {
        int x = bounds.lockConfirmationX();
        int y = bounds.lockConfirmationY(mode.isEditable());
        if (inRect(mouseX, mouseY, x + 48, y, 20, 20)) {
            boolean nextLocked = requestedLockState;
            lockConfirmationOpen = false;
            status(session.changeLock(nextLocked));
            return true;
        }
        if (inRect(mouseX, mouseY, x + 72, y, 20, 20)) {
            lockConfirmationOpen = false;
            status("已取消锁定操作");
            return true;
        }
        // A child popup is modal: a click elsewhere dismisses it and never
        // leaks into the page below as an accidental edit or caret move.
        lockConfirmationOpen = false;
        return true;
    }

    private boolean inAnnotation(Bounds bounds, double mouseX, double mouseY) {
        return bounds.annotationVisible()
                && mouseX >= bounds.annotationX() && mouseX < bounds.annotationX() + bounds.annotationWidth()
                && mouseY >= bounds.bodyTop() && mouseY < bounds.footerY();
    }

    /** Opens a local-only comment composer for the currently highlighted text. */
    private boolean beginCommentComposer() {
        NormalizedScreenSelection selected = normalizeScreenSelection(currentSelection());
        String quote = selectedText();
        if (selected == null || quote == null || quote.isBlank()) {
            status("请先框选需要注释的文字");
            return true;
        }
        if (!canShowAnnotationPaper()) {
            status("请先缩小或隐藏目录，为备注留出空间；正文纸宽固定");
            return true;
        }
        // The range is an invisible placement anchor, not text that becomes
        // part of the player's local note. Notes contain only what the player
        // writes in the right-hand area.
        pendingAnnotation = new PendingAnnotation(selected.startBlock(), selected.startColumn(),
                selected.endBlock(), selected.endColumn(), "");
        commentEditor.reset("");
        editingAnnotationIndex = -1;
        commentComposerOpen = true;
        annotationVisible = true;
        annotationScroll = 0;
        return true;
    }

    /** Reopens the clicked comment in place, retaining its original document anchor. */
    private void beginExistingCommentEditing(int annotationIndex) {
        if (annotationIndex < 0 || annotationIndex >= annotations.size()) {
            return;
        }
        LocalTextAnnotation annotation = annotations.get(annotationIndex);
        pendingAnnotation = new PendingAnnotation(annotation.startBlock(), annotation.startColumn(),
                annotation.endBlock(), annotation.endColumn(), "");
        commentEditor.reset(annotation.comment());
        editingAnnotationIndex = annotationIndex;
        selectedAnnotationIndex = annotationIndex;
        commentComposerOpen = true;
        annotationVisible = true;
        showAnnotation(annotation);
    }

    private void beginAnnotationInteraction(Bounds bounds, double mouseX, double mouseY) {
        int textTop = bounds.bodyTop() + 5 + directoryLineHeight() + 3;
        int textBottom = bounds.footerY() - 4;
        if (mouseY < textTop || mouseY >= textBottom) {
            return;
        }
        int row = (int) ((mouseY - textTop + annotationScroll) / directoryLineHeight());
        AnnotationCardLayout card = annotationCardAtRow(
                annotationCardLayouts(Math.max(20, bounds.annotationWidth() - 12)), row);
        if (card == null) {
            return;
        }
        if (!card.composer()) {
            beginExistingCommentEditing(card.annotationIndex());
            return;
        }
        if (row >= card.commentStartRow()
                && row < card.commentStartRow() + card.inputLines().size()) {
            commentEditor.moveTo(commentComposerCaretAt(card, mouseX, row, bounds.annotationX() + 6),
                    selectingCommentText || hasShiftDown());
        }
    }

    private int commentComposerCaretAt(AnnotationCardLayout card, double mouseX, int row, int x) {
        int visualLine = row - card.commentStartRow();
        List<CommentInputLine> lines = card.inputLines();
        if (visualLine < 0 || visualLine >= lines.size()) {
            return commentEditor.text().length();
        }
        CommentInputLine line = lines.get(visualLine);
        int cursor = line.start();
        for (int offset = 0; offset < line.text().length();) {
            int codePoint = line.text().codePointAt(offset);
            String character = new String(Character.toChars(codePoint));
            int characterWidth = font.width(character);
            if (mouseX < x + characterWidth / 2.0) {
                return line.start() + offset;
            }
            x += characterWidth;
            offset += Character.charCount(codePoint);
            cursor = line.start() + offset;
        }
        return cursor;
    }

    private boolean handleCommentComposerKey(int keyCode, int modifiers) {
        boolean control = (modifiers & GLFW.GLFW_MOD_CONTROL) != 0;
        boolean shift = (modifiers & GLFW.GLFW_MOD_SHIFT) != 0;
        if (keyCode == GLFW.GLFW_KEY_ESCAPE || control
                && (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER)) {
            closeCommentComposer();
            return true;
        }
        if (control) {
            if (keyCode == GLFW.GLFW_KEY_A) { commentEditor.selectAll(); return true; }
            if ((keyCode == GLFW.GLFW_KEY_C || keyCode == GLFW.GLFW_KEY_X) && minecraft != null) {
                if (commentEditor.hasSelection()) {
                    minecraft.keyboardHandler.setClipboard(commentEditor.selectedText());
                    if (keyCode == GLFW.GLFW_KEY_X) {
                        commentEditor.replaceSelection("");
                        persistCommentComposer();
                    }
                }
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_V && minecraft != null) {
                replaceCommentComposerAtCaret(sanitiseCommentInput(minecraft.keyboardHandler.getClipboard()));
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_Z || keyCode == GLFW.GLFW_KEY_Y) {
                if (keyCode == GLFW.GLFW_KEY_Y || shift) commentEditor.redo();
                else commentEditor.undo();
                persistCommentComposer();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_S) { persistCommentComposer(); return true; }
        }
        if (keyCode == GLFW.GLFW_KEY_BACKSPACE || keyCode == GLFW.GLFW_KEY_DELETE) {
            commentEditor.delete(keyCode == GLFW.GLFW_KEY_BACKSPACE ? -1 : 1, control);
            persistCommentComposer();
        } else if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            replaceCommentComposerAtCaret("\n");
        } else if (keyCode == GLFW.GLFW_KEY_LEFT || keyCode == GLFW.GLFW_KEY_RIGHT) {
            commentEditor.move(keyCode == GLFW.GLFW_KEY_LEFT ? -1 : 1, shift, control);
        } else if (keyCode == GLFW.GLFW_KEY_HOME || keyCode == GLFW.GLFW_KEY_END
                || keyCode == GLFW.GLFW_KEY_UP || keyCode == GLFW.GLFW_KEY_DOWN) {
            List<CommentInputLine> lines = commentInputLines(Math.max(20, bounds().annotationWidth() - 12));
            int current = 0;
            for (int i = 0; i < lines.size(); i++) if (lines.get(i).start() <= commentEditor.caret()) current = i;
            CommentInputLine line = lines.get(current);
            int target;
            if (keyCode == GLFW.GLFW_KEY_HOME) target = control ? 0 : line.start();
            else if (keyCode == GLFW.GLFW_KEY_END) target = control ? commentEditor.text().length() : line.end();
            else {
                int next = clamp(current + (keyCode == GLFW.GLFW_KEY_UP ? -1 : 1), 0, lines.size() - 1);
                target = Math.min(lines.get(next).end(), lines.get(next).start() + commentEditor.caret() - line.start());
            }
            commentEditor.moveTo(target, shift);
        }
        return true;
    }

    private void replaceCommentComposerAtCaret(String input) {
        String safe = sanitiseCommentInput(input);
        if (!safe.isEmpty() && commentEditor.replaceSelection(safe)) persistCommentComposer();
    }

    /** Closes the local note editor only; every actual edit is already persisted. */
    private void closeCommentComposer() {
        if (!commentComposerOpen) {
            return;
        }
        resetCommentComposer();
    }

    /** Writes the right-hand text area immediately, just like normal document input. */
    private void persistCommentComposer() {
        annotationHistory.commentsChanged();
        PendingAnnotation pending = pendingAnnotation;
        if (!commentComposerOpen || pending == null) {
            return;
        }
        List<LocalTextAnnotation> next = new ArrayList<>(annotations);
        if (commentEditor.text().isBlank()) {
            if (editingAnnotationIndex < 0 || editingAnnotationIndex >= next.size()) {
                return;
            }
            next.remove(editingAnnotationIndex);
            try {
                annotationStore.saveTextAnnotations(annotationStorageKey(), sortAnnotations(next));
                annotations = sortAnnotations(next);
                selectedAnnotationIndex = -1;
                editingAnnotationIndex = -1;
            } catch (IOException | IllegalArgumentException exception) {
                status("本地注释保存失败");
            }
            return;
        }
        LocalTextAnnotation saved = new LocalTextAnnotation(pending.startBlock(), pending.startColumn(), pending.endBlock(),
                pending.endColumn(), pending.quotedText(), commentEditor.text());
        if (editingAnnotationIndex >= 0 && editingAnnotationIndex < next.size()) {
            next.set(editingAnnotationIndex, saved);
        } else {
            next.add(saved);
        }
        List<LocalTextAnnotation> sorted = sortAnnotations(next);
        try {
            annotationStore.saveTextAnnotations(annotationStorageKey(), sorted);
            annotations = sorted;
            selectedAnnotationIndex = annotations.indexOf(saved);
            editingAnnotationIndex = selectedAnnotationIndex;
        } catch (IOException | IllegalArgumentException exception) {
            status("本地注释保存失败");
        }
    }

    private void resetCommentComposer() {
        commentComposerOpen = false;
        pendingAnnotation = null;
        commentEditor.reset("");
        selectingCommentText = false;
        editingAnnotationIndex = -1;
    }

    private static String sanitiseCommentInput(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        StringBuilder safe = new StringBuilder(input.length());
        for (int offset = 0; offset < input.length();) {
            int codePoint = input.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (codePoint == '\n' || codePoint == '\r') {
                if (safe.isEmpty() || safe.charAt(safe.length() - 1) != '\n') {
                    safe.append('\n');
                }
            } else if (!Character.isISOControl(codePoint)) {
                safe.appendCodePoint(codePoint);
            }
        }
        return safe.toString();
    }

    private List<LocalTextAnnotation> loadAnnotations() {
        try {
            return sortAnnotations(annotationStore.loadVersionedTextAnnotations(session.personalAnnotationKey(),
                    new WikiDocumentCodec().contentHash(editor.document())));
        } catch (IOException | IllegalArgumentException exception) {
            return List.of();
        }
    }

    /** Word-style review panes follow the selected source text, not save time. */
    private static List<LocalTextAnnotation> sortAnnotations(List<LocalTextAnnotation> source) {
        List<LocalTextAnnotation> sorted = new ArrayList<>(source);
        sorted.sort((left, right) -> {
            int comparison = Integer.compare(left.startBlock(), right.startBlock());
            if (comparison == 0) {
                comparison = Integer.compare(left.startColumn(), right.startColumn());
            }
            if (comparison == 0) {
                comparison = Integer.compare(left.endBlock(), right.endBlock());
            }
            if (comparison == 0) {
                comparison = Integer.compare(left.endColumn(), right.endColumn());
            }
            if (comparison == 0) {
                comparison = left.quotedText().compareTo(right.quotedText());
            }
            return comparison == 0 ? left.comment().compareTo(right.comment()) : comparison;
        });
        return List.copyOf(sorted);
    }

    private void showAnnotation(LocalTextAnnotation annotation) {
        int count = NativeDocumentPresentation.pageCount(editor.document());
        for (int candidate = 0; candidate < count; candidate++) {
            DocumentPages.PageRange range = DocumentPages.range(editor.document(), candidate);
            if (annotation.startBlock() >= range.startInclusive() && annotation.startBlock() < range.endExclusive()) {
                showPage(candidate);
                Bounds bounds = bounds();
                ensureLayout(bounds.bodyWidth());
                for (LayoutLine line : layoutLines) {
                    if (line.blockIndex() == annotation.startBlock()) {
                        bodyScroll = clamp(Math.max(0, line.y() - 8), 0, maxBodyScroll(bounds));
                        break;
                    }
                }
                return;
            }
        }
        status("此注释所指的原文已不存在");
    }

    private void updateModuleDivider(int mouseX) {
        Bounds bounds = bounds();
        int innerWidth = bounds.right() - bounds.left() - OUTER_PADDING * 2;
        // The entire module group recentres. Moving either right edge by d requires a width delta of 2d.
        int requested = moduleDragStartWidth + 2 * (mouseX - moduleDragStartX);
        if (moduleDividerDrag == ModuleDividerDrag.DIRECTORY) {
            int maximum = innerWidth - BODY_COLUMN_WIDTH - COLUMN_GAP
                    - (bounds.annotationVisible() ? COLUMN_GAP + bounds.annotationWidth() : 0);
            directoryModuleWidth = clamp(requested, MODULE_MIN_WIDTH,
                    Math.min(MODULE_MAX_WIDTH, Math.max(MODULE_MIN_WIDTH, maximum)));
        } else if (moduleDividerDrag == ModuleDividerDrag.ANNOTATION) {
            int maximum = innerWidth - BODY_COLUMN_WIDTH - COLUMN_GAP
                    - (bounds.directoryVisible() ? COLUMN_GAP + bounds.directoryWidth() : 0);
            annotationModuleWidth = clamp(requested, MODULE_MIN_WIDTH,
                    Math.min(MODULE_MAX_WIDTH, Math.max(MODULE_MIN_WIDTH, maximum)));
        }
        invalidateLayout();
    }

    private void beginTitleEditing(Bounds bounds, double mouseX) {
        String title = editor.document().title();
        if (!editingTitle) {
            editor.finishTextEdit();
            editingTitle = true;
            titleSelectionAnchor = title.length();
        }
        int x = bounds.bodyX() + 5;
        int nextCaret = 0;
        for (int offset = 0; offset < title.length(); ) {
            int codePoint = title.codePointAt(offset);
            int next = offset + Character.charCount(codePoint);
            int glyphWidth = font.width(new String(Character.toChars(codePoint)));
            if (mouseX < x + glyphWidth / 2.0) {
                break;
            }
            x += glyphWidth;
            nextCaret = next;
            offset = next;
        }
        titleCaret = nextCaret;
        titleSelectionAnchor = nextCaret;
    }

    private void finishTitleEditing() {
        if (!editingTitle) {
            return;
        }
        editor.finishTextEdit();
        editingTitle = false;
        titleCaret = 0;
        titleSelectionAnchor = 0;
    }

    private boolean handleTitleKey(int keyCode, int modifiers) {
        boolean control = (modifiers & GLFW.GLFW_MOD_CONTROL) != 0;
        boolean shift = (modifiers & GLFW.GLFW_MOD_SHIFT) != 0;
        if (keyCode == GLFW.GLFW_KEY_ESCAPE || keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            finishTitleEditing();
            return true;
        }
        String title = editor.document().title();
        if (control && keyCode == GLFW.GLFW_KEY_A) {
            titleSelectionAnchor = 0;
            titleCaret = title.length();
            return true;
        }
        if (control && keyCode == GLFW.GLFW_KEY_C) {
            copyTitleSelection();
            return true;
        }
        if (control && keyCode == GLFW.GLFW_KEY_X) {
            copyTitleSelection();
            replaceTitleSelection("");
            return true;
        }
        if (control && keyCode == GLFW.GLFW_KEY_V) {
            if (minecraft != null) {
                replaceTitleSelection(sanitiseTitleInput(minecraft.keyboardHandler.getClipboard()));
            }
            return true;
        }
        if (control && keyCode == GLFW.GLFW_KEY_Z && !shift) {
            if (editor.undo()) {
                titleCaret = editor.document().title().length();
                titleSelectionAnchor = titleCaret;
                refreshAfterEdit();
            }
            return true;
        }
        if (control && (keyCode == GLFW.GLFW_KEY_Y || (keyCode == GLFW.GLFW_KEY_Z && shift))) {
            if (editor.redo()) {
                titleCaret = editor.document().title().length();
                titleSelectionAnchor = titleCaret;
                refreshAfterEdit();
            }
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_HOME) {
            moveTitleCaret(0, shift);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_END) {
            moveTitleCaret(title.length(), shift);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_LEFT) {
            moveTitleCaret(titleCaret <= 0 ? 0 : title.offsetByCodePoints(titleCaret, -1), shift);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_RIGHT) {
            moveTitleCaret(titleCaret >= title.length() ? title.length() : title.offsetByCodePoints(titleCaret, 1), shift);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_BACKSPACE) {
            if (titleCaret != titleSelectionAnchor) {
                replaceTitleSelection("");
            } else if (titleCaret > 0) {
                int previous = title.offsetByCodePoints(titleCaret, -1);
                titleSelectionAnchor = previous;
                replaceTitleSelection("");
            }
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_DELETE) {
            if (titleCaret != titleSelectionAnchor) {
                replaceTitleSelection("");
            } else if (titleCaret < title.length()) {
                int next = title.offsetByCodePoints(titleCaret, 1);
                titleSelectionAnchor = next;
                replaceTitleSelection("");
            }
            return true;
        }
        return true;
    }

    private void moveTitleCaret(int nextCaret, boolean extendSelection) {
        titleCaret = Math.max(0, Math.min(nextCaret, editor.document().title().length()));
        if (!extendSelection) {
            titleSelectionAnchor = titleCaret;
        }
    }

    private void replaceTitleSelection(String replacement) {
        String title = editor.document().title();
        int start = Math.min(titleCaret, titleSelectionAnchor);
        int end = Math.max(titleCaret, titleSelectionAnchor);
        String safe = sanitiseTitleInput(replacement);
        if (editor.replaceTitle(title.substring(0, start) + safe + title.substring(end))) {
            titleCaret = start + safe.length();
            titleSelectionAnchor = titleCaret;
        }
    }

    private void copyTitleSelection() {
        if (minecraft == null || titleCaret == titleSelectionAnchor) {
            return;
        }
        String title = editor.document().title();
        minecraft.keyboardHandler.setClipboard(title.substring(Math.min(titleCaret, titleSelectionAnchor),
                Math.max(titleCaret, titleSelectionAnchor)));
    }

    private static String sanitiseTitleInput(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        StringBuilder safe = new StringBuilder(input.length());
        for (int offset = 0; offset < input.length(); ) {
            int codePoint = input.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (!Character.isISOControl(codePoint)) {
                safe.appendCodePoint(codePoint);
            }
        }
        return safe.toString();
    }

    private void drawPanel(GuiGraphics graphics, Bounds bounds) {
        BookEditorChrome.cover(graphics, bounds.left(), bounds.top(), bounds.right(), bounds.bottom());
        if (bounds.directoryVisible()) {
            drawModulePaper(graphics, bounds.directoryX(), bounds.bodyTop() - 1,
                    bounds.directoryWidth(), bounds.footerY() - bounds.bodyTop(), DIRECTORY_BACKGROUND);
            drawModuleResizeGrip(graphics, bounds.directoryResizeX(), bounds.bodyTop(), bounds.footerY());
        }
        drawModulePaper(graphics, bounds.bodyPaperX(), bounds.bodyTop() - 1,
                bounds.bodyPaperWidth(), bounds.footerY() - bounds.bodyTop(), 0xFF000000 | paperColorRgb);
        if (bounds.annotationVisible()) {
            drawModulePaper(graphics, bounds.annotationX(), bounds.bodyTop() - 1,
                    bounds.annotationWidth(), bounds.footerY() - bounds.bodyTop(), DIRECTORY_BACKGROUND);
            drawModuleResizeGrip(graphics, bounds.annotationResizeX(), bounds.bodyTop(), bounds.footerY());
        }
    }

    private static void drawModulePaper(GuiGraphics graphics, int x, int y, int paperWidth, int paperHeight,
            int fillColor) {
        BookEditorChrome.paper(graphics, x, y, paperWidth, paperHeight, fillColor);
    }

    /** Three tiny edge marks reveal a draggable paper boundary without becoming another large button. */
    private static void drawModuleResizeGrip(GuiGraphics graphics, int x, int top, int bottom) {
        int middle = top + (bottom - top) / 2;
        graphics.fill(x - 1, middle - 3, x + 1, middle - 2, DISABLED_COLOR);
        graphics.fill(x - 1, middle, x + 1, middle + 1, DISABLED_COLOR);
        graphics.fill(x - 1, middle + 3, x + 1, middle + 4, DISABLED_COLOR);
    }

    private void drawHeader(GuiGraphics graphics, Bounds bounds, int mouseX, int mouseY) {
        graphics.fill(bounds.left() + 5, bounds.top() + 4, bounds.right() - 5, bounds.bodyTop() - 6, BookEditorChrome.ENDPAPER);
        drawButton(graphics, bounds.modeX(), bounds.headerY(), 46, 20, mode.toggleLabel(),
                inRect(mouseX, mouseY, bounds.modeX(), bounds.headerY(), 46, 20));
        if (mode.isEditable() && editingTitle) {
            drawTitleEditor(graphics, bounds);
        } else {
            String title = editor.document().title().isBlank() ? "未命名文档" : editor.document().title();
            drawCentered(graphics, title, bounds.bodyX() + bounds.bodyVisibleWidth() / 2, bounds.headerY() + 6, HEADING_COLOR);
        }
        String modeNote = session.isLocked()
                ? (mode == DocumentMode.READ ? "锁定·只读" : "锁定·编辑")
                : (mode == DocumentMode.READ ? "只读" : "编辑");
        if (session.canChangeLock()) {
            drawButton(graphics, bounds.lockButtonX(), bounds.headerY(), 34, 20,
                    session.isLocked() ? "解锁" : "锁定",
                    inRect(mouseX, mouseY, bounds.lockButtonX(), bounds.headerY(), 34, 20));
            drawRight(graphics, modeNote, bounds.lockButtonX() - 4, bounds.headerY() + 6, DISABLED_COLOR);
        } else {
            drawRight(graphics, modeNote, bounds.right() - 8, bounds.headerY() + 6, DISABLED_COLOR);
        }
        if (mode.isEditable()) {
            drawRibbon(graphics, bounds, mouseX, mouseY);
            drawToolbarMenu(graphics, bounds, mouseX, mouseY);
        }
        drawLockConfirmation(graphics, bounds, mouseX, mouseY);
    }

    /** Draws a small child confirmation band; it owns clicks and Escape before the book does. */
    private void drawLockConfirmation(GuiGraphics graphics, Bounds bounds, int mouseX, int mouseY) {
        if (!lockConfirmationOpen) {
            return;
        }
        int x = bounds.lockConfirmationX();
        int y = bounds.lockConfirmationY(mode.isEditable());
        String label = requestedLockState ? "锁定此副本？" : "解除锁定？";
        graphics.fill(x - 3, y - 2, bounds.right() - 5, y + 22, DIRECTORY_BACKGROUND);
        graphics.drawString(font, label, x, y + 6, HEADING_COLOR, false);
        drawButton(graphics, x + 48, y, 20, 20, "是",
                inRect(mouseX, mouseY, x + 48, y, 20, 20));
        drawButton(graphics, x + 72, y, 20, 20, "否",
                inRect(mouseX, mouseY, x + 72, y, 20, 20));
    }

    private void drawModuleToggles(GuiGraphics graphics, Bounds bounds, int mouseX, int mouseY) {
        drawEdgeToggle(graphics, bounds.directoryToggleX(), bounds.moduleToggleY(),
                bounds.directoryVisible() ? "<" : ">",
                inRect(mouseX, mouseY, bounds.directoryToggleX(), bounds.moduleToggleY(),
                        MODULE_TOGGLE_WIDTH, MODULE_TOGGLE_HEIGHT));
        if (hasAnnotationModule()) {
            drawEdgeToggle(graphics, bounds.annotationToggleX(), bounds.moduleToggleY(),
                    bounds.annotationVisible() ? ">" : "<",
                    inRect(mouseX, mouseY, bounds.annotationToggleX(), bounds.moduleToggleY(),
                            MODULE_TOGGLE_WIDTH, MODULE_TOGGLE_HEIGHT));
        }
    }

    /** A bare glyph is intentional: it is an edge affordance, not a panel button. */
    private void drawEdgeToggle(GuiGraphics graphics, int x, int y, String label, boolean hovered) {
        graphics.drawString(font, label, x, y, hovered ? LINK_COLOR : DISABLED_COLOR, false);
    }

    private boolean hasAnnotationModule() {
        return commentComposerOpen || !annotations.isEmpty();
    }

    private void drawTitleEditor(GuiGraphics graphics, Bounds bounds) {
        int x = bounds.bodyX();
        int y = bounds.headerY();
        int titleWidth = bounds.bodyWidth();
        graphics.fill(x, y, x + titleWidth, y + 20, 0xFFFFFFFF);
        graphics.fill(x, y, x + titleWidth, y + 1, PANEL_BORDER_COLOR);
        graphics.fill(x, y + 19, x + titleWidth, y + 20, PANEL_BORDER_COLOR);
        graphics.fill(x, y, x + 1, y + 20, PANEL_BORDER_COLOR);
        graphics.fill(x + titleWidth - 1, y, x + titleWidth, y + 20, PANEL_BORDER_COLOR);
        String title = editor.document().title();
        String visible = truncateToWidth(title, Math.max(8, titleWidth - 10));
        int textX = x + 5;
        int selectionStart = Math.min(titleCaret, titleSelectionAnchor);
        int selectionEnd = Math.max(titleCaret, titleSelectionAnchor);
        if (selectionStart < selectionEnd && selectionEnd <= visible.length()) {
            graphics.fill(textX + font.width(title.substring(0, selectionStart)), y + 4,
                    textX + font.width(title.substring(0, selectionEnd)), y + 16, SELECTION_COLOR);
        }
        graphics.drawString(font, visible, textX, y + 6, HEADING_COLOR, false);
        if (titleCaret <= visible.length()
                && System.currentTimeMillis() % 1000L < 500L) {
            drawCaret(graphics, textX + font.width(title.substring(0, titleCaret)), y + 5, font.lineHeight + 1);
        }
    }

    private void drawToolbarMenu(GuiGraphics graphics, Bounds bounds, int mouseX, int mouseY) {
        if (toolbarMenu == ToolbarMenu.NONE || isColorMenu()) {
            return;
        }
        int x = bounds.modeX() + 52;
        int y = bounds.menuY();
        if (isLinkMenu()) {
            if (linkInput != null) {
                linkInput.setX(bounds.modeX()); linkInput.setY(y + 14);
                linkInput.setWidth(Math.max(30, bounds.right() - bounds.modeX() - 104));
                graphics.drawString(font, toolbarMenu == ToolbarMenu.COMMAND
                        ? "点击执行指令 · 使用点击玩家的权限" : "超链接 · https://网址 或 page:页码", bounds.modeX(), y + 2, BODY_TEXT_COLOR, false);
                linkInput.render(graphics, mouseX, mouseY, 0);
                int bx = bounds.right() - 98;
                drawButton(graphics, bx, y + 14, 42, 18, "应用", inRect(mouseX, mouseY, bx, y + 14, 42, 18));
                drawButton(graphics, bx + 46, y + 14, 42, 18, "移除", inRect(mouseX, mouseY, bx + 46, y + 14, 42, 18));
            }
            return;
        }
        if (toolbarMenu == ToolbarMenu.HEADING) {
            drawButton(graphics, x, y, 34, 20, "正文", inRect(mouseX, mouseY, x, y, 34, 20));
            for (int level = 1; level <= 3; level++) {
                int optionX = x + 38 + (level - 1) * 28;
                drawButton(graphics, optionX, y, 24, 20, "H" + level,
                        inRect(mouseX, mouseY, optionX, y, 24, 20));
            }
            return;
        }
        if (toolbarMenu == ToolbarMenu.FONT_SIZE) {
            for (int index = 0; index < FONT_SIZE_PRESETS.length; index++) {
                int optionX = x + index * 25;
                drawButton(graphics, optionX, y, 22, 20, Integer.toString(FONT_SIZE_PRESETS[index]),
                        inRect(mouseX, mouseY, optionX, y, 22, 20));
            }
            int inputX = x + FONT_SIZE_PRESETS.length * 25 + 4;
            graphics.fill(inputX, y, inputX + 62, y + 20, 0xFFFFFFFF);
            graphics.fill(inputX, y, inputX + 62, y + 1, PANEL_BORDER_COLOR);
            graphics.fill(inputX, y + 19, inputX + 62, y + 20, PANEL_BORDER_COLOR);
            graphics.fill(inputX, y, inputX + 1, y + 20, PANEL_BORDER_COLOR);
            graphics.fill(inputX + 61, y, inputX + 62, y + 20, PANEL_BORDER_COLOR);
            graphics.drawString(font, "输入 " + fontSizeInput, inputX + 4, y + 6, BODY_TEXT_COLOR, false);
            return;
        }
        if (toolbarMenu == ToolbarMenu.TABLE) {
            graphics.drawString(font, "行", x, y + 6, BODY_TEXT_COLOR, false);
            drawTableSizeInput(graphics, x + 12, y, tableRowsInput, tableSizeInput == TableSizeInput.ROWS,
                    inRect(mouseX, mouseY, x + 12, y, 24, 20));
            graphics.drawString(font, "列", x + 41, y + 6, BODY_TEXT_COLOR, false);
            drawTableSizeInput(graphics, x + 53, y, tableColumnsInput, tableSizeInput == TableSizeInput.COLUMNS,
                    inRect(mouseX, mouseY, x + 53, y, 24, 20));
            drawButton(graphics, x + 82, y, 34, 20, "插入", inRect(mouseX, mouseY, x + 82, y, 34, 20));
            return;
        }
        if (toolbarMenu == ToolbarMenu.SORT) {
            drawButton(graphics, x, y, 38, 20, "A→Z", inRect(mouseX, mouseY, x, y, 38, 20));
            drawButton(graphics, x + 42, y, 38, 20, "Z→A", inRect(mouseX, mouseY, x + 42, y, 38, 20));
            return;
        }
    }

    private void drawDirectory(GuiGraphics graphics, Bounds bounds, int mouseX, int mouseY) {
        if (!bounds.directoryVisible()) {
            return;
        }
        int textY = bounds.bodyTop() + 6 - directoryScroll;
        int textRight = bounds.directoryX() + bounds.directoryWidth() - 7;
        List<NativeDocumentPresentation.TableOfContentsEntry> directory = directory();
        if (directory.isEmpty()) {
            graphics.drawString(font, "目录", bounds.directoryX() + 8, textY, LINK_COLOR, false);
            graphics.drawString(font, "（没有标题）", bounds.directoryX() + 8, textY + directoryLineHeight(), DISABLED_COLOR, false);
            return;
        }
        for (int index = 0; index < directory.size(); index++) {
            NativeDocumentPresentation.TableOfContentsEntry entry = directory.get(index);
            int rowY = textY + index * directoryLineHeight();
            if (rowY + directoryLineHeight() < bounds.bodyTop() || rowY >= bounds.footerY()) {
                continue;
            }
            int left = bounds.directoryX() + 7 + (entry.level() - 1) * 8;
            boolean active = entry.pageIndex() == pageIndex;
            boolean hovered = inRect(mouseX, mouseY, bounds.directoryX(), rowY,
                    bounds.directoryWidth(), directoryLineHeight());
            int color = active || hovered ? LINK_COLOR : 0xFF766F67;
            String displayed = truncateToWidth(entry.displayText(), Math.max(28, textRight - left));
            graphics.drawString(font, displayed, left, rowY, color, false);
        }
    }

    /** Draws range-anchored local review comments; they are never document blocks. */
    private void drawAnnotation(GuiGraphics graphics, Bounds bounds, int mouseX, int mouseY) {
        if (!bounds.annotationVisible()) {
            return;
        }
        int x = bounds.annotationX() + 6;
        int top = bounds.bodyTop() + 5;
        int availableWidth = Math.max(20, bounds.annotationWidth() - 12);
        graphics.drawString(font, "注释（仅本地）", x, top, LINK_COLOR, false);
        int textTop = top + directoryLineHeight() + 3;
        int textBottom = bounds.footerY() - 4;
        graphics.enableScissor(bounds.annotationX(), textTop,
                bounds.annotationX() + bounds.annotationWidth(), textBottom);
        List<AnnotationCardLayout> cards = annotationCardLayouts(availableWidth);
        for (AnnotationCardLayout card : cards) {
            int cardTop = textTop + card.startRow() * directoryLineHeight() - annotationScroll;
            int cardBottom = textTop + card.endRow() * directoryLineHeight() - annotationScroll - 2;
            if (cardBottom < textTop || cardTop >= textBottom) {
                continue;
            }
            int cardColor = card.composer() ? 0x50FFFFFF
                    : (card.annotationIndex() == selectedAnnotationIndex ? 0x30D8BF83 : 0x24FFFFFF);
            graphics.fill(x - 2, cardTop - 1, bounds.annotationX() + bounds.annotationWidth() - 4,
                    Math.max(cardTop, cardBottom), cardColor);
            for (int index = 0; index < card.quoteLines().size(); index++) {
                int y = textTop + (card.quoteStartRow() + index) * directoryLineHeight() - annotationScroll;
                graphics.drawString(font, card.quoteLines().get(index), x, y, LINK_COLOR, false);
            }
            if (card.composer()) {
                drawAnnotationComposerCard(graphics, card, textTop, x);
            } else {
                for (int index = 0; index < card.commentLines().size(); index++) {
                    int y = textTop + (card.commentStartRow() + index) * directoryLineHeight() - annotationScroll;
                    graphics.drawString(font, card.commentLines().get(index), x, y, BODY_TEXT_COLOR, false);
                }
            }
        }
        if (cards.isEmpty()) {
            graphics.drawString(font, "框选文字后右键添加注释", x, textTop, DISABLED_COLOR, false);
        }
        graphics.disableScissor();
        String hint = commentComposerOpen ? "自动保存 · 点击正文切换输入" : "点击备注编辑并定位原文";
        drawRight(graphics, hint, bounds.annotationX() + bounds.annotationWidth() - 6,
                bounds.footerY() - 14, DISABLED_COLOR);
    }

    /** The draft takes the same card form as saved comments and saves on every edit. */
    private void drawAnnotationComposerCard(GuiGraphics graphics, AnnotationCardLayout card, int textTop, int x) {
        for (int index = 0; index < card.inputLines().size(); index++) {
            CommentInputLine line = card.inputLines().get(index);
            int y = textTop + (card.commentStartRow() + index) * directoryLineHeight() - annotationScroll;
            if (commentEditor.text().isEmpty()) {
                graphics.drawString(font, "输入本地评语…", x, y, DISABLED_COLOR, false);
                drawCaret(graphics, x, y, font.lineHeight + 1);
            } else {
                int selectionStart = Math.max(line.start(), commentEditor.start());
                int selectionEnd = Math.min(line.end(), commentEditor.end());
                if (selectionStart < selectionEnd) {
                    int left = font.width(line.text().substring(0, selectionStart - line.start()));
                    int right = font.width(line.text().substring(0, selectionEnd - line.start()));
                    graphics.fill(x + left, y - 1, x + right, y + font.lineHeight + 1, SELECTION_COLOR);
                }
                graphics.drawString(font, line.text(), x, y, BODY_TEXT_COLOR, false);
                boolean caretOnLine = commentEditor.caret() >= line.start()
                        && (commentEditor.caret() < line.end()
                        || index == card.inputLines().size() - 1 && commentEditor.caret() == line.end());
                if (caretOnLine) {
                    int localEnd = Math.max(0, Math.min(line.text().length(), commentEditor.caret() - line.start()));
                    drawCaret(graphics, x + font.width(line.text().substring(0, localEnd)), y, font.lineHeight + 1);
                }
            }
        }
    }

    /** Produces the visual right-hand review pane in document-anchor order. */
    private List<AnnotationCardLayout> annotationCardLayouts(int availableWidth) {
        List<AnnotationCardSource> sources = new ArrayList<>();
        for (int index = 0; index < annotations.size(); index++) {
            if (index == editingAnnotationIndex) {
                continue;
            }
            LocalTextAnnotation annotation = annotations.get(index);
            sources.add(new AnnotationCardSource(index, new PendingAnnotation(annotation.startBlock(), annotation.startColumn(),
                    annotation.endBlock(), annotation.endColumn(), annotation.quotedText()), annotation.comment(), false));
        }
        if (commentComposerOpen && pendingAnnotation != null) {
            sources.add(new AnnotationCardSource(editingAnnotationIndex, pendingAnnotation, commentEditor.text(), true));
        }
        sources.sort((left, right) -> {
            int comparison = compareAnnotationAnchors(left.anchor(), right.anchor());
            if (comparison != 0) {
                return comparison;
            }
            if (left.composer() != right.composer()) {
                return left.composer() ? 1 : -1;
            }
            return Integer.compare(left.annotationIndex(), right.annotationIndex());
        });

        List<AnnotationCardLayout> result = new ArrayList<>();
        int row = 0;
        for (AnnotationCardSource source : sources) {
            // The selected original text is only an invisible anchor. A card
            // deliberately displays no quotation, so its entire visible body
            // is authored by the player.
            List<String> quoteLines = List.of();
            int startRow = row;
            int quoteStartRow = row;
            row += quoteLines.size();
            int commentStartRow = row;
            List<CommentInputLine> inputLines = source.composer() ? commentInputLines(availableWidth) : List.of();
            List<String> commentLines = source.composer() ? List.of() : wrapAnnotationText(source.comment(), availableWidth);
            row += source.composer() ? inputLines.size() : commentLines.size();
            int endRow = row;
            row++;
            result.add(new AnnotationCardLayout(source.annotationIndex(), source.composer(), quoteLines, commentLines,
                    inputLines, startRow, quoteStartRow, commentStartRow, endRow));
        }
        return result;
    }

    private AnnotationCardLayout annotationCardAtRow(List<AnnotationCardLayout> cards, int row) {
        for (AnnotationCardLayout card : cards) {
            if (row >= card.startRow() && row < card.endRow()) {
                return card;
            }
        }
        return null;
    }

    private static int compareAnnotationAnchors(PendingAnnotation left, PendingAnnotation right) {
        int comparison = Integer.compare(left.startBlock(), right.startBlock());
        if (comparison == 0) {
            comparison = Integer.compare(left.startColumn(), right.startColumn());
        }
        if (comparison == 0) {
            comparison = Integer.compare(left.endBlock(), right.endBlock());
        }
        return comparison == 0 ? Integer.compare(left.endColumn(), right.endColumn()) : comparison;
    }

    private List<String> wrapAnnotationText(String source, int availableWidth) {
        List<String> result = new ArrayList<>();
        int offset = 0;
        StringBuilder line = new StringBuilder();
        int lineWidth = 0;
        while (offset < source.length()) {
            int codePoint = source.codePointAt(offset);
            String character = new String(Character.toChars(codePoint));
            offset += Character.charCount(codePoint);
            if (codePoint == '\n') {
                result.add(line.toString());
                line.setLength(0);
                lineWidth = 0;
                continue;
            }
            int characterWidth = font.width(character);
            if (lineWidth > 0 && lineWidth + characterWidth > availableWidth) {
                result.add(line.toString());
                line.setLength(0);
                lineWidth = 0;
            }
            line.append(character);
            lineWidth += characterWidth;
        }
        result.add(line.toString());
        return result;
    }

    private List<AnnotationDisplayLine> annotationDisplayLines(int availableWidth) {
        List<AnnotationDisplayLine> result = new ArrayList<>();
        int visualIndex = 0;
        for (int index = 0; index < annotations.size(); index++) {
            LocalTextAnnotation annotation = annotations.get(index);
            visualIndex = appendAnnotationDisplayText(result, index, "「" + annotation.quotedText() + "」",
                    true, visualIndex, availableWidth);
            visualIndex = appendAnnotationDisplayText(result, index, annotation.comment(), false, visualIndex,
                    availableWidth);
            visualIndex++;
        }
        return result;
    }

    private int appendAnnotationDisplayText(List<AnnotationDisplayLine> result, int annotationIndex, String source,
            boolean quoted, int visualIndex, int availableWidth) {
        int offset = 0;
        StringBuilder line = new StringBuilder();
        int lineWidth = 0;
        while (offset < source.length()) {
            int codePoint = source.codePointAt(offset);
            String character = new String(Character.toChars(codePoint));
            offset += Character.charCount(codePoint);
            if (codePoint == '\n') {
                result.add(new AnnotationDisplayLine(annotationIndex, quoted, line.toString(), visualIndex++));
                line.setLength(0);
                lineWidth = 0;
                continue;
            }
            int characterWidth = font.width(character);
            if (lineWidth > 0 && lineWidth + characterWidth > availableWidth) {
                result.add(new AnnotationDisplayLine(annotationIndex, quoted, line.toString(), visualIndex++));
                line.setLength(0);
                lineWidth = 0;
            }
            line.append(character);
            lineWidth += characterWidth;
        }
        if (!line.isEmpty() || source.isEmpty()) {
            result.add(new AnnotationDisplayLine(annotationIndex, quoted, line.toString(), visualIndex++));
        }
        return visualIndex;
    }

    private List<CommentInputLine> commentInputLines(int availableWidth) {
        List<CommentInputLine> result = new ArrayList<>();
        int start = 0;
        int offset = 0;
        int visualIndex = 0;
        StringBuilder line = new StringBuilder();
        int lineWidth = 0;
        while (offset < commentEditor.text().length()) {
            int characterStart = offset;
            int codePoint = commentEditor.text().codePointAt(offset);
            String character = new String(Character.toChars(codePoint));
            offset += Character.charCount(codePoint);
            if (codePoint == '\n') {
                result.add(new CommentInputLine(start, characterStart, line.toString(), visualIndex++));
                start = offset;
                line.setLength(0);
                lineWidth = 0;
                continue;
            }
            int characterWidth = font.width(character);
            if (lineWidth > 0 && lineWidth + characterWidth > availableWidth) {
                result.add(new CommentInputLine(start, characterStart, line.toString(), visualIndex++));
                start = characterStart;
                line.setLength(0);
                lineWidth = 0;
            }
            line.append(character);
            lineWidth += characterWidth;
        }
        result.add(new CommentInputLine(start, commentEditor.text().length(), line.toString(), visualIndex));
        return result;
    }

    /** Draws the local-only red dotted leaders in the whitespace between the papers. */
    private void drawAnnotationConnectors(GuiGraphics graphics, Bounds bounds) {
        if (!bounds.annotationVisible() || annotations.isEmpty()) {
            return;
        }
        ensureLayout(bounds.bodyWidth());
        int[] targetY = new int[annotations.size()];
        java.util.Arrays.fill(targetY, -1);
        int annotationTextTop = bounds.bodyTop() + 5 + directoryLineHeight() + 3;
        for (AnnotationCardLayout card : annotationCardLayouts(Math.max(20, bounds.annotationWidth() - 12))) {
            if (card.annotationIndex() >= 0 && card.annotationIndex() < targetY.length
                    && targetY[card.annotationIndex()] < 0) {
                targetY[card.annotationIndex()] = annotationTextTop
                        + card.commentStartRow() * directoryLineHeight() - annotationScroll + 5;
            }
        }
        for (LayoutLine line : layoutLines) {
            int lineY = bounds.bodyTop() + line.y() - bodyScroll;
            if (lineY + line.height() < bounds.bodyTop() || lineY >= bounds.footerY()) {
                continue;
            }
            int[] anchorX = new int[annotations.size()];
            java.util.Arrays.fill(anchorX, -1);
            int cursorX = bounds.bodyX() + line.indent();
            for (Fragment fragment : line.fragments()) {
                int fragmentWidth = measuredFragmentWidth(fragment);
                cursorX = bounds.bodyX() + Math.round(fragment.x());
                int annotation = annotationForFragment(line, fragment);
                if (annotation >= 0) {
                    anchorX[annotation] = cursorX + fragmentWidth;
                }
                cursorX += fragmentWidth;
            }
            int lane = 0;
            for (int annotation = 0; annotation < anchorX.length; annotation++) {
                if (anchorX[annotation] < 0 || targetY[annotation] < bounds.bodyTop()
                        || targetY[annotation] >= bounds.footerY()) {
                    continue;
                }
                int sourceY = lineY + line.height() + 1;
                // Each marked span receives its own narrow route in the empty
                // gutter, so multiple fonts on one line do not merge together.
                int gutterX = bounds.bodyPaperX() + bounds.bodyPaperWidth() + 2 + (lane++ % 3) * 2;
                drawDashedHorizontal(graphics, anchorX[annotation], gutterX, sourceY);
                drawDashedVertical(graphics, gutterX, sourceY, targetY[annotation]);
                drawDashedHorizontal(graphics, gutterX, bounds.annotationX() - 2, targetY[annotation]);
            }
        }
    }

    private static void drawDashedHorizontal(GuiGraphics graphics, int fromX, int toX, int y) {
        int step = fromX <= toX ? 1 : -1;
        for (int x = fromX, count = 0; x != toX; x += step, count++) {
            if ((count / 2) % 2 == 0) {
                graphics.fill(x, y, x + 1, y + 1, ANNOTATION_CONNECTOR_COLOR);
            }
        }
    }

    private static void drawDashedVertical(GuiGraphics graphics, int x, int fromY, int toY) {
        int step = fromY <= toY ? 1 : -1;
        for (int y = fromY, count = 0; y != toY; y += step, count++) {
            if ((count / 2) % 2 == 0) {
                graphics.fill(x, y, x + 1, y + 1, ANNOTATION_CONNECTOR_COLOR);
            }
        }
    }

    private void drawDocument(GuiGraphics graphics, Bounds bounds) {
        ensureLayout(bounds.bodyWidth());
        // Document objects may be taller than the viewport while the player
        // scrolls. Clip every text line, image and selection handle to the
        // body module so none can cover the footer, side modules or game UI.
        graphics.enableScissor(bounds.bodyX(), bounds.bodyTop(),
                bounds.bodyX() + bounds.bodyVisibleWidth(), bounds.footerY());
        if (layoutLines.isEmpty() && imageLayouts.isEmpty() && tableLayouts.isEmpty()) {
            drawCentered(graphics, "本页为空", bounds.bodyX() + bounds.bodyWidth() / 2,
                    bounds.bodyTop() + 16, DISABLED_COLOR);
            graphics.disableScissor();
            return;
        }
        for (FrameLayout frame : frameLayouts) drawFrame(graphics, bounds, frame);
        // Floating images are deliberately behind prose.  "覆盖" is painted
        // last below, so it is the explicit image-on-top alternative.
        for (ImageLayout image : imageLayouts) {
            if (image.layoutMode() == ImageLayoutMode.FLOATING) {
                drawImage(graphics, bounds, image);
            }
        }
        for (LayoutLine line : layoutLines) {
            int y = bounds.bodyTop() + line.y() - bodyScroll;
            if (y + line.height() < bounds.bodyTop() || y >= bounds.footerY()) {
                continue;
            }
            if (line.quote()) {
                graphics.fill(bounds.bodyX(), y - 2, bounds.bodyX() + bounds.bodyWidth(), y + line.height() + 1,
                        QUOTE_BACKGROUND);
                graphics.fill(bounds.bodyX(), y - 2, bounds.bodyX() + 3, y + line.height() + 1, LINK_COLOR);
            }
            drawRichDecoration(graphics, bounds, line, y);
            drawFragments(graphics, line, bounds.bodyX(), y);
        }
        for (ImageLayout image : imageLayouts) {
            if (image.layoutMode() != ImageLayoutMode.FLOATING && image.layoutMode() != ImageLayoutMode.COVER) {
                drawImage(graphics, bounds, image);
            }
        }
        for (TableLayout table : tableLayouts) {
            drawTable(graphics, bounds, table);
        }
        for (ImageLayout image : imageLayouts) {
            if (image.layoutMode() == ImageLayoutMode.COVER) {
                drawImage(graphics, bounds, image);
            }
        }
        drawBlockFoldControls(graphics, bounds);
        graphics.disableScissor();
    }

    private void drawImage(GuiGraphics graphics, Bounds bounds, ImageLayout image) {
        ImageVisual visual = imageVisual(image);
        int y = bounds.bodyTop() + visual.y() - bodyScroll;
        if (y + visual.height() < bounds.bodyTop() || y >= bounds.footerY()) {
            return;
        }
        int x = bounds.bodyX() + visual.xOffset();
        if (image.loaded() == null) {
            graphics.fill(x, y, x + visual.width(), y + visual.height(), 0xFFF0E5D0);
            graphics.fill(x, y, x + visual.width(), y + 1, PANEL_BORDER_COLOR);
            graphics.fill(x, y + visual.height() - 1, x + visual.width(), y + visual.height(), PANEL_BORDER_COLOR);
            drawCentered(graphics, "图片不可用", x + visual.width() / 2,
                    y + Math.max(4, (visual.height() - font.lineHeight) / 2), DISABLED_COLOR);
        } else {
            WikiImageTextures.LoadedImage loaded = image.loaded();
            graphics.blit(loaded.texture(), x, y, visual.width(), visual.height(), 0f, 0f,
                    loaded.width(), loaded.height(), loaded.width(), loaded.height());
        }
        if (mode.isEditable() && selectedImageBlock == image.blockIndex()) {
            drawImageSelection(graphics, x, y, visual.width(), visual.height());
        }
    }

    /** Draws one authored table identically in reader and editor modes. */
    private void drawTable(GuiGraphics graphics, Bounds bounds, TableLayout table) {
        int tableY = bounds.bodyTop() + table.y() - bodyScroll;
        if (tableY + table.totalHeight() < bounds.bodyTop() || tableY >= bounds.footerY()) {
            return;
        }
        for (int row = 0; row < table.rowLayouts().size(); row++) {
            TableRowLayout rowLayout = table.rowLayouts().get(row);
            int cellY = tableY + rowLayout.yOffset();
            for (int column = 0; column < table.columns(); column++) {
                int cellX = bounds.bodyX() + table.xOffset() + column * table.baseColumnWidth();
                int cellWidth = table.columnWidth(column);
                boolean active = isActiveTableCell(table.blockIndex(), row, column);
                graphics.fill(cellX, cellY, cellX + cellWidth, cellY + rowLayout.height(),
                        active ? 0xFFDCE8F5 : 0xFFFFFFFF);
                graphics.fill(cellX, cellY, cellX + cellWidth, cellY + 1, PANEL_BORDER_COLOR);
                graphics.fill(cellX, cellY + rowLayout.height() - 1, cellX + cellWidth, cellY + rowLayout.height(),
                        PANEL_BORDER_COLOR);
                graphics.fill(cellX, cellY, cellX + 1, cellY + rowLayout.height(), PANEL_BORDER_COLOR);
                graphics.fill(cellX + cellWidth - 1, cellY, cellX + cellWidth, cellY + rowLayout.height(),
                        PANEL_BORDER_COLOR);
                drawTableCell(graphics, table, row, column, rowLayout.cells().get(column),
                        cellX + TABLE_CELL_PADDING, cellY + TABLE_CELL_PADDING);
            }
        }
    }

    private void drawTableCell(GuiGraphics graphics, TableLayout table, int row, int column,
            TableCellLayout cell, int x, int y) {
        int caret = isActiveTableCell(table.blockIndex(), row, column) ? tableCellCaret.column() : -1;
        TableCellLine caretLine = null;
        for (TableCellLine candidate : cell.lines())
            if (caret >= candidate.startColumn() && caret <= candidate.endColumn()) caretLine = candidate;
        for (TableCellLine line : cell.lines()) {
            int cursorX = x;
            int lineY = y + line.yOffset();
            for (Fragment fragment : line.fragments()) {
                cursorX = x + Math.round(fragment.x());
                int characterWidth = measuredFragmentWidth(fragment);
                if (caret >= 0 && fragment.startColumn() < Math.max(caret, tableSelectionAnchor)
                        && fragment.endColumn() > Math.min(caret, tableSelectionAnchor))
                    graphics.fill(cursorX, lineY, cursorX + characterWidth, lineY + line.height(), SELECTION_COLOR);
                if (line == caretLine && caret == fragment.startColumn()) {
                    drawCaret(graphics, cursorX, lineY, line.height());
                }
                paintFragment(graphics, fragment, x, lineY, line.height(), spoilerKey(table.blockIndex(), row+":"+column,
                        fragment.startColumn(), table.table().rows().get(row).cells().get(column).runs()));
                cursorX += characterWidth;
            }
            if (line == caretLine && caret == line.endColumn()) {
                drawCaret(graphics, cursorX, lineY, line.height());
            }
        }
    }

    private boolean isActiveTableCell(int blockIndex, int row, int column) {
        return mode.isEditable() && tableCellCaret != null
                && tableCellCaret.blockIndex() == blockIndex
                && tableCellCaret.row() == row
                && tableCellCaret.tableColumn() == column;
    }

    private void drawFooter(GuiGraphics graphics, Bounds bounds, int mouseX, int mouseY) {
        graphics.fill(bounds.left() + 5, bounds.footerY() - 4, bounds.right() - 5, bounds.bottom() - 4, BookEditorChrome.ENDPAPER);
        drawButton(graphics, bounds.previousX(), bounds.footerY(), 50, 20, "上一页",
                inRect(mouseX, mouseY, bounds.previousX(), bounds.footerY(), 50, 20));
        drawButton(graphics, bounds.nextX(), bounds.footerY(), 50, 20, "下一页",
                inRect(mouseX, mouseY, bounds.nextX(), bounds.footerY(), 50, 20));
        boolean pageNumberHovered = inRect(mouseX, mouseY, bounds.pageNumberX(), bounds.footerY(),
                bounds.pageNumberWidth(), 20);
        String pageLabel = (pageIndex + 1) + "/" + NativeDocumentPresentation.pageCount(editor.document());
        if (pageNumberHovered || pagePickerOpen) {
            graphics.fill(bounds.pageNumberX(), bounds.footerY() + 2,
                    bounds.pageNumberX() + bounds.pageNumberWidth(), bounds.footerY() + 19, DIRECTORY_BACKGROUND);
        }
        drawCentered(graphics, pageLabel, bounds.bodyX() + bounds.bodyWidth() / 2, bounds.footerY() + 6,
                BODY_TEXT_COLOR);
    }

    /** Draws a small child menu above the page footer; no screen replacement is involved. */
    private void drawPagePicker(GuiGraphics graphics, Bounds bounds, int mouseX, int mouseY) {
        if (!pagePickerOpen) {
            return;
        }
        int pageCount = NativeDocumentPresentation.pageCount(editor.document());
        int chooserWidth = Math.min(Math.max(112, bounds.bodyWidth() - 8), 208);
        int chooserX = bounds.bodyX() + (bounds.bodyWidth() - chooserWidth) / 2;
        int chooserY = bounds.footerY() - 27;
        graphics.fill(chooserX, chooserY, chooserX + chooserWidth, chooserY + 24, PANEL_BORDER_COLOR);
        graphics.fill(chooserX + 1, chooserY + 1, chooserX + chooserWidth - 1, chooserY + 23, PAPER_COLOR);
        graphics.drawString(font, "跳至", chooserX + 5, chooserY + 8, BODY_TEXT_COLOR, false);
        int inputX = chooserX + 31;
        int inputWidth = 31;
        graphics.fill(inputX, chooserY + 4, inputX + inputWidth, chooserY + 20, DIRECTORY_BACKGROUND);
        drawCentered(graphics, pagePickerInput.isBlank() ? "?" : pagePickerInput,
                inputX + inputWidth / 2, chooserY + 7, BODY_TEXT_COLOR);
        graphics.drawString(font, "/" + pageCount + " 页", inputX + inputWidth + 4, chooserY + 8,
                DISABLED_COLOR, false);

        int first = Math.max(0, Math.min(pageCount - 5, pageIndex - 2));
        int chipX = chooserX + chooserWidth - 59;
        for (int index = 0; index < Math.min(5, pageCount); index++) {
            int candidate = first + index;
            int x = chipX + index * 11;
            boolean hovered = inRect(mouseX, mouseY, x, chooserY + 5, 10, 14);
            if (candidate == pageIndex || hovered) {
                graphics.fill(x, chooserY + 5, x + 10, chooserY + 19,
                        candidate == pageIndex ? 0xFFDDCBA4 : DIRECTORY_BACKGROUND);
            }
            drawCentered(graphics, Integer.toString(candidate + 1), x + 5, chooserY + 8, BODY_TEXT_COLOR);
        }
    }

    private boolean handlePagePickerClick(Bounds bounds, double mouseX, double mouseY) {
        int pageCount = NativeDocumentPresentation.pageCount(editor.document());
        int chooserWidth = Math.min(Math.max(112, bounds.bodyWidth() - 8), 208);
        int chooserX = bounds.bodyX() + (bounds.bodyWidth() - chooserWidth) / 2;
        int chooserY = bounds.footerY() - 27;
        if (!inRect(mouseX, mouseY, chooserX, chooserY, chooserWidth, 24)) {
            return false;
        }
        int first = Math.max(0, Math.min(pageCount - 5, pageIndex - 2));
        int chipX = chooserX + chooserWidth - 59;
        for (int index = 0; index < Math.min(5, pageCount); index++) {
            if (inRect(mouseX, mouseY, chipX + index * 11, chooserY + 5, 10, 14)) {
                showPage(first + index);
                pagePickerOpen = false;
                return true;
            }
        }
        return true;
    }

    private boolean handlePagePickerKey(int keyCode) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            pagePickerOpen = false;
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_BACKSPACE) {
            if (!pagePickerInput.isEmpty()) {
                pagePickerInput = pagePickerInput.substring(0, pagePickerInput.length() - 1);
            }
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            try {
                int requestedPage = Integer.parseInt(pagePickerInput) - 1;
                showPage(requestedPage);
            } catch (NumberFormatException ignored) {
                // An empty field simply stays on the current page.
            }
            pagePickerOpen = false;
            return true;
        }
        return false;
    }

    private void ensureLayout(int bodyWidth) {
        WikiDocument document = editor.document();
        long imageTextureGeneration = WikiImageTextures.generation();
        if (document == laidOutDocument && pageIndex == laidOutPage && bodyWidth == laidOutWidth
                && imageTextureGeneration == laidOutImageTextureGeneration) {
            return;
        }
        laidOutDocument = document;
        laidOutPage = pageIndex;
        laidOutWidth = bodyWidth;
        laidOutImageTextureGeneration = imageTextureGeneration;
        List<LayoutLine> result = new ArrayList<>();
        List<ImageLayout> renderedImages = new ArrayList<>();
        List<TableLayout> renderedTables = new ArrayList<>();
        List<FrameLayout> renderedFrames = new ArrayList<>();
        List<TextFlowExclusion> wrappingImages = new ArrayList<>();
        int y = 0;
        DocumentPages.PageRange pageRange = DocumentPages.range(document, pageIndex);

        // Word treats every non-inline image as a page object first, not as a
        // paragraph-sized block.  Resolve those objects before measuring any
        // prose so an image moved into the middle of an existing paragraph
        // makes that prose flow on both its left and its right.
        for (int blockIndex = pageRange.startInclusive(); blockIndex < pageRange.endExclusive(); blockIndex++) {
            Block block = document.blocks().get(blockIndex);
            if (block instanceof ImageBlock image && image.frame() == null && image.layoutMode() != ImageLayoutMode.INLINE) {
                ImageAppend positioned = appendImage(renderedImages, 0, blockIndex, image, bodyWidth);
                if (positioned.flowExclusion() != null) {
                    wrappingImages.add(positioned.flowExclusion());
                }
            }
        }
        int frameStart = -1, frameEnd = -1, frameY = 0, frameFirstLine = 0, frameFirstImage = 0, frameFirstTable = 0, frameFirstExclusion = 0;
        for (int blockIndex = pageRange.startInclusive(); blockIndex < pageRange.endExclusive(); blockIndex++) {
            Block block = document.blocks().get(blockIndex);
            BlockFrame frame = block.frame();
            if (frame != null && blockIndex > frameEnd) {
                y = advancePastWrappingImages(y, wrappingImages);
                frameStart = blockIndex; frameEnd = blockIndex;
                while (frameEnd + 1 < pageRange.endExclusive() && frame.equals(document.blocks().get(frameEnd + 1).frame())) frameEnd++;
                frameY = y; frameFirstLine = result.size(); frameFirstImage = renderedImages.size(); frameFirstTable = renderedTables.size();
                frameFirstExclusion = wrappingImages.size();
                y += 4;
                for (int member = frameStart; member <= frameEnd; member++) {
                    if (document.blocks().get(member) instanceof ImageBlock image && image.layoutMode() != ImageLayoutMode.INLINE) {
                        ImageAppend positioned = appendImage(renderedImages, y, member, image,
                                bodyWidth - frameInset(frame) - 12, frameInset(frame), y);
                        if (positioned.flowExclusion() != null) wrappingImages.add(positioned.flowExclusion());
                    }
                }
            }
            int firstLine = result.size();
            if (block instanceof Heading heading) {
                y = appendTextBlock(result, y, blockIndex, heading.runs(), bodyWidth, headingIndent(heading.level()),
                        false, headingScale(heading.level()), true, wrappingImages);
            } else if (block instanceof RichTextBlock rich) {
                if (rich.alignment() != RichTextBlock.Alignment.LEFT) y = advancePastWrappingImages(y, wrappingImages);
                y = appendRichBlock(result, y, blockIndex, rich, bodyWidth, wrappingImages);
            } else if (block instanceof HorizontalRule) {
                y = advancePastWrappingImages(y, wrappingImages);
                result.add(new LayoutLine(blockIndex, 0, 0, y, 12, 0, false, List.of()));
                y += 18;
            } else if (block instanceof Paragraph paragraph) {
                y = appendTextBlock(result, y, blockIndex, paragraph.runs(), bodyWidth, 0, false, 1f, false,
                        wrappingImages);
            } else if (block instanceof QuoteBlock quote) {
                y = appendTextBlock(result, y, blockIndex, quote.runs(), bodyWidth, 8, true, 1f, false,
                        wrappingImages);
                if (!quote.source().isBlank()) {
                    y = appendTextBlock(result, y - 2, -1,
                            List.of(new TextRun("— " + quote.source(), new TextStyle(false, true, false, 0x62594E, 10))),
                            bodyWidth, 14, true, 0.85f, false, wrappingImages);
                }
            } else if (block instanceof ImageBlock image) {
                if (image.layoutMode() == ImageLayoutMode.INLINE) {
                    y = appendImage(renderedImages, y, blockIndex, image,
                            bodyWidth - (frame == null ? 0 : frameInset(frame) + 12),
                            frameInset(frame), frame == null ? 0 : frameY + 4).nextY();
                }
            } else if (block instanceof TableBlock table) {
                // Tables remain block-level in this phase.  Do not let one
                // accidentally paint through a square-wrapped picture.
                y = advancePastWrappingImages(y, wrappingImages);
                y = appendTable(renderedTables, y, blockIndex, table, bodyWidth, wrappingImages);
            }
            if (collapsedBlocks.contains(blockIndex) && canFold(blockIndex) && result.size() > firstLine + 1) {
                LayoutLine summary = result.get(firstLine);
                result.subList(firstLine + 1, result.size()).clear();
                y = summary.y() + summary.height() + 7;
            }
            if (frame != null && blockIndex == frameEnd) {
                for (int i = frameFirstImage; i < renderedImages.size(); i++) {
                    ImageLayout image = renderedImages.get(i);
                    y = Math.max(y, image.y() + image.height() + 4);
                }
                boolean foldable = frameEnd > frameStart || result.size() > frameFirstLine + 1;
                if (frameEnd == frameStart && block instanceof RichTextBlock rich && rich.isTask()) foldable = false;
                if (foldable && collapsedBlocks.contains(frameStart)) {
                    LayoutLine summary = result.size() > frameFirstLine ? result.get(frameFirstLine) : null;
                    result.subList(frameFirstLine, result.size()).clear();
                    if (summary != null) result.add(new LayoutLine(frameStart, summary.startColumn(), summary.endColumn(),
                            frameY + 7, summary.height(), summary.indent(), false, summary.fragments()));
                    renderedImages.subList(frameFirstImage, renderedImages.size()).clear();
                    renderedTables.subList(frameFirstTable, renderedTables.size()).clear();
                    y = frameY + (summary == null ? 14 : summary.height()) + 12;
                }
                // Images from this frame must never wrap unrelated text outside it.
                wrappingImages.subList(frameFirstExclusion, wrappingImages.size()).clear();
                renderedFrames.add(new FrameLayout(frameStart, frameEnd, frameY, y - frameY, frame, foldable));
                y += 7;
            }
        }
        layoutLines = List.copyOf(result);
        imageLayouts = List.copyOf(renderedImages);
        tableLayouts = List.copyOf(renderedTables);
        frameLayouts = List.copyOf(renderedFrames);
        int overlayBottom = 0;
        for (ImageLayout image : imageLayouts) {
            overlayBottom = Math.max(overlayBottom, image.y() + image.height());
        }
        contentHeight = Math.max(y, overlayBottom);
    }

    private TextFlowLayout.Result flowText(List<TextRun> runs, int width, int indent, float scale,
            int y, int gap, List<TextFlowExclusion> exclusions) {
        return TextFlowLayout.layout(runs, Math.max(indent + 1, width), indent, scale, y, gap,
                exclusions.stream().map(e -> new TextFlowLayout.Exclusion(
                        e.gapStart(), e.topY(), e.gapEnd(), e.bottomY())).toList(),
                new TextFlowLayout.Metrics() {
                    @Override
                    public float advance(String text, TextStyle style, float fontScale) {
                        return font.width(styled(text, style)) * fontScale;
                    }

                    @Override
                    public int height(float fontScale) {
                        return Math.max(1, (int) Math.ceil(font.lineHeight * fontScale));
                    }
                });
    }

    private static List<Fragment> fragments(TextFlowLayout.Line line) {
        return line.glyphs().stream().map(g -> new Fragment(g.text(), g.style(), g.scale(),
                g.link(), g.start(), g.end(), g.x(), g.advance(), styled(g.text(), g.style()))).toList();
    }

    private int appendTextBlock(List<LayoutLine> result, int y, int blockIndex, List<TextRun> runs, int totalWidth,
            int indent, boolean quote, float blockScale, boolean heading, List<TextFlowExclusion> flowExclusions) {
        TextFlowLayout.Result layout = flowText(runs, totalWidth - (quote ? 7 : 0) - (canFold(blockIndex) ? 12 : 0),
                indent, blockScale, y, 2, flowExclusions);
        for (TextFlowLayout.Line line : layout.lines()) {
            result.add(new LayoutLine(blockIndex, line.start(), line.end(), line.y(), line.height(),
                    indent, quote, fragments(line)));
        }
        return layout.bottom() + (heading ? 5 : 4);
    }

    private int appendRichBlock(List<LayoutLine> result, int y, int index, RichTextBlock rich, int width,
            List<TextFlowExclusion> exclusions) {
        if (canFold(index)) width -= 12;
        int indent = switch(rich.kind()) {
            case BULLET, NUMBERED -> 24; case TASK -> 18; case QUOTE_TASK -> 24; case QUOTE, CODE -> 8;
            case INFO, WARNING -> 14; case STEP -> 42; default -> 0;
        };
        if (rich.frame() != null) {
            indent = frameInset(rich.frame()) + switch (rich.kind()) {
                case TASK, QUOTE_TASK -> 14;
                case BULLET, NUMBERED -> 24;
                case STEP -> 42;
                default -> 0;
            };
            width -= 8;
        }
        if (rich.isList()) indent = frameInset(rich.frame()) + listIndent(index, rich.kind());
        float scale = rich.headingLevel() > 0 ? headingScale(rich.headingLevel()) : 1f;
        if ((rich.isTask() || rich.isList()) && !exclusions.isEmpty()) {
            // Wrapped pictures must avoid the checkbox/list marker as well as its text.
            int markerGutter = rich.isTask() ? 14 : font.width(listMarkerLabel(index, rich.kind())) + 4;
            exclusions = exclusions.stream().map(e -> new TextFlowExclusion(e.topY(), e.bottomY(), e.gapStart(), e.gapEnd() + markerGutter)).toList();
            if (WikiDocumentEditor.blockText(rich).isEmpty()) y = advancePastWrappingImages(y, exclusions);
        }
        TextFlowLayout.Result flow = flowText(rich.runs(), width - 4, indent, scale, y + 3, 2, exclusions);
        for (int i = 0; i < flow.lines().size(); i++) {
            TextFlowLayout.Line line = flow.lines().get(i);
            boolean last = i == flow.lines().size() - 1 || (line.end() < WikiDocumentEditor.blockText(rich).length()
                    && WikiDocumentEditor.blockText(rich).charAt(line.end()) == '\n');
            List<Fragment> fs = fragments(line);
            float end = fs.isEmpty() ? indent : fs.getLast().x()+fs.getLast().advance();
            float room = Math.max(0, width - 4 - end);
            float shift = switch(rich.alignment()) { case CENTER -> room/2; case RIGHT -> room; default -> 0; };
            int gaps = 0;
            if(rich.alignment() == RichTextBlock.Alignment.JUSTIFY && !last) {
                for(int n=0;n<fs.size()-1;n++) if(fs.get(n).text().isBlank()) gaps++;
            }
            boolean distribute = rich.alignment() == RichTextBlock.Alignment.JUSTIFY && !last;
            boolean every = gaps == 0;
            if(distribute && every) gaps = Math.max(0,fs.size()-1);
            List<Fragment> aligned = new ArrayList<>();
            float extra = shift;
            for(int n=0;n<fs.size();n++) {
                Fragment f=fs.get(n);
                float advance=f.advance();
                if(distribute && gaps>0 && n<fs.size()-1 && (every || f.text().isBlank())) advance += room/gaps;
                aligned.add(new Fragment(f.text(),f.style(),f.scale(),f.link(),f.startColumn(),f.endColumn(),f.x()+extra,advance,f.painted()));
                extra += advance-f.advance();
            }
            result.add(new LayoutLine(index,line.start(),line.end(),line.y(),line.height(),Math.round(indent+shift),false,aligned));
        }
        boolean continuesQuote = rich.isQuote() && index + 1 < editor.document().blocks().size()
                && editor.document().blocks().get(index + 1) instanceof TextualBlock following
                && RichTextBlock.from(following).isQuote();
        return flow.bottom() + (rich.frame() != null || continuesQuote ? 3 : 7);
    }
    private boolean canFold(int index) {
        return index >= 0 && index < editor.document().blocks().size()
                && editor.document().blocks().get(index).frame() == null
                && editor.document().blocks().get(index) instanceof TextualBlock text
                && !(text instanceof RichTextBlock rich && rich.isTask())
                && WikiDocumentEditor.blockText(text).contains("\n");
    }

    private void drawBlockFoldControls(GuiGraphics g, Bounds b) {
        for (FrameLayout frame : frameLayouts) {
            if (!frame.foldable()) continue;
            ContentControl control = frameFoldControl(b, frame);
            g.drawString(font, collapsedBlocks.contains(frame.start()) ? ">" : "v",
                    control.x() + 2, control.y(), BookEditorChrome.ACCENT, false);
        }
        for (LayoutLine line : layoutLines) {
            if (line.startColumn() != 0 || !canFold(line.blockIndex())) continue;
            int y = b.bodyTop() + line.y() - bodyScroll;
            if (y < b.bodyTop() || y >= b.footerY()) continue;
            ContentControl control = foldControl(b, line);
            g.drawString(font, collapsedBlocks.contains(line.blockIndex()) ? ">" : "v",
                    control.x() + 2, control.y(), BookEditorChrome.ACCENT, false);
        }
    }

    private boolean handleBlockFoldClick(Bounds b, double x, double y) {
        if (x < b.bodyX() + b.bodyVisibleWidth() - 12 || x >= b.bodyX() + b.bodyVisibleWidth()
                || y < b.bodyTop() || y >= b.footerY()) return false;
        ensureLayout(b.bodyWidth());
        for (FrameLayout frame : frameLayouts) {
            if (!frame.foldable() || !frameFoldControl(b, frame).contains(x, y)) continue;
            editor.finishTextEdit(); clearSelection();
            if (!collapsedBlocks.add(frame.start())) collapsedBlocks.remove(frame.start());
            invalidateLayout(); ensureLayout(b.bodyWidth());
            bodyScroll = clamp(bodyScroll, 0, maxBodyScroll(b));
            return true;
        }
        for (LayoutLine line : layoutLines) {
            if (line.startColumn() == 0 && canFold(line.blockIndex()) && foldControl(b, line).contains(x, y)) {
                editor.finishTextEdit();
                clearSelection();
                if (!collapsedBlocks.add(line.blockIndex())) collapsedBlocks.remove(line.blockIndex());
                invalidateLayout();
                ensureLayout(b.bodyWidth());
                bodyScroll = clamp(bodyScroll, 0, maxBodyScroll(b));
                return true;
            }
        }
        return false;
    }

    private ContentControl foldControl(Bounds b, LayoutLine line) {
        return new ContentControl(b.bodyX() + b.bodyVisibleWidth() - 12, b.bodyTop() + line.y() - bodyScroll, 12, 12);
    }

    private record FrameLayout(int start, int end, int y, int height, BlockFrame frame, boolean foldable) { }

    private static int frameInset(BlockFrame frame) {
        return frame == null ? 0 : frame.kind() == BlockFrame.Kind.QUOTE ? 8 : 14;
    }

    private ContentControl frameFoldControl(Bounds b, FrameLayout frame) {
        return new ContentControl(b.bodyX() + b.bodyVisibleWidth() - 12, b.bodyTop() + frame.y() + 4 - bodyScroll, 12, 12);
    }

    private void drawFrame(GuiGraphics g, Bounds b, FrameLayout frame) {
        int y = b.bodyTop() + frame.y() - bodyScroll, x = b.bodyX(), bottom = y + frame.height();
        if (bottom < b.bodyTop() || y >= b.footerY()) return;
        int color = switch (frame.frame().kind()) {
            case QUOTE -> 0xFFE9EEF3; case INFO -> 0xFFE5F0FB; case WARNING -> 0xFFFFF0D0;
        };
        g.fill(x, y, x + b.bodyWidth(), bottom, color);
        g.fill(x, y, x + 2, bottom, frame.frame().kind() == BlockFrame.Kind.WARNING ? 0xFFBA7B19 : 0xFF5F83A6);
        String label = switch (frame.frame().kind()) { case INFO -> "i"; case WARNING -> "!"; default -> ""; };
        if (!label.isEmpty()) g.drawString(font, label, x + 4 + (8 - font.width(label)) / 2, y + 7, 0xFF456783, false);
    }

    private int blockNumber(int index, RichTextBlock.Kind kind) {
        int count=1;
        BlockFrame frame = editor.document().blocks().get(index).frame();
        for(int i=index-1;i>=0;i--) {
            Block block=editor.document().blocks().get(i);
            if(block instanceof RichTextBlock r && r.kind()==kind && Objects.equals(frame, r.frame())) {count++;continue;}
            break;
        }
        return count;
    }

    private String listMarkerLabel(int index, RichTextBlock.Kind kind) {
        return kind == RichTextBlock.Kind.BULLET ? "•" : blockNumber(index, kind) + ".";
    }

    private int listIndent(int index, RichTextBlock.Kind kind) {
        if (kind == RichTextBlock.Kind.BULLET) return 24;
        int finalNumber = blockNumber(index, kind);
        BlockFrame frame = editor.document().blocks().get(index).frame();
        for (int i = index + 1; i < editor.document().blocks().size(); i++) {
            Block next = editor.document().blocks().get(i);
            if (!(next instanceof RichTextBlock rich) || rich.kind() != kind || !Objects.equals(frame, rich.frame())) break;
            finalNumber++;
        }
        // Every item shares the width needed by the longest number, including 100 and beyond.
        return Math.max(24, font.width(finalNumber + ".") + 8);
    }

    private ContentControl listMarkerBox(LayoutLine line) {
        RichTextBlock rich = (RichTextBlock) editor.document().blocks().get(line.blockIndex());
        int markerWidth = font.width(listMarkerLabel(line.blockIndex(), rich.kind()));
        float textX = line.fragments().isEmpty() ? line.indent() : line.fragments().getFirst().x();
        return new ContentControl(Math.round(textX) - markerWidth - 4,
                line.y() + Math.max(0, (line.height() - font.lineHeight) / 2), markerWidth, font.lineHeight);
    }
    private void drawRichDecoration(GuiGraphics g, Bounds b, LayoutLine line, int y) {
        if(line.blockIndex()<0) return;
        Block block=editor.document().blocks().get(line.blockIndex());
        int x=b.bodyX(), right=x+b.bodyWidth();
        if(block instanceof HorizontalRule) { g.fill(x+4,y+5,right-4,y+6,0xFF9AABBD);return; }
        if(!(block instanceof RichTextBlock r)) return;
        boolean first=line.startColumn()==0;
        int background=switch(r.kind()) {
            case QUOTE, QUOTE_TASK -> 0xFFE9EEF3; case CODE -> 0xFFE9EDF0; case INFO -> 0xFFE5F0FB;
            case WARNING -> 0xFFFFF0D0; case STEP -> 0xFFF0F3F8; default -> 0;
        };
        if(background!=0 && r.frame() == null) g.fill(x,y-3,right,y+line.height()+3,background);
        if(r.frame() == null && (r.isQuote() || r.kind()==RichTextBlock.Kind.INFO || r.kind()==RichTextBlock.Kind.WARNING))
            g.fill(x,y-3,x+2,y+line.height()+3,r.kind()==RichTextBlock.Kind.WARNING ? 0xFFBA7B19:0xFF5F83A6);
        if(first) {
            String label=switch(r.kind()) {
                case BULLET -> "•"; case NUMBERED -> blockNumber(line.blockIndex(),r.kind())+".";
                case INFO -> "i"; case WARNING -> "!";
                case STEP -> "步骤 "+blockNumber(line.blockIndex(),r.kind()); default -> "";
            };
            if(r.isTask()) {
                ContentControl box = taskBox(line);
                int bx = x + box.x(), by = b.bodyTop() + box.y() - bodyScroll;
                g.fill(bx,by,bx+10,by+10,0xFF3E665A);
                g.fill(bx+1,by+1,bx+9,by+9,r.checked()?0xFF3E665A:0xFFFFFCF2);
                // Pixel checkmark, independent of missing or misaligned Unicode font glyphs.
                if(r.checked()) {
                    g.fill(bx+2,by+4,bx+4,by+6,0xFFFFFFFF);
                    g.fill(bx+3,by+5,bx+5,by+7,0xFFFFFFFF);
                    g.fill(bx+4,by+4,bx+6,by+6,0xFFFFFFFF);
                    g.fill(bx+5,by+3,bx+7,by+5,0xFFFFFFFF);
                    g.fill(bx+6,by+2,bx+8,by+4,0xFFFFFFFF);
                }
            } else if (r.isList()) {
                ContentControl marker = listMarkerBox(line);
                g.drawString(font, label, x + marker.x(), b.bodyTop() + marker.y() - bodyScroll, 0xFF456783, false);
            } else {
                int labelY = y + Math.max(0, (line.height() - font.lineHeight) / 2);
                int labelX = x + frameInset(r.frame()) + 4;
                if (r.kind()==RichTextBlock.Kind.INFO || r.kind()==RichTextBlock.Kind.WARNING)
                    labelX = x + 4 + (8 - font.width(label)) / 2;
                if (r.frame() == null || r.kind() != RichTextBlock.Kind.INFO && r.kind() != RichTextBlock.Kind.WARNING)
                    g.drawString(font,label,labelX,labelY,0xFF456783,false);
            }
        }
    }

    private record ContentControl(int x, int y, int width, int height) {
        boolean contains(double px, double py) {
            return px >= x && px < x + width && py >= y && py < y + height;
        }
    }

    /** Document-local rectangle used by BOTH checkbox drawing and hit testing. */
    private ContentControl taskBox(LayoutLine line) {
        float textX = line.fragments().isEmpty() ? line.indent() : line.fragments().getFirst().x();
        return new ContentControl(Math.round(textX) - 14, line.y() + Math.max(0, (line.height() - 10) / 2), 10, 10);
    }

    private static int advancePastWrappingImages(int y, List<TextFlowExclusion> flowExclusions) {
        boolean advanced;
        do {
            advanced = false;
            for (TextFlowExclusion exclusion : flowExclusions) {
                if (y >= exclusion.topY() && y < exclusion.bottomY()) {
                    y = exclusion.bottomY();
                    advanced = true;
                }
            }
        } while (advanced);
        return y;
    }

    private int appendTable(List<TableLayout> result, int y, int blockIndex, TableBlock table, int bodyWidth,
            List<TextFlowExclusion> exclusions) {
        int xOffset = frameInset(table.frame());
        if (table.frame() != null) bodyWidth -= xOffset + 12;
        int tableWidth = Math.max(24, bodyWidth);
        int columns = table.columnCount();
        int baseColumnWidth = Math.max(1, tableWidth / columns);
        List<TableRowLayout> rows = new ArrayList<>(table.rows().size());
        int tableHeight = 0;
        for (TableRow row : table.rows()) {
            List<TableCellLayout> cells = new ArrayList<>(columns);
            int rowHeight = TABLE_ROW_HEIGHT;
            for (int column = 0; column < columns; column++) {
                int cellWidth = column == columns - 1
                        ? tableWidth - column * baseColumnWidth : baseColumnWidth;
                TableCellLayout cell = layoutTableCell(row.cells().get(column),
                        Math.max(1, cellWidth - TABLE_CELL_PADDING * 2));
                cells.add(cell);
                rowHeight = Math.max(rowHeight, cell.contentHeight() + TABLE_CELL_PADDING * 2);
            }
            rows.add(new TableRowLayout(tableHeight, rowHeight, List.copyOf(cells)));
            tableHeight += rowHeight;
        }
        if (table.frame() != null) {
            boolean advanced;
            do {
                advanced = false;
                for (TextFlowExclusion e : exclusions) if (y < e.bottomY() && y + tableHeight > e.topY()) {
                    y = e.bottomY(); advanced = true;
                }
            } while (advanced);
        }
        result.add(new TableLayout(blockIndex, y, xOffset, tableWidth, baseColumnWidth, columns, tableHeight,
                List.copyOf(rows), table));
        return y + tableHeight + 8;
    }

    /** Lays out every cell with the same glyph measurements used by regular text. */
    private TableCellLayout layoutTableCell(TableCell cell, int availableWidth) {
        TextFlowLayout.Result layout = flowText(cell.runs(), availableWidth, 0, 1f, 0, 1, List.of());
        return new TableCellLayout(layout.lines().stream().map(line -> new TableCellLine(
                line.y(), line.height(), line.start(), line.end(), fragments(line))).toList(), layout.bottom());
    }

    private ImageAppend appendImage(List<ImageLayout> result, int y, int blockIndex, ImageBlock image, int bodyWidth) {
        return appendImage(result, y, blockIndex, image, bodyWidth, 0, 0);
    }

    private ImageAppend appendImage(List<ImageLayout> result, int y, int blockIndex, ImageBlock image, int bodyWidth,
            int originX, int originY) {
        WikiImageTextures.LoadedImage loaded = WikiImageTextures.get(image.assetHash()).orElse(null);
        if (loaded == null) {
            int width = Math.min(120, bodyWidth);
            boolean inline = image.layoutMode() == ImageLayoutMode.INLINE;
            int xOffset = inline ? 0 : clamp(toGuiCoordinate(image.offsetX()), 0, Math.max(0, bodyWidth - width));
            xOffset += originX;
            int imageY = inline ? y : originY + Math.max(0, toGuiCoordinate(image.offsetY()));
            result.add(new ImageLayout(blockIndex, imageY, xOffset, width, 32, image.alignment(), image.layoutMode(), null));
            return inline
                    ? new ImageAppend(y + 40, null)
                    : new ImageAppend(y, null);
        }
        // ImageBlock stores physical display pixels, not Minecraft GUI units.
        // Without this conversion a 300px source at GUI scale 4 is painted
        // as 1200px, causing the "large but blurry" result. Text and image
        // layout are intentionally independent: a picture begins at its own
        // pixel size and is only reduced when it would exceed this column.
        int physicalWidth = image.requestedWidth() == 0 ? loaded.width() : image.requestedWidth();
        int maximumWidth = switch (image.layoutMode()) {
            case WRAP_LEFT, WRAP_RIGHT -> Math.max(32, bodyWidth - 30);
            default -> bodyWidth;
        };
        int width = clamp(toGuiPixels(physicalWidth), 1, maximumWidth);
        int height = Math.max(1, Math.round((float) loaded.height() * width / loaded.width()));
        int xOffset = switch (image.alignment()) {
            case LEFT -> 0;
            case CENTER -> Math.max(0, (bodyWidth - width) / 2);
            case RIGHT -> Math.max(0, bodyWidth - width);
        };
        int imageY = y;
        if (image.layoutMode() != ImageLayoutMode.INLINE) {
            // Non-inline images use fixed coordinates on the authored page.
            // They therefore remain where the author placed them when text or
            // a new paragraph is inserted earlier in the document.
            xOffset = clamp(toGuiCoordinate(image.offsetX()), 0, Math.max(0, bodyWidth - width));
            imageY = originY + Math.max(0, toGuiCoordinate(image.offsetY()));
        }
        xOffset += originX;
        if (imageResize != null && imageResize.blockIndex() == blockIndex) {
            width = Math.min(maximumWidth, imageResize.previewWidth());
            height = Math.max(1, Math.round((float) loaded.height() * width / loaded.width()));
            if (image.layoutMode() != ImageLayoutMode.INLINE) {
                xOffset = clamp(imageResize.previewX(), originX, originX + Math.max(0, bodyWidth - width));
                imageY = Math.max(originY, imageResize.previewY());
            } else {
                xOffset = originX + switch (image.alignment()) {
                    case LEFT -> 0;
                    case CENTER -> Math.max(0, (bodyWidth - width) / 2);
                    case RIGHT -> Math.max(0, bodyWidth - width);
                };
            }
        }
        if (imageMove != null && imageMove.blockIndex() == blockIndex) {
            xOffset = clamp(imageMove.startImageX() + imageMove.deltaX(), originX, originX + Math.max(0, bodyWidth - width));
            imageY = Math.max(originY, imageMove.startImageY() + imageMove.deltaY());
        }
        result.add(new ImageLayout(blockIndex, imageY, xOffset, width, height, image.alignment(), image.layoutMode(), loaded));
        return switch (image.layoutMode()) {
            case WRAP_LEFT, WRAP_RIGHT -> new ImageAppend(y,
                    new TextFlowExclusion(Math.max(0, imageY - 4), imageY + height + 4, Math.max(0, xOffset - 4), xOffset + width + 4));
            case COVER, FLOATING -> new ImageAppend(y, null);
            case INLINE -> new ImageAppend(y + height + 8, null);
        };
    }

    private void drawFragments(GuiGraphics graphics, LayoutLine line, int bodyX, int y) {
        int cursorX = bodyX + line.indent();
        boolean caretLine = line == caretVisualLine();
        for (Fragment fragment : line.fragments()) {
            Component text = fragment.painted();
            int characterWidth = measuredFragmentWidth(fragment);
            cursorX = bodyX + Math.round(fragment.x());
            int annotationIndex = annotationForFragment(line, fragment);
            if (annotationIndex >= 0) {
                graphics.fill(cursorX, y - 1, cursorX + characterWidth, y + line.height(),
                        annotationIndex == selectedAnnotationIndex
                                ? ACTIVE_ANNOTATION_HIGHLIGHT_COLOR : ANNOTATION_HIGHLIGHT_COLOR);
            }
            if (isFragmentSelected(line, fragment)) {
                graphics.fill(cursorX, y - 1, cursorX + characterWidth, y + line.height(), SELECTION_COLOR);
            }
            if (caretLine && isCaretAt(line.blockIndex(), fragment.startColumn())) {
                drawCaret(graphics, cursorX, y, line.height());
            }
            List<TextRun> runs = line.blockIndex() < 0 ? List.of() : editor.document().blocks().get(line.blockIndex()).runs();
            paintFragment(graphics, fragment, bodyX, y, line.height(), spoilerKey(line.blockIndex(), "", fragment.startColumn(), runs));
            cursorX += characterWidth;
        }
        if (caretLine && isCaretAt(line.blockIndex(), line.endColumn())) {
            drawCaret(graphics, cursorX, y, line.height());
        }
    }

    private void paintFragment(GuiGraphics g, Fragment f, int originX, int y, int lineHeight, String spoiler) {
        int x=originX+Math.round(f.x()), right=originX+Math.round(f.x()+f.advance());
        if(f.style().code() || f.style().hidden()) g.fill(x,y-1,right,y+lineHeight,
                f.style().hidden()?0xFFE1DDEC:0xFFDDE5ED);
        if(f.style().hidden() && !mode.isEditable() && !revealedSpoilers.contains(spoiler)) {
            g.fill(x,y,right,y+lineHeight-1,0xFF626B7A); return;
        }
        int glyphHeight=(int)Math.ceil(font.lineHeight*f.scale());
        int baselineOffset=f.style().script()>0 ? 0 : lineHeight-glyphHeight;
        g.pose().pushPose();g.pose().translate(originX+f.x(),y+baselineOffset,0);
        g.pose().scale(f.scale(),f.scale(),1f);
        g.drawString(font,f.painted(),0,0,0xFF000000|f.style().rgbColor(),false);
        g.pose().popPose();
        if(f.link()) g.fill(x,y+lineHeight,right,y+lineHeight+1,0xFF426D9E);
        if(f.style().hidden() && mode.isEditable()) g.fill(x,y+lineHeight-1,right,y+lineHeight,0xFF8F79AD);
    }

    private int annotationForFragment(LayoutLine line, Fragment fragment) {
        for (int index = 0; index < annotations.size(); index++) {
            LocalTextAnnotation annotation = annotations.get(index);
            if (line.blockIndex() < annotation.startBlock() || line.blockIndex() > annotation.endBlock()) {
                continue;
            }
            int start = line.blockIndex() == annotation.startBlock() ? annotation.startColumn() : 0;
            int end = line.blockIndex() == annotation.endBlock()
                    ? annotation.endColumn()
                    : WikiDocumentEditor.blockText(editor.document().blocks().get(line.blockIndex())).length();
            if (fragment.startColumn() < end && fragment.endColumn() > start) {
                return index;
            }
        }
        return -1;
    }

    private static Component styled(String text, TextStyle style) {
        return Component.literal(text).withStyle(Style.EMPTY
                .withBold(style.bold())
                .withItalic(style.italic())
                .withStrikethrough(style.strikethrough())
                .withUnderlined(style.underline())
                .withColor(style.rgbColor()));
    }

    private int measuredFragmentWidth(Fragment fragment) {
        // Round the boundaries, never each advance: cumulative rounding caused
        // visible drift between small/large text, selection and caret placement.
        return Math.max(0, Math.round(fragment.x() + fragment.advance()) - Math.round(fragment.x()));
    }

    private static float headingScale(int level) {
        return switch (level) {
            case 1 -> 1.52f;
            case 2 -> 1.28f;
            default -> 1.12f;
        };
    }

    private static int headingIndent(int level) {
        return switch (level) {
            case 1 -> 0;
            case 2 -> 4;
            default -> 8;
        };
    }

    private void drawTableContextMenu(GuiGraphics graphics, Bounds bounds, int mouseX, int mouseY) {
        if (tableContextMenu == null || !mode.isEditable()) return;
        int x = tableContextX(bounds);
        int y = tableContextY(bounds);
        String[] labels = {"删除此行", "删除此列", "删除表格"};
        for (String label : labels) {
            drawImageContextRow(graphics, x, y, label,
                    inRect(mouseX, mouseY, x, y, IMAGE_CONTEXT_WIDTH, IMAGE_CONTEXT_ROW_HEIGHT));
            y += IMAGE_CONTEXT_ROW_HEIGHT;
        }
    }

    private boolean handleTableContextMenuClick(Bounds bounds, double mouseX, double mouseY) {
        if (!inRect(mouseX, mouseY, tableContextX(bounds), tableContextY(bounds),
                IMAGE_CONTEXT_WIDTH, IMAGE_CONTEXT_ROW_HEIGHT * 3)) return false;
        int action = (int) (mouseY - tableContextY(bounds)) / IMAGE_CONTEXT_ROW_HEIGHT;
        TableContextMenu target = tableContextMenu;
        tableContextMenu = null;
        if (!mode.isEditable()) return true;
        boolean changed = switch (action) {
            case 0 -> editor.deleteTableRow(target.blockIndex(), target.row());
            case 1 -> editor.deleteTableColumn(target.blockIndex(), target.column());
            default -> editor.deleteTableBlock(target.blockIndex());
        };
        if (changed) {
            clearSelection();
            refreshAfterEdit();
            status("已删除，可按 Ctrl+Z 撤销");
        }
        return true;
    }

    private int tableContextX(Bounds bounds) {
        return clamp(tableContextMenu.x(), bounds.left() + 2,
                Math.max(bounds.left() + 2, bounds.right() - IMAGE_CONTEXT_WIDTH - 2));
    }

    private int tableContextY(Bounds bounds) {
        return clamp(tableContextMenu.y(), bounds.top() + 2,
                Math.max(bounds.top() + 2, bounds.bottom() - IMAGE_CONTEXT_ROW_HEIGHT * 3 - 2));
    }

    /** Compact right-click image menu; choices are document data, not UI-only state. */
    private void drawImageContextMenu(GuiGraphics graphics, Bounds bounds, int mouseX, int mouseY) {
        if (imageContextMenu == null) {
            return;
        }
        int x = imageContextX(bounds);
        int y = imageContextY(bounds);
        for (int index = 0; index < IMAGE_LAYOUT_CHOICES.length; index++) {
            int rowY = y + index * IMAGE_CONTEXT_ROW_HEIGHT;
            boolean hovered = inRect(mouseX, mouseY, x, rowY, IMAGE_CONTEXT_WIDTH, IMAGE_CONTEXT_ROW_HEIGHT);
            graphics.fill(x, rowY, x + IMAGE_CONTEXT_WIDTH, rowY + IMAGE_CONTEXT_ROW_HEIGHT,
                    hovered ? BUTTON_HOVER_COLOR : BUTTON_COLOR);
            graphics.drawString(font, imageLayoutLabel(IMAGE_LAYOUT_CHOICES[index]), x + 5, rowY + 5, 0xFFFFFFFF, false);
        }
    }

    private void drawImageContextRow(GuiGraphics graphics, int x, int y, String label, boolean hovered) {
        graphics.fill(x, y, x + IMAGE_CONTEXT_WIDTH, y + IMAGE_CONTEXT_ROW_HEIGHT,
                hovered ? BUTTON_HOVER_COLOR : BUTTON_COLOR);
        graphics.drawString(font, label, x + 5, y + 5, 0xFFFFFFFF, false);
    }

    private boolean handleImageContextMenuClick(Bounds bounds, double mouseX, double mouseY) {
        int x = imageContextX(bounds);
        int y = imageContextY(bounds);
        for (ImageLayoutMode mode : IMAGE_LAYOUT_CHOICES) {
            if (inRect(mouseX, mouseY, x, y, IMAGE_CONTEXT_WIDTH, IMAGE_CONTEXT_ROW_HEIGHT)) {
                int blockIndex = imageContextMenu.blockIndex();
                imageContextMenu = null;
                return setImageLayoutMode(blockIndex, mode);
            }
            y += IMAGE_CONTEXT_ROW_HEIGHT;
        }
        return false;
    }

    private int imageContextX(Bounds bounds) {
        return clamp(imageContextMenu.x(), bounds.left() + 2,
                Math.max(bounds.left() + 2, bounds.right() - IMAGE_CONTEXT_WIDTH - 2));
    }

    private int imageContextY(Bounds bounds) {
        int contextHeight = IMAGE_CONTEXT_ROW_HEIGHT * IMAGE_LAYOUT_CHOICES.length;
        return clamp(imageContextMenu.y(), bounds.top() + 2,
                Math.max(bounds.top() + 2, bounds.bottom() - contextHeight - 2));
    }

    private boolean setImageLayoutMode(int blockIndex, ImageLayoutMode mode) {
        if (blockIndex < 0 || blockIndex >= editor.document().blocks().size()
                || !(editor.document().blocks().get(blockIndex) instanceof ImageBlock image)) {
            status("图片选择已失效，请重新点击图片");
            return true;
        }
        Bounds bounds = bounds();
        ensureLayout(bounds.bodyWidth());
        ImageLayout displayed = imageLayout(blockIndex);
        int offsetX = image.offsetX();
        int offsetY = image.offsetY();
        if (mode != ImageLayoutMode.INLINE && image.layoutMode() == ImageLayoutMode.INLINE && displayed != null) {
            // Freeze the picture at its current visible position when it
            // leaves the text flow; subsequent preceding text edits cannot
            // silently pull the image down the page.
            offsetX = toPhysicalCoordinate(displayed.xOffset() - frameInset(image.frame()));
            offsetY = toPhysicalCoordinate(displayed.y() - imageOriginY(blockIndex));
        }
        editor.finishTextEdit();
        editor.updateImageBlock(blockIndex, new ImageBlock(
                image.assetHash(), image.caption(), image.alignment(), image.requestedWidth(),
                mode, offsetX, offsetY));
        selectedImageBlock = blockIndex;
        refreshAfterEdit();
        status("图片排版已设为" + imageLayoutLabel(mode));
        return true;
    }

    private ImageLayout imageLayout(int blockIndex) {
        for (ImageLayout layout : imageLayouts) {
            if (layout.blockIndex() == blockIndex) {
                return layout;
            }
        }
        return null;
    }

    private int imageOriginY(int blockIndex) {
        for (FrameLayout frame : frameLayouts) if (blockIndex >= frame.start() && blockIndex <= frame.end()) return frame.y() + 4;
        return 0;
    }

    private int imageColumnWidth(ImageBlock image) {
        return bounds().bodyWidth() - (image.frame() == null ? 0 : frameInset(image.frame()) + 12);
    }

    private static String imageLayoutLabel(ImageLayoutMode mode) {
        return switch (mode) {
            case INLINE -> "行内";
            case WRAP_LEFT -> "环绕";
            case WRAP_RIGHT -> "环绕（旧）";
            case COVER -> "覆盖";
            case FLOATING -> "浮动";
        };
    }

    private void drawImageSelection(GuiGraphics graphics, int x, int y, int imageWidth, int imageHeight) {
        int right = x + imageWidth;
        int bottom = y + imageHeight;
        graphics.fill(x, y, right, y + 1, LINK_COLOR);
        graphics.fill(x, bottom - 1, right, bottom, LINK_COLOR);
        graphics.fill(x, y, x + 1, bottom, LINK_COLOR);
        graphics.fill(right - 1, y, right, bottom, LINK_COLOR);
        drawImageHandle(graphics, x, y);
        drawImageHandle(graphics, right - 3, y);
        drawImageHandle(graphics, x, bottom - 3);
        drawImageHandle(graphics, right - 3, bottom - 3);
    }

    private static void drawImageHandle(GuiGraphics graphics, int x, int y) {
        graphics.fill(x, y, x + 3, y + 3, 0xFFFDF8EC);
        graphics.fill(x, y, x + 3, y + 1, LINK_COLOR);
        graphics.fill(x, y + 2, x + 3, y + 3, LINK_COLOR);
        graphics.fill(x, y, x + 1, y + 3, LINK_COLOR);
        graphics.fill(x + 2, y, x + 3, y + 3, LINK_COLOR);
    }

    private ImageVisual imageVisual(ImageLayout image) {
        // Preview geometry is already resolved by ensureLayout, including container insets.
        return new ImageVisual(image.blockIndex(), image.y(), image.xOffset(), image.width(), image.height());
    }

    private ResizeHandle resizeHandleAt(Bounds bounds, ImageLayout image, double mouseX, double mouseY) {
        ImageVisual visual = imageVisual(image);
        int x = bounds.bodyX() + visual.xOffset();
        int y = bounds.bodyTop() + visual.y() - bodyScroll;
        boolean left = mouseX >= x - 3 && mouseX <= x + 5;
        boolean right = mouseX >= x + visual.width() - 5 && mouseX <= x + visual.width() + 3;
        boolean top = mouseY >= y - 3 && mouseY <= y + 5;
        boolean bottom = mouseY >= y + visual.height() - 5 && mouseY <= y + visual.height() + 3;
        if (left && top) {
            return ResizeHandle.NORTH_WEST;
        }
        if (right && top) {
            return ResizeHandle.NORTH_EAST;
        }
        if (left && bottom) {
            return ResizeHandle.SOUTH_WEST;
        }
        if (right && bottom) {
            return ResizeHandle.SOUTH_EAST;
        }
        return null;
    }

    private void updateImageResize(int mouseX, int mouseY) {
        if (imageResize == null) {
            return;
        }
        ImageBlock image = (ImageBlock) editor.document().blocks().get(imageResize.blockIndex());
        int originX = frameInset(image.frame()), originY = imageOriginY(imageResize.blockIndex());
        int maximum = Math.max(1, imageColumnWidth(image));
        if (image.layoutMode() == ImageLayoutMode.WRAP_LEFT || image.layoutMode() == ImageLayoutMode.WRAP_RIGHT)
            maximum = Math.max(32, maximum - 30);
        if (image.layoutMode() != ImageLayoutMode.INLINE) {
            int horizontalLimit = imageResize.handle().left()
                    ? imageResize.startImageX() + imageResize.startWidth() - originX
                    : originX + imageColumnWidth(image) - imageResize.startImageX();
            maximum = Math.min(maximum, Math.max(1, horizontalLimit));
            if (imageResize.handle().top()) {
                int verticalLimit = (int) ((long) (imageResize.startImageY() + imageResize.startHeight() - originY)
                        * imageResize.startWidth() / imageResize.startHeight());
                maximum = Math.min(maximum, Math.max(1, verticalLimit));
            }
        }
        imageResize = imageResize.withPreviewWidth(ImageResizeGeometry.width(
                imageResize.startWidth(), imageResize.startHeight(),
                mouseX - imageResize.startX(), mouseY - imageResize.startY(),
                imageResize.handle().left(), imageResize.handle().top(), maximum));
        invalidateLayout();
    }

    private void finishImageResize() {
        ImageResize resize = imageResize;
        imageResize = null;
        if (resize == null || resize.previewWidth() == resize.startWidth()
                || resize.blockIndex() < 0 || resize.blockIndex() >= editor.document().blocks().size()
                || !(editor.document().blocks().get(resize.blockIndex()) instanceof ImageBlock image)) {
            return;
        }
        editor.finishTextEdit();
        editor.updateImageBlock(resize.blockIndex(), new ImageBlock(
                image.assetHash(), image.caption(), image.alignment(), toPhysicalPixels(resize.previewWidth()),
                image.layoutMode(),
                image.layoutMode() == ImageLayoutMode.INLINE ? image.offsetX()
                        : clamp(toPhysicalCoordinate(resize.previewX() - frameInset(image.frame())), 0, 16_384),
                image.layoutMode() == ImageLayoutMode.INLINE ? image.offsetY()
                        : clamp(toPhysicalCoordinate(resize.previewY() - imageOriginY(resize.blockIndex())), 0, 16_384)));
        refreshAfterEdit();
    }

    private void finishImageMove(int mouseX, int mouseY) {
        ImageMove move = imageMove;
        imageMove = null;
        if (move == null || move.blockIndex() < 0 || move.blockIndex() >= editor.document().blocks().size()
                || !(editor.document().blocks().get(move.blockIndex()) instanceof ImageBlock image)
                || image.layoutMode() == ImageLayoutMode.INLINE) {
            return;
        }
        if (mouseX == move.startMouseX() && mouseY == move.startMouseY()) {
            // A second ordinary click unlocks the picture. A click-and-drag
            // keeps it selected and commits a real floating position.
            if (deselectImageOnClick) selectedImageBlock = -1;
            return;
        }
        ensureLayout(bounds().bodyWidth());
        ImageLayout moved = imageLayouts.stream().filter(layout -> layout.blockIndex() == move.blockIndex()).findFirst().orElse(null);
        int pictureWidth = moved == null ? 1 : moved.width();
        int nextOffsetX = clamp(toPhysicalCoordinate(clamp(move.startImageX() + mouseX - move.startMouseX() - frameInset(image.frame()),
                0, Math.max(0, imageColumnWidth(image) - pictureWidth))), 0, 16_384);
        int nextOffsetY = clamp(toPhysicalCoordinate(Math.max(0, move.startImageY() + mouseY - move.startMouseY() - imageOriginY(move.blockIndex()))), 0, 16_384);
        if (nextOffsetX == image.offsetX() && nextOffsetY == image.offsetY()) {
            return;
        }
        editor.finishTextEdit();
        editor.updateImageBlock(move.blockIndex(), new ImageBlock(
                image.assetHash(), image.caption(), image.alignment(), image.requestedWidth(),
                image.layoutMode(), nextOffsetX, nextOffsetY));
        refreshAfterEdit();
    }

    /** Places the document caret in a real paragraph neighbouring a selected image. */
    private boolean insertTextAdjacentToSelectedImage(boolean before) {
        int imageBlock = selectedImageBlock;
        editor.finishTextEdit();
        int paragraphBlock = editor.insertParagraphAdjacentToImage(imageBlock, before);
        if (paragraphBlock < 0) {
            clearSelection();
            status("图片选择已失效，请重新选择图片");
            return true;
        }
        clearSelection();
        caretBlock = paragraphBlock;
        caretColumn = 0;
        selectionAnchorBlock = paragraphBlock;
        selectionAnchorColumn = 0;
        refreshAfterEdit();
        status(before ? "已在图片前插入文字" : "已在图片后插入文字");
        return true;
    }

    private int toGuiPixels(int physicalPixels) {
        double scale = minecraft == null ? 1.0 : Math.max(1.0, minecraft.getWindow().getGuiScale());
        return Math.max(1, (int) Math.round(physicalPixels / scale));
    }

    /** Coordinates may legitimately be zero (unlike an image's display width). */
    private int toGuiCoordinate(int physicalPixels) {
        double scale = minecraft == null ? 1.0 : Math.max(1.0, minecraft.getWindow().getGuiScale());
        return (int) Math.round(physicalPixels / scale);
    }

    private int toPhysicalPixels(int guiPixels) {
        double scale = minecraft == null ? 1.0 : Math.max(1.0, minecraft.getWindow().getGuiScale());
        return Math.max(1, (int) Math.round(guiPixels * scale));
    }

    private int toPhysicalCoordinate(int guiPixels) {
        double scale = minecraft == null ? 1.0 : Math.max(1.0, minecraft.getWindow().getGuiScale());
        return (int) Math.round(guiPixels * scale);
    }

    private int guiDeltaToPhysical(int guiPixels) {
        double scale = minecraft == null ? 1.0 : Math.max(1.0, minecraft.getWindow().getGuiScale());
        return (int) Math.round(guiPixels * scale);
    }

    private record ToolButton(int action, String label, int x, int y, int width) { }
    private static final String[] RIBBON_TABS = { "开始", "段落", "插入", "文档" };
    private static final int[][] RIBBON_ACTIONS = {
        {0, 1, 11, 2, 12, 13, 4, 5, 3, 14, 15, 31, 32},
        {16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 33},
        {6, 7, 28, 29, 30}, {8, 34, 35, 9, 36}
    };
    private static final String[] TOOL_LABELS = { "B", "I", "S", "样式", "字号", "A", "图片", "表格", "保存", "删页", "排序",
        "U", "x²", "x₂", "隐藏", "</>", "", "", "", "", "", "", "引用", "任务", "提示", "警告", "步骤", "代码块",
        "链接", "指令", "分隔线", "撤销", "重做", "正文", "导入 Word", "导出 Word", "纸色" };
    private static final String[] TOOL_HINTS = { "粗体  Ctrl+B", "斜体  Ctrl+I", "删除线  Ctrl+Shift+X", "正文 / 标题", "字号  Ctrl+Shift+P", "文字颜色",
        "插入图片（也可 Ctrl+V 粘贴）", "插入表格", "保存  Ctrl+S", "删除当前页", "排序选中段落", "下划线  Ctrl+U",
        "上标", "下标", "隐藏文字  Ctrl+Shift+H（阅读时点击揭示）", "行内代码", "左对齐  Ctrl+L", "居中  Ctrl+E", "右对齐  Ctrl+R", "两端对齐  Ctrl+J",
        "无序列表", "有序列表", "引用块", "任务列表（编辑时点击方框勾选）", "信息提示框", "警告提示框", "编号步骤", "多行代码块",
        "超链接  Ctrl+K", "点击执行 MC 指令", "插入水平分隔线", "撤销  Ctrl+Z", "重做  Ctrl+Y", "清除段落样式", "导入 .docx 文档（可撤销）", "导出 .docx 文档", "纸张背景颜色（本地偏好，不上传）" };
    private static int toolWidth(int action) { return switch (action) {
        case 0,1,2,5,11,12,13,15,16,17,18,19,20,21 -> 23;
        case 34,35 -> 66;
        case 6,7 -> 50;
        default -> 38;
    }; }
    // Reserve the largest tab so changing tabs never moves the document under the caret.
    private static int toolbarRows(int width) {
        int maximum = 1;
        for (int[] actions : RIBBON_ACTIONS) {
            int rows = 1, used = 0;
            for (int action : actions) {
                int w = toolWidth(action);
                if (used > 0 && used + w > width) { rows++; used = 0; }
                used += w + 3;
            }
            maximum = Math.max(maximum, rows);
        }
        return maximum;
    }
    private List<ToolButton> toolbarButtons(Bounds bounds) {
        List<ToolButton> result = new ArrayList<>();
        int x = bounds.modeX(), y = bounds.toolbarY() + 18;
        for (int action : RIBBON_ACTIONS[ribbonTab]) {
            int w = toolWidth(action);
            if (x > bounds.modeX() && x + w > bounds.right() - 8) { x = bounds.modeX(); y += 20; }
            result.add(new ToolButton(action, TOOL_LABELS[action], x, y, w));
            x += w + 3;
        }
        return result;
    }
    private boolean handleRibbonTabClick(Bounds b, double mx, double my) {
        for (int i = 0; i < RIBBON_TABS.length; i++) {
            if (inRect(mx, my, b.modeX() + i * 45, b.toolbarY(), 42, 16)) {
                ribbonTab = i; closeToolbarMenu(); return true;
            }
        }
        return false;
    }
    private void drawRibbon(GuiGraphics g, Bounds b, int mx, int my) {
        for (int i = 0; i < RIBBON_TABS.length; i++) {
            int x = b.modeX() + i * 45, y = b.toolbarY();
            if (i == ribbonTab) g.fill(x, y + 14, x + 40, y + 16, BookEditorChrome.ACCENT);
            g.drawString(font, RIBBON_TABS[i], x + 8, y + 3, i == ribbonTab ? HEADING_COLOR : DISABLED_COLOR, false);
        }
        for (ToolButton button : toolbarButtons(b)) {
            int x = button.x(), y = button.y(), a = button.action();
            boolean hover = inRect(mx, my, x, y, button.width(), 18), active = toolActive(a);
            g.fill(x, y, x + button.width(), y + 18, active ? 0xFFCDD9C1 : hover ? 0xFFE7DCBF : 0xFFF3EBD7);
            if (active) { g.fill(x, y + 17, x + button.width(), y + 18, BookEditorChrome.ACCENT); }
            if (a == 6 || a == 7) {
                int ix=x+4, iy=y+4, ink=0xFF315D89;
                g.fill(ix,iy,ix+13,iy+10,ink);g.fill(ix+1,iy+1,ix+12,iy+9,0xFFF2F6FA);
                if(a==7) {g.fill(ix+4,iy,ix+5,iy+10,ink);g.fill(ix+8,iy,ix+9,iy+10,ink);g.fill(ix,iy+3,ix+13,iy+4,ink);g.fill(ix,iy+6,ix+13,iy+7,ink);}
                else {g.fill(ix+8,iy+2,ix+10,iy+4,0xFFE4B347);for(int k=0;k<7;k++)g.fill(ix+2+k,iy+7-Math.min(k,6-k),ix+3+k,iy+9,ink);}
                g.drawString(font,button.label(),x+21,y+5,BODY_TEXT_COLOR,false);
            } else if (a >= 16 && a <= 21) {
                for (int row = 0; row < 4; row++) {
                    int length = (a == 19 || row % 2 == 0) ? 13 : 8;
                    int left = x + 5 + (a == 17 ? (13 - length) / 2 : a == 18 ? 13 - length : 0);
                    if (a >= 20) { left = x + 9; length = row % 2 == 0 ? 10 : 7; }
                    g.fill(left, y + 4 + row * 3, left + length, y + 5 + row * 3, BODY_TEXT_COLOR);
                    if (a == 20) g.fill(x + 4, y + 4 + row * 3, x + 6, y + 6 + row * 3, BODY_TEXT_COLOR);
                }
                if (a == 21) { g.pose().pushPose(); g.pose().translate(x + 2, y + 3, 0); g.pose().scale(.55f,.55f,1); g.drawString(font,"1",0,0,BODY_TEXT_COLOR,false);g.drawString(font,"2",0,10,BODY_TEXT_COLOR,false);g.pose().popPose(); }
            } else {
                Component label = Component.literal(button.label()).withStyle(Style.EMPTY.withBold(a == 0).withItalic(a == 1)
                        .withStrikethrough(a == 2).withUnderlined(a == 11));
                g.drawString(font, label, x + (button.width() - font.width(label)) / 2, y + 5, BODY_TEXT_COLOR, false);
                if (a == 5) g.fill(x + 5, y + 14, x + button.width() - 5, y + 16, 0xFF000000 | currentTypingStyle().rgbColor());
            }
        }
    }
    private boolean toolActive(int action) {
        TextStyle s = currentTypingStyle();
        RichTextBlock r = caretBlock >= 0 && caretBlock < editor.document().blocks().size()
                && editor.document().blocks().get(caretBlock) instanceof TextualBlock text ? RichTextBlock.from(text) : null;
        return switch (action) {
            case 0 -> s.bold(); case 1 -> s.italic(); case 2 -> s.strikethrough(); case 11 -> s.underline();
            case 12 -> s.script() == 1; case 13 -> s.script() == -1; case 14 -> s.hidden(); case 15 -> s.code();
            case 16,17,18,19 -> r != null && r.alignment().ordinal() == action - 16;
            case 22 -> r != null && r.isQuote();
            case 23 -> r != null && r.isTask();
            case 24,25 -> r != null && (r.frame() == null ? r.kind() == kindForAction(action)
                    : r.frame().kind().name().equals(kindForAction(action).name()));
            case 20,21,26,27 -> r != null && r.kind() == kindForAction(action);
            default -> false;
        };
    }
    private void drawRibbonTooltip(GuiGraphics g, Bounds b, int mx, int my) {
        if (!mode.isEditable() || toolbarMenu != ToolbarMenu.NONE) return;
        for (ToolButton button : toolbarButtons(b)) if (inRect(mx,my,button.x(),button.y(),button.width(),18)) {
            g.renderTooltip(font, Component.literal(TOOL_HINTS[button.action()]), mx, my); return;
        }
    }
    private static RichTextBlock.Kind kindForAction(int a) {
        return switch (a) {
            case 20 -> RichTextBlock.Kind.BULLET; case 21 -> RichTextBlock.Kind.NUMBERED;
            case 22 -> RichTextBlock.Kind.QUOTE; case 23 -> RichTextBlock.Kind.TASK;
            case 24 -> RichTextBlock.Kind.INFO; case 25 -> RichTextBlock.Kind.WARNING;
            case 26 -> RichTextBlock.Kind.STEP; case 27 -> RichTextBlock.Kind.CODE;
            default -> RichTextBlock.Kind.NORMAL;
        };
    }

    private boolean handleToolbarClick(Bounds bounds, double mouseX, double mouseY) {
        for (ToolButton button : toolbarButtons(bounds)) {
            if (!inRect(mouseX, mouseY, button.x(), button.y(), button.width(), 18)) continue;
            return switch (button.action()) {
                case 0 -> toggleBold();
                case 1 -> toggleItalic();
                case 2 -> toggleStrikethrough();
                case 3 -> openHeadingMenu();
                case 4 -> openFontSizeMenu();
                case 5 -> openColorMenu();
                case 6 -> importAndInsertImage();
                case 7 -> openTableMenu();
                case 8 -> {
                    editor.finishTextEdit();
                    saveCurrentDocument();
                    yield true;
                }
                case 9 -> {
                    if (!editor.deletePage(pageIndex)) status("至少保留一页，无法删除");
                    else {
                        pageIndex = clampPage(pageIndex);
                        clearSelection();
                        refreshAfterEdit();
                        pageChanged.accept(pageIndex);
                        status("已删除当前页面");
                    }
                    yield true;
                }
                case 10 -> openSortMenu();
                case 11 -> { boolean value = !currentTypingStyle().underline(); yield applyTextFormat(s -> s.withUnderline(value)); }
                case 12, 13 -> { int value = button.action() == 12 ? 1 : -1; int next = currentTypingStyle().script() == value ? 0 : value;
                    yield applyTextFormat(s -> s.withScript(next)); }
                case 14 -> { boolean value = !currentTypingStyle().hidden(); yield applyTextFormat(s -> s.withHidden(value)); }
                case 15 -> { boolean value = !currentTypingStyle().code(); yield applyTextFormat(s -> s.withCode(value)); }
                case 16,17,18,19 -> formatParagraph(r -> new RichTextBlock(r.kind(), RichTextBlock.Alignment.values()[button.action()-16], r.headingLevel(), r.checked(), r.runs(), r.frame()));
                case 20,21,22,23,24,25,26,27,33 -> applyParagraphKind(kindForAction(button.action()), !toolActive(button.action()));
                case 28 -> openLinkMenu(false);
                case 29 -> openLinkMenu(true);
                case 30 -> insertRule();
                case 31 -> { if (editor.undo()) refreshAfterEdit(); yield true; }
                case 32 -> { if (editor.redo()) refreshAfterEdit(); yield true; }
                case 34 -> importDocx();
                case 35 -> exportDocx();
                case 36 -> openPaperColorMenu();
                default -> false;
            };
        }
        return false;
    }

    private boolean applyParagraphKind(RichTextBlock.Kind kind, boolean enabled) {
        if (enabled && hasEditableCaret() && (kind == RichTextBlock.Kind.TASK
                || kind == RichTextBlock.Kind.BULLET || kind == RichTextBlock.Kind.NUMBERED)) {
            WikiDocumentEditor.PasteResult added = editor.addListInsideFrame(currentSelection(), kind);
            if (added != null) {
                placeCaret(new Caret(added.blockIndex(), added.column()), false);
                refreshAfterEdit();
                return true;
            }
        }
        return formatParagraph(r -> r.withPresentation(kind, enabled));
    }

    /** Opens the operating system's local PNG/JPG picker, then inserts an in-flow picture at the text caret. */
    private boolean importDocx() {
        String selected;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            selected = TinyFileDialogs.tinyfd_openFileDialog("导入 Word 文档", null, stack.pointers(stack.UTF8("*.docx")), "Word 文档 (*.docx)", false);
        }
        if (selected == null) return true;
        try {
            DocxIO.ImportResult imported = DocxIO.read(Path.of(selected), editor.document().metadata(), session::importLocalImage);
            editor.importDocument(imported.document());
            pageIndex=0;bodyScroll=0;clearSelection();refreshAfterEdit();pageChanged.accept(0);
            for (int i=0;i<editor.document().blocks().size();i++) if(isEditableBlock(i)) {placeCaret(new Caret(i,0),false);break;}
            showDocxReport("导入完成", "已导入正文。Ctrl+Z 可恢复导入前的内容，保存或退出时写入书中。", imported.warnings());
        } catch (IOException | IllegalArgumentException e) { showDocxReport("导入失败", e.getMessage(), List.of()); }
        return true;
    }
    private boolean exportDocx() {
        String selected;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            String name=editor.document().title().replaceAll("[\\\\/:*?\"<>|]", "_");
            selected=TinyFileDialogs.tinyfd_saveFileDialog("导出 Word 文档", (name.isBlank()?"文档":name)+".docx", stack.pointers(stack.UTF8("*.docx")), "Word 文档 (*.docx)");
        }
        if(selected==null)return true;
        if(!selected.toLowerCase(java.util.Locale.ROOT).endsWith(".docx"))selected+=".docx";
        try {
            List<String> warnings=DocxIO.write(Path.of(selected),editor.document(), hash -> {
                if(!hash.matches("[0-9a-f]{64}"))throw new IOException("图片标识无效");
                return com.writablebookwiki.local.StoragePaths.dataRoot(FMLPaths.CONFIGDIR.get()).resolve("assets").resolve(hash+".asset");
            });
            showDocxReport("导出完成", "已保存到："+selected, warnings);
        } catch(IOException | IllegalArgumentException e) {showDocxReport("导出失败",e.getMessage(),List.of());}
        return true;
    }
    private void showDocxReport(String title, String detail, List<String> warnings) {
        if(minecraft!=null) {
            showingChildReport = true;
            minecraft.setScreen(new DocxReportScreen(this,title,detail,warnings));
        }
    }

    private boolean formatParagraph(UnaryOperator<RichTextBlock> operation) {
        if (!hasEditableCaret()) { status("请先点击或选中正文段落"); return true; }
        editor.finishTextEdit();
        editor.formatParagraphs(currentSelection(), operation);
        refreshAfterEdit(); return true;
    }
    private boolean insertRule() {
        if (!hasEditableCaret()) { status("请先点击分隔线要插入的位置"); return true; }
        NormalizedScreenSelection range = normalizeScreenSelection(currentSelection());
        if (editor.insertHorizontalRule(currentSelection())) {
            placeCaret(new Caret(range.startBlock() + 2, 0), false);
            refreshAfterEdit();
        } else status("分隔线只能插入一段文字中");
        return true;
    }
    private boolean isLinkMenu() { return toolbarMenu == ToolbarMenu.LINK || toolbarMenu == ToolbarMenu.COMMAND; }
    private boolean openLinkMenu(boolean command) {
        if (!hasTextSelection() || tableCellCaret != null) { status("请先选中正文中作为链接的文字"); return true; }
        editor.finishTextEdit();
        toolbarMenu = command ? ToolbarMenu.COMMAND : ToolbarMenu.LINK;
        linkInput = new EditBox(font, bounds().modeX(), bounds().menuY() + 14, 250, 18, Component.literal("链接目标"));
        linkInput.setMaxLength(command ? 257 : 2048);
        linkInput.setHint(Component.literal(command ? "/say 你好" : "https://… 或 page:2"));
        NormalizedScreenSelection range = normalizeScreenSelection(currentSelection());
        TextRun run = runAt(editor.document().blocks().get(range.startBlock()).runs(), range.startColumn());
        if (run != null && run.linkTarget() != null) {
            String target = run.linkTarget();
            if (command && target.startsWith("command:")) target = target.substring(8);
            if (command == run.linkTarget().startsWith("command:")) linkInput.setValue(target);
        }
        linkInput.setFocused(true);
        linkInput.setHighlightPos(0);
        return true;
    }
    private boolean applyLink(boolean remove) {
        try {
            String target = null;
            if (!remove) {
                DocumentLink link = DocumentLink.parse((toolbarMenu == ToolbarMenu.COMMAND ? "command:" : "") + linkInput.getValue());
                if (toolbarMenu == ToolbarMenu.LINK && link.kind() == DocumentLink.Kind.COMMAND)
                    throw new IllegalArgumentException("游戏指令请使用“指令”按钮");
                if (link.kind() == DocumentLink.Kind.PAGE && Integer.parseInt(link.value()) > NativeDocumentPresentation.pageCount(editor.document()))
                    throw new IllegalArgumentException("这本书还没有该页");
                target = link.target();
            }
            editor.setLinkTarget(currentSelection(), target);
            closeToolbarMenu(); refreshAfterEdit(); status(remove ? "已移除链接" : "已设置链接，切换阅读后点击使用");
        } catch (IllegalArgumentException e) { status(e.getMessage()); }
        return true;
    }
    private static TextRun runAt(List<TextRun> runs, int column) {
        int offset = 0;
        for (TextRun run : runs) {
            if (column >= offset && column < offset + run.text().length()) return run;
            offset += run.text().length();
        }
        return null;
    }
    private String spoilerKey(int block, String cell, int column, List<TextRun> runs) {
        int offset = 0;
        for (TextRun run : runs) {
            if (column < offset + run.text().length()) break;
            offset += run.text().length();
        }
        return block + ":" + cell + ":" + offset;
    }
    private boolean handleRichContentClick(Bounds b, double mx, double my) {
        ensureLayout(b.bodyWidth());
        double localX = mx - b.bodyX(), localY = my - b.bodyTop() + bodyScroll;
        for (LayoutLine line : layoutLines) {
            if (line.blockIndex() < 0) continue;
            Block block = editor.document().blocks().get(line.blockIndex());
            if (block instanceof RichTextBlock r && r.isTask() && line.startColumn() == 0 && taskBox(line).contains(localX, localY)) {
                if (mode.isEditable()) { editor.toggleTask(line.blockIndex()); refreshAfterEdit(); }
                else status("切换编辑后可勾选任务");
                return true;
            }
            if (localY < line.y() || localY >= line.y() + line.height()) continue;
            for (Fragment f : line.fragments()) if (localX >= f.x() && localX < f.x()+f.advance()) {
                return activateRun(line.blockIndex(), "", f.startColumn(), block.runs());
            }
        }
        if (!mode.isEditable()) {
            for (TableLayout table : tableLayouts) for (int row=0;row<table.rowLayouts().size();row++) {
                TableRowLayout r=table.rowLayouts().get(row);
                for(int col=0;col<table.columns();col++) for(TableCellLine line:r.cells().get(col).lines()) {
                    double x=localX-table.xOffset()-col*table.baseColumnWidth()-TABLE_CELL_PADDING;
                    int y=table.y()+r.yOffset()+TABLE_CELL_PADDING+line.yOffset();
                    if(localY<y || localY>=y+line.height()) continue;
                    for(Fragment f:line.fragments()) if(x>=f.x() && x<f.x()+f.advance())
                        return activateRun(table.blockIndex(),row+":"+col,f.startColumn(),table.table().rows().get(row).cells().get(col).runs());
                }
            }
        }
        return false;
    }
    private void drawContentTooltip(GuiGraphics g, Bounds b, int mx, int my) {
        if(toolbarMenu!=ToolbarMenu.NONE || mx<b.bodyX() || mx>=b.bodyX()+b.bodyWidth() || my<b.bodyTop() || my>=b.footerY()) return;
        double x=mx-b.bodyX(), y=my-b.bodyTop()+bodyScroll;
        for(LayoutLine line:layoutLines) {
            if(line.blockIndex()<0 || y<line.y() || y>=line.y()+line.height()) continue;
            List<TextRun> runs=editor.document().blocks().get(line.blockIndex()).runs();
            for(Fragment f:line.fragments()) if(x>=f.x() && x<f.x()+f.advance()) {
                TextRun run=runAt(runs,f.startColumn()); if(run==null) return;
                String text=null;
                if(run.style().hidden() && !mode.isEditable() && !revealedSpoilers.contains(spoilerKey(line.blockIndex(),"",f.startColumn(),runs)))text="点击显示隐藏文字";
                else if(run.isLink()) {
                    try {
                        DocumentLink link=DocumentLink.parse(run.linkTarget());
                        text=switch(link.kind()) {
                            case COMMAND -> "执行 /"+link.value()+"（使用点击玩家的权限）";
                            case PAGE -> "跳转到第 "+link.value()+" 页";
                            case WEB -> link.value();
                        };
                        if(mode.isEditable())text="阅读时点击："+text;
                    } catch(IllegalArgumentException e) {text="链接目标无效";}
                }
                if(text!=null)g.renderComponentTooltip(font,font.split(Component.literal(text),Math.min(320,width-30))
                        .stream().<Component>map(seq -> Component.literal(sequenceText(seq))).toList(),mx,my);
                return;
            }
        }
    }
    private static String sequenceText(net.minecraft.util.FormattedCharSequence sequence) {
        StringBuilder text=new StringBuilder();sequence.accept((index,style,codePoint)->{text.appendCodePoint(codePoint);return true;});return text.toString();
    }

    private boolean activateRun(int block, String cell, int column, List<TextRun> runs) {
        if (mode.isEditable()) return false;
        TextRun run = runAt(runs, column);
        if (run == null) return false;
        String key = spoilerKey(block,cell,column,runs);
        if (run.style().hidden() && !revealedSpoilers.contains(key)) { revealedSpoilers.add(key); return true; }
        if (run.linkTarget() != null) {
            try {
                DocumentLink link = DocumentLink.parse(run.linkTarget());
                switch (link.kind()) {
                    case WEB -> handleComponentClicked(Style.EMPTY.withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, link.value())));
                    case PAGE -> {
                        int page = Integer.parseInt(link.value()) - 1;
                        if (page < NativeDocumentPresentation.pageCount(editor.document())) showPage(page);
                        else status("目标页面不存在");
                    }
                    case COMMAND -> {
                        if (minecraft != null && minecraft.player != null) {
                            minecraft.player.connection.sendCommand(link.value());
                            status("已发送指令；结果由服务器返回");
                        } else status("进入世界后才能执行指令");
                    }
                }
            } catch (IllegalArgumentException e) { status(e.getMessage()); }
            return true;
        }
        if (run.style().hidden()) { revealedSpoilers.remove(key); return true; }
        return false;
    }

    private boolean importAndInsertImage() {
        if (!hasEditableCaret()) {
            status("请先点击图片要插入的位置");
            return true;
        }
        String selected = chooseLocalImageFile();
        if (selected == null || selected.isBlank()) {
            return true;
        }
        try {
            ImportedImageAsset imported = session.importLocalImage(Path.of(selected));
            insertImportedImage(imported);
        } catch (InvalidPathException exception) {
            status("图片路径无效");
        } catch (IOException exception) {
            String reason = exception.getMessage();
            status(reason == null || reason.isBlank()
                    ? "图片未导入：只支持安全的 PNG、JPG 或 JPEG"
                    : reason);
        }
        return true;
    }

    private void insertImportedImage(ImportedImageAsset imported) {
        ImageBlock image = new ImageBlock(imported.assetHash(), "", ImageAlignment.LEFT, 0);
        editor.finishTextEdit();
        if (!editor.insertImageAtSelection(currentSelection(), image)) {
            status("图片只能插入当前一段文字中");
            return;
        }
        WikiImageTextures.invalidate(imported.assetHash());
        selectedImageBlock = imageBlockAtOrAfter(caretBlock, imported.assetHash());
        clearTextSelectionAfterImageInsert();
        refreshAfterEdit();
        status("已插入图片，可按 Ctrl+Z 撤销");
    }

    /** Returns false only when ordinary text paste should handle the clipboard. */
    private boolean pasteClipboardImage() {
        try {
            if (java.awt.GraphicsEnvironment.isHeadless()
                    && System.getProperty("os.name", "").startsWith("Windows")) {
                ImportedImageAsset imported = WindowsClipboardImageImport.importImage(
                        com.writablebookwiki.local.StoragePaths.dataRoot(FMLPaths.CONFIGDIR.get()).resolve("temporary"),
                        source -> {
                            if (!hasEditableCaret() || tableCellCaret != null)
                                throw new IOException("请先点击正文中要插入图片的位置");
                            NormalizedScreenSelection selection = normalizeScreenSelection(currentSelection());
                            if (selection == null || selection.startBlock() != selection.endBlock())
                                throw new IOException("图片只能插入当前一段文字中");
                            return session.importLocalImage(source);
                        });
                if (imported == null) return false;
                insertImportedImage(imported);
                return true;
            }
            var contents = java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().getContents(null);
            if (!ClipboardImageImport.hasImage(contents)) return false;
            if (!hasEditableCaret() || tableCellCaret != null) {
                status("请先点击正文中要插入图片的位置");
                return true;
            }
            NormalizedScreenSelection selection = normalizeScreenSelection(currentSelection());
            if (selection == null || selection.startBlock() != selection.endBlock()) {
                status("图片只能插入当前一段文字中");
                return true;
            }
            ImportedImageAsset imported = ClipboardImageImport.importImage(contents,
                    com.writablebookwiki.local.StoragePaths.dataRoot(FMLPaths.CONFIGDIR.get()).resolve("temporary"),
                    session::importLocalImage);
            if (imported != null) insertImportedImage(imported);
            return true;
        } catch (java.awt.HeadlessException exception) {
            status("当前 Java 环境无法读取剪贴板图片，请使用“图”按钮导入");
            return false;
        } catch (IOException exception) {
            status(exception.getMessage() == null ? "图片粘贴失败，请重新复制图片后重试" : exception.getMessage());
            return true;
        } catch (java.awt.datatransfer.UnsupportedFlavorException | IllegalStateException exception) {
            status("图片粘贴失败，请重新复制图片后重试");
            return true;
        }
    }

    private String chooseLocalImageFile() {
        // tinyfiledialogs is bundled with Minecraft's LWJGL runtime. It opens
        // a local operating-system picker; image bytes are then copied into
        // our own content-addressed store and never retain this source path.
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer patterns = stack.pointers(
                    stack.UTF8("*.png"), stack.UTF8("*.jpg"), stack.UTF8("*.jpeg"));
            return TinyFileDialogs.tinyfd_openFileDialog("选择本地图片", null, patterns,
                    "PNG、JPG 或 JPEG 图片", false);
        } catch (RuntimeException exception) {
            status("无法打开本地图片选择窗口");
            return null;
        }
    }

    private int imageBlockAtOrAfter(int startIndex, String assetHash) {
        List<Block> blocks = editor.document().blocks();
        for (int index = Math.max(0, startIndex); index < blocks.size(); index++) {
            if (blocks.get(index) instanceof ImageBlock image && image.assetHash().equals(assetHash)) {
                return index;
            }
        }
        return -1;
    }

    private void clearTextSelectionAfterImageInsert() {
        caretBlock = -1;
        caretColumn = 0;
        selectionAnchorBlock = -1;
        selectionAnchorColumn = 0;
        selectingWithMouse = false;
    }

    private boolean handleToolbarMenuClick(Bounds bounds, double mouseX, double mouseY) {
        if (isColorMenu()) return handleColorPaletteClick(bounds, mouseX, mouseY);
        int x = bounds.modeX() + 52;
        int y = bounds.menuY();
        if (isLinkMenu()) {
            if (inRect(mouseX,mouseY,bounds.right()-98,y+14,42,18)) return applyLink(false);
            if (inRect(mouseX,mouseY,bounds.right()-52,y+14,42,18)) return applyLink(true);
            if (linkInput != null && linkInput.mouseClicked(mouseX,mouseY,0)) { linkInput.setFocused(true); return true; }
            closeToolbarMenu(); return true;
        }
        if (toolbarMenu == ToolbarMenu.HEADING) {
            if (inRect(mouseX, mouseY, x, y, 34, 20)) {
                return setHeadingLevel(0);
            }
            for (int level = 1; level <= 3; level++) {
                int optionX = x + 38 + (level - 1) * 28;
                if (inRect(mouseX, mouseY, optionX, y, 24, 20)) {
                    return setHeadingLevel(level);
                }
            }
        } else if (toolbarMenu == ToolbarMenu.FONT_SIZE) {
            for (int index = 0; index < FONT_SIZE_PRESETS.length; index++) {
                int optionX = x + index * 25;
                if (inRect(mouseX, mouseY, optionX, y, 22, 20)) {
                    return setFontSize(FONT_SIZE_PRESETS[index]);
                }
            }
        } else if (toolbarMenu == ToolbarMenu.TABLE) {
            if (inRect(mouseX, mouseY, x + 12, y, 24, 20)) {
                tableSizeInput = TableSizeInput.ROWS;
                replaceTableSizeInputOnType = true;
                return true;
            }
            if (inRect(mouseX, mouseY, x + 53, y, 24, 20)) {
                tableSizeInput = TableSizeInput.COLUMNS;
                replaceTableSizeInputOnType = true;
                return true;
            }
            if (inRect(mouseX, mouseY, x + 82, y, 34, 20)) {
                return insertConfiguredTable();
            }
        } else if (toolbarMenu == ToolbarMenu.SORT) {
            if (inRect(mouseX, mouseY, x, y, 38, 20)) {
                return sortSelectedTextBlocks(true);
            }
            if (inRect(mouseX, mouseY, x + 42, y, 38, 20)) {
                return sortSelectedTextBlocks(false);
            }
        }
        closeToolbarMenu();
        return true;
    }

    private boolean handleToolbarMenuKey(int keyCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            closeToolbarMenu();
            return true;
        }
        if (isColorMenu()) {
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) return applyCustomColor();
            if (colorInput != null) colorInput.keyPressed(keyCode, 0, modifiers);
            return true;
        }
        if (isLinkMenu()) {
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) return applyLink(false);
            if (linkInput != null) linkInput.keyPressed(keyCode, 0, modifiers);
            return true;
        }
        if (toolbarMenu == ToolbarMenu.TABLE) {
            if (keyCode == GLFW.GLFW_KEY_TAB) {
                tableSizeInput = tableSizeInput == TableSizeInput.ROWS
                        ? TableSizeInput.COLUMNS : TableSizeInput.ROWS;
                replaceTableSizeInputOnType = true;
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_BACKSPACE) {
                replaceTableSizeInputOnType = false;
                if (tableSizeInput == TableSizeInput.ROWS && !tableRowsInput.isEmpty()) {
                    tableRowsInput = tableRowsInput.substring(0, tableRowsInput.length() - 1);
                } else if (tableSizeInput == TableSizeInput.COLUMNS && !tableColumnsInput.isEmpty()) {
                    tableColumnsInput = tableColumnsInput.substring(0, tableColumnsInput.length() - 1);
                }
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                return insertConfiguredTable();
            }
            return true;
        }
        if (toolbarMenu == ToolbarMenu.SORT || toolbarMenu == ToolbarMenu.HEADING || toolbarMenu == ToolbarMenu.COLOR) {
            // A menu owns the keyboard until Escape closes it. Page navigation
            // and edits must not fall through to the document below.
            return true;
        }
        if (toolbarMenu != ToolbarMenu.FONT_SIZE) {
            return false;
        }
        if (keyCode == GLFW.GLFW_KEY_BACKSPACE) {
            if (!fontSizeInput.isEmpty()) {
                fontSizeInput = fontSizeInput.substring(0, fontSizeInput.length() - 1);
            }
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            if (fontSizeInput.isEmpty()) {
                status("请输入 6 到 72 的字号");
                return true;
            }
            try {
                return setFontSize(Integer.parseInt(fontSizeInput));
            } catch (NumberFormatException ignored) {
                status("字号必须是数字");
                return true;
            }
        }
        // While a child palette is visible it owns the keyboard. In
        // particular, PageDown must not change the book page behind it.
        return true;
    }

    private boolean handleEditKey(int keyCode, int modifiers) {
        boolean control = (modifiers & GLFW.GLFW_MOD_CONTROL) != 0;
        boolean shift = (modifiers & GLFW.GLFW_MOD_SHIFT) != 0;
        if (control) {
            if (keyCode == GLFW.GLFW_KEY_LEFT || keyCode == GLFW.GLFW_KEY_RIGHT)
                return moveCaret(keyCode == GLFW.GLFW_KEY_LEFT ? -1 : 1, shift, true);
            if (keyCode == GLFW.GLFW_KEY_HOME || keyCode == GLFW.GLFW_KEY_END)
                return moveCaretToPageBoundary(keyCode == GLFW.GLFW_KEY_END, shift);
            if (keyCode == GLFW.GLFW_KEY_BACKSPACE || keyCode == GLFW.GLFW_KEY_DELETE) {
                if (!hasSelection()) moveCaret(keyCode == GLFW.GLFW_KEY_BACKSPACE ? -1 : 1, true, true);
                return replaceCurrentSelection("");
            }
            if (keyCode == GLFW.GLFW_KEY_A) {
                return selectCurrentPageText();
            }
            if (keyCode == GLFW.GLFW_KEY_B) {
                return toggleBold();
            }
            if (keyCode == GLFW.GLFW_KEY_L || keyCode == GLFW.GLFW_KEY_E || keyCode == GLFW.GLFW_KEY_R || keyCode == GLFW.GLFW_KEY_J) {
                if (keyCode == GLFW.GLFW_KEY_L && shift) return applyParagraphKind(RichTextBlock.Kind.BULLET, true);
                RichTextBlock.Alignment alignment = switch(keyCode) {
                    case GLFW.GLFW_KEY_E -> RichTextBlock.Alignment.CENTER; case GLFW.GLFW_KEY_R -> RichTextBlock.Alignment.RIGHT;
                    case GLFW.GLFW_KEY_J -> RichTextBlock.Alignment.JUSTIFY; default -> RichTextBlock.Alignment.LEFT;
                };
                return formatParagraph(r -> new RichTextBlock(r.kind(),alignment,r.headingLevel(),r.checked(),r.runs(),r.frame()));
            }
            if (keyCode == GLFW.GLFW_KEY_H && shift) { boolean value=!currentTypingStyle().hidden();return applyTextFormat(s -> s.withHidden(value)); }
            if (keyCode == GLFW.GLFW_KEY_EQUAL) { int value=shift?1:-1;int next=currentTypingStyle().script()==value?0:value;return applyTextFormat(s -> s.withScript(next)); }
            if (keyCode == GLFW.GLFW_KEY_K) return openLinkMenu(false);
            if (keyCode == GLFW.GLFW_KEY_U) { boolean value = !currentTypingStyle().underline(); return applyTextFormat(s -> s.withUnderline(value)); }
            if (keyCode == GLFW.GLFW_KEY_I) {
                return toggleItalic();
            }
            if (keyCode == GLFW.GLFW_KEY_P && shift) {
                return openFontSizeMenu();
            }
            if (keyCode == GLFW.GLFW_KEY_X && shift) {
                return toggleStrikethrough();
            }
            if (keyCode == GLFW.GLFW_KEY_C) {
                return copySelection();
            }
            if (keyCode == GLFW.GLFW_KEY_X) {
                return cutSelection();
            }
            if (keyCode == GLFW.GLFW_KEY_V) {
                return pasteClipboard();
            }
            if (keyCode == GLFW.GLFW_KEY_S) {
                editor.finishTextEdit();
                saveCurrentDocument();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_Z && !shift) {
                if (editor.undo()) {
                    refreshAfterEdit();
                }
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_Y || (keyCode == GLFW.GLFW_KEY_Z && shift)) {
                if (editor.redo()) {
                    refreshAfterEdit();
                }
                return true;
            }
            return false;
        }
        if (keyCode == GLFW.GLFW_KEY_BACKSPACE) {
            return deleteBeforeCaret();
        }
        if (keyCode == GLFW.GLFW_KEY_DELETE) {
            return deleteAfterCaret();
        }
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            if (hasEditableCaret() && editor.document().blocks().get(caretBlock) instanceof RichTextBlock list && list.isList()) {
                return shift ? insertSoftBreak() : splitAtCaret();
            }
            boolean container = hasEditableCaret() && (editor.document().blocks().get(caretBlock) instanceof QuoteBlock
                    || editor.document().blocks().get(caretBlock) instanceof RichTextBlock r
                    && (r.frame() != null || r.isQuote() || r.isTask() || r.kind() == RichTextBlock.Kind.INFO
                    || r.kind() == RichTextBlock.Kind.WARNING || r.kind() == RichTextBlock.Kind.STEP));
            return (container ? !shift : shift) ? insertSoftBreak() : splitAtCaret();
        }
        if (keyCode == GLFW.GLFW_KEY_UP || keyCode == GLFW.GLFW_KEY_DOWN) {
            return moveCaretVertically(keyCode == GLFW.GLFW_KEY_UP ? -1 : 1, shift);
        }
        if (keyCode == GLFW.GLFW_KEY_LEFT) {
            return moveCaret(-1, shift);
        }
        if (keyCode == GLFW.GLFW_KEY_RIGHT) {
            return moveCaret(1, shift);
        }
        if (keyCode == GLFW.GLFW_KEY_HOME) {
            return moveCaretToBoundary(false, shift);
        }
        if (keyCode == GLFW.GLFW_KEY_END) {
            return moveCaretToBoundary(true, shift);
        }
        if (keyCode == GLFW.GLFW_KEY_PAGE_DOWN) {
            nextPage();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_PAGE_UP) {
            showPage(pageIndex - 1);
            return true;
        }
        return false;
    }

    private TextStyle currentTypingStyle() {
        if (typingStyle != null) return typingStyle;
        if (tableCellCaret != null && editor.document().blocks().get(tableCellCaret.blockIndex()) instanceof TableBlock table) {
            return WikiDocumentEditor.styleAt(table.rows().get(tableCellCaret.row()).cells()
                    .get(tableCellCaret.tableColumn()).runs(), tableCellCaret.column());
        }
        return editor.insertionStyle(caretBlock, caretColumn);
    }

    private boolean applyTextFormat(UnaryOperator<TextStyle> operation) {
        editor.finishTextEdit();
        if (tableCellCaret != null && tableSelectionAnchor != tableCellCaret.column()) {
            editor.applyTableCellStyle(tableCellCaret.blockIndex(), tableCellCaret.row(), tableCellCaret.tableColumn(),
                    Math.min(tableSelectionAnchor, tableCellCaret.column()),
                    Math.max(tableSelectionAnchor, tableCellCaret.column()), operation);
        } else if (hasTextSelection()) {
            editor.applyStyle(currentSelection(), operation);
        } else if (hasEditableCaret() || tableCellCaret != null) {
            typingStyle = operation.apply(currentTypingStyle());
        } else {
            status("请先点击要输入的位置");
        }
        refreshAfterEdit();
        return true;
    }

    private boolean toggleBold() {
        if (hasTextSelection() && tableCellCaret == null) {
            editor.toggleBold(currentSelection());
            refreshAfterEdit();
            return true;
        }
        boolean value = !currentTypingStyle().bold();
        return applyTextFormat(s -> s.withBold(value));
    }

    private boolean toggleItalic() {
        if (hasTextSelection() && tableCellCaret == null) {
            editor.toggleItalic(currentSelection());
            refreshAfterEdit();
            return true;
        }
        boolean value = !currentTypingStyle().italic();
        return applyTextFormat(s -> s.withItalic(value));
    }

    private boolean toggleStrikethrough() {
        if (hasTextSelection() && tableCellCaret == null) {
            editor.toggleStrikethrough(currentSelection());
            refreshAfterEdit();
            return true;
        }
        boolean value = !currentTypingStyle().strikethrough();
        return applyTextFormat(s -> s.withStrikethrough(value));
    }

    private boolean openHeadingMenu() {
        if (!hasEditableCaret()) {
            status("请先点击要设置的段落");
            return true;
        }
        toolbarMenu = toolbarMenu == ToolbarMenu.HEADING ? ToolbarMenu.NONE : ToolbarMenu.HEADING;
        return true;
    }

    /** Opens Word-style paragraph sorting for one selected continuous text range. */
    private boolean openSortMenu() {
        TextSelection selection = currentSelection();
        if (selection == null || selection.startLine() == selection.endLine()) {
            status("请框选至少两段文字后排序");
            return true;
        }
        toolbarMenu = toolbarMenu == ToolbarMenu.SORT ? ToolbarMenu.NONE : ToolbarMenu.SORT;
        return true;
    }

    private boolean sortSelectedTextBlocks(boolean ascending) {
        TextSelection selection = currentSelection();
        if (selection == null) {
            closeToolbarMenu();
            status("请框选至少两段文字后排序");
            return true;
        }
        editor.finishTextEdit();
        if (!editor.sortTextBlocks(selection, ascending)) {
            closeToolbarMenu();
            status("排序范围只能包含连续文字段落，不能跨图片、表格或分页");
            return true;
        }
        clearSelection();
        closeToolbarMenu();
        refreshAfterEdit();
        status(ascending ? "已按段落 A→Z 排序" : "已按段落 Z→A 排序");
        return true;
    }

    private boolean setHeadingLevel(int level) {
        if (!hasEditableCaret()) {
            closeToolbarMenu();
            return true;
        }
        Block block = editor.document().blocks().get(caretBlock);
        editor.finishTextEdit();
        editor.setBlockType(currentSelection(), level);
        closeToolbarMenu();
        refreshAfterEdit();
        return true;
    }

    private String headingButtonLabel() {
        return "H";
    }

    private boolean openFontSizeMenu() {
        if (!hasEditableCaret() && tableCellCaret == null) {
            status("请先点击要输入的位置");
            return true;
        }
        if (toolbarMenu == ToolbarMenu.FONT_SIZE) {
            closeToolbarMenu();
            return true;
        }
        toolbarMenu = ToolbarMenu.FONT_SIZE;
        // An empty input is intentional: unlike a preset button, typed text
        // means an explicit custom Word-style point size.
        fontSizeInput = "";
        return true;
    }

    private boolean openColorMenu() {
        if (!hasEditableCaret() && tableCellCaret == null) {
            status("请先点击要输入的位置");
            return true;
        }
        if (toolbarMenu == ToolbarMenu.COLOR) closeToolbarMenu();
        else openColorPalette(ToolbarMenu.COLOR, currentTypingStyle().rgbColor());
        return true;
    }

    private boolean isColorMenu() {
        return toolbarMenu == ToolbarMenu.COLOR || toolbarMenu == ToolbarMenu.PAPER_COLOR;
    }

    private boolean openPaperColorMenu() {
        openColorPalette(ToolbarMenu.PAPER_COLOR, paperColorRgb);
        return true;
    }

    private void openColorPalette(ToolbarMenu target, int initialColor) {
        toolbarMenu = target;
        colorInput = new EditBox(font, 0, 0, 82, 18, Component.literal("自定义颜色 #RRGGBB"));
        colorInput.setMaxLength(7);
        colorInput.setValue("#%06X".formatted(initialColor));
        colorInput.setFocused(true);
        colorInput.setCursorPosition(0);
        colorInput.setHighlightPos(colorInput.getValue().length());
    }

    /** A real child popup: opening a palette must not move or reflow the selected text. */
    private record ColorPaletteBounds(int x, int y, int columns, int rows) {
        int width() { return columns * 18 + 12; }
        int height() { return 20 + rows * 18 + 28; }
        int swatchX(int index) { return x + 6 + index % columns * 18; }
        int swatchY(int index) { return y + 20 + index / columns * 18; }
        int inputY() { return y + 20 + rows * 18 + 4; }
    }

    private ColorPaletteBounds colorPaletteBounds(Bounds bounds) {
        int columns = Math.max(6, Math.min(12, (bounds.right() - bounds.left() - 28) / 18));
        int rows = (COLOR_PRESETS.length + columns - 1) / columns;
        int popupHeight = 20 + rows * 18 + 28;
        int y = Math.max(bounds.top() + 4, Math.min(bounds.menuY(), bounds.bottom() - popupHeight - 5));
        return new ColorPaletteBounds(bounds.modeX(), y, columns, rows);
    }

    private void positionColorInput(ColorPaletteBounds popup) {
        if (colorInput == null) return;
        colorInput.setX(popup.x() + 6);
        colorInput.setY(popup.inputY());
    }

    private static int parseRgb(String value) {
        String trimmed = value.trim();
        if (!trimmed.matches("#?[0-9a-fA-F]{6}")) return -1;
        return Integer.parseInt(trimmed.startsWith("#") ? trimmed.substring(1) : trimmed, 16);
    }

    private boolean applyCustomColor() {
        int color = colorInput == null ? -1 : parseRgb(colorInput.getValue());
        if (color < 0) { status("请输入六位色值，例如 #FFAA00"); return true; }
        return setColor(color);
    }

    private void drawColorPalette(GuiGraphics g, Bounds bounds, int mouseX, int mouseY) {
        ColorPaletteBounds p = colorPaletteBounds(bounds);
        int right = p.x() + p.width(), bottom = p.y() + p.height();
        g.fill(p.x() + 2, p.y() + 2, right + 2, bottom + 2, 0x70000000);
        g.fill(p.x(), p.y(), right, bottom, 0xFF796347);
        g.fill(p.x() + 1, p.y() + 1, right - 1, bottom - 1, PAPER_COLOR);
        g.drawString(font, toolbarMenu == ToolbarMenu.PAPER_COLOR ? "纸色 · 仅本机" : "文字颜色 · 60 色",
                p.x() + 6, p.y() + 6, HEADING_COLOR, false);
        int current = toolbarMenu == ToolbarMenu.PAPER_COLOR ? paperColorRgb : currentTypingStyle().rgbColor();
        int preview = colorInput == null ? current : parseRgb(colorInput.getValue());
        g.fill(right - 22, p.y() + 4, right - 5, p.y() + 17, PANEL_BORDER_COLOR);
        g.fill(right - 21, p.y() + 5, right - 6, p.y() + 16, 0xFF000000 | (preview < 0 ? current : preview));
        int hovered = -1;
        for (int index = 0; index < COLOR_PRESETS.length; index++) {
            int x = p.swatchX(index), y = p.swatchY(index);
            boolean hover = inRect(mouseX, mouseY, x, y, 16, 16);
            if (hover) hovered = index;
            g.fill(x, y, x + 16, y + 16, hover || current == COLOR_PRESETS[index] ? 0xFF263F34 : 0xFFB6A88C);
            g.fill(x + 1, y + 1, x + 15, y + 15, 0xFFFFFFFF);
            g.fill(x + 2, y + 2, x + 14, y + 14, 0xFF000000 | COLOR_PRESETS[index]);
            if (current == COLOR_PRESETS[index]) {
                // Two-tone corner marker remains legible on black, white and saturated swatches.
                g.fill(x + 10, y + 10, x + 15, y + 15, 0xFF263F34);
                g.fill(x + 11, y + 11, x + 14, y + 14, 0xFFFFFFFF);
            }
        }
        positionColorInput(p);
        if (colorInput != null) colorInput.render(g, mouseX, mouseY, 0);
        drawButton(g, p.x() + 94, p.inputY(), 36, 18, "应用",
                inRect(mouseX, mouseY, p.x() + 94, p.inputY(), 36, 18));
        drawButton(g, p.x() + 136, p.inputY(), 48, 18, "默认",
                inRect(mouseX, mouseY, p.x() + 136, p.inputY(), 48, 18));
        if (hovered >= 0) g.renderTooltip(font, Component.literal("#%06X".formatted(COLOR_PRESETS[hovered])), mouseX, mouseY);
    }

    private boolean handleColorPaletteClick(Bounds bounds, double mouseX, double mouseY) {
        ColorPaletteBounds p = colorPaletteBounds(bounds);
        for (int index = 0; index < COLOR_PRESETS.length; index++) {
            if (inRect(mouseX, mouseY, p.swatchX(index), p.swatchY(index), 16, 16)) return setColor(COLOR_PRESETS[index]);
        }
        positionColorInput(p);
        if (colorInput != null && colorInput.mouseClicked(mouseX, mouseY, 0)) {
            colorInput.setFocused(true);
            return true;
        }
        if (inRect(mouseX, mouseY, p.x() + 94, p.inputY(), 36, 18)) return applyCustomColor();
        if (inRect(mouseX, mouseY, p.x() + 136, p.inputY(), 48, 18)) {
            return setColor(toolbarMenu == ToolbarMenu.PAPER_COLOR ? LocalAppearanceStore.DEFAULT_PAPER_RGB : 0x000000);
        }
        if (!inRect(mouseX, mouseY, p.x(), p.y(), p.width(), p.height())) closeToolbarMenu();
        return true;
    }

    private boolean openTableMenu() {
        if (!hasEditableCaret()) {
            status("请先点击表格要插入的位置");
            return true;
        }
        if (toolbarMenu == ToolbarMenu.TABLE) {
            toolbarMenu = ToolbarMenu.NONE;
        } else {
            toolbarMenu = ToolbarMenu.TABLE;
            tableSizeInput = TableSizeInput.ROWS;
            replaceTableSizeInputOnType = true;
        }
        return true;
    }

    private void drawTableSizeInput(GuiGraphics graphics, int x, int y, String value, boolean active,
            boolean hovered) {
        graphics.fill(x, y, x + 24, y + 20, active || hovered ? 0xFFFFFFFF : DIRECTORY_BACKGROUND);
        graphics.fill(x, y, x + 24, y + 1, active ? LINK_COLOR : PANEL_BORDER_COLOR);
        graphics.fill(x, y + 19, x + 24, y + 20, active ? LINK_COLOR : PANEL_BORDER_COLOR);
        graphics.fill(x, y, x + 1, y + 20, active ? LINK_COLOR : PANEL_BORDER_COLOR);
        graphics.fill(x + 23, y, x + 24, y + 20, active ? LINK_COLOR : PANEL_BORDER_COLOR);
        drawCentered(graphics, value.isBlank() ? "?" : value, x + 12, y + 6, BODY_TEXT_COLOR);
    }

    private boolean insertConfiguredTable() {
        try {
            int rows = Integer.parseInt(tableRowsInput);
            int columns = Integer.parseInt(tableColumnsInput);
            if (rows < 1 || rows > 16 || columns < 1 || columns > 16) {
                status("表格行列范围为 1 到 16");
                return true;
            }
            return insertTable(rows, columns);
        } catch (NumberFormatException exception) {
            status("请输入 1 到 16 的行数和列数");
            return true;
        }
    }

    private boolean insertTable(int rows, int columns) {
        TextSelection insertion = currentSelection();
        if (insertion == null || !editor.insertTableAtSelection(insertion, rows, columns)) {
            closeToolbarMenu();
            status("表格只能插入当前一段文字中");
            return true;
        }
        editor.finishTextEdit();
        int tableIndex = tableBlockAtOrAfter(caretBlock);
        clearSelection();
        if (tableIndex >= 0) {
            tableCellCaret = new TableCellCaret(tableIndex, 0, 0, 0);
        }
        closeToolbarMenu();
        refreshAfterEdit();
        status("已插入 " + rows + "×" + columns + " 表格；点击单元格即可输入");
        return true;
    }

    private int tableBlockAtOrAfter(int startIndex) {
        List<Block> blocks = editor.document().blocks();
        for (int index = Math.max(0, startIndex); index < blocks.size(); index++) {
            if (blocks.get(index) instanceof TableBlock) {
                return index;
            }
        }
        return -1;
    }

    private boolean setFontSize(int size) {
        if (size < 6 || size > 72) {
            status("字号范围为 6 到 72");
            return true;
        }
        closeToolbarMenu();
        return applyTextFormat(s -> s.withFontSize(size));
    }

    private boolean setColor(int color) {
        if (toolbarMenu == ToolbarMenu.PAPER_COLOR) {
            paperColorRgb = color;
            closeToolbarMenu();
            try { appearanceStore.savePaperColor(color); }
            catch (IOException failure) { status("纸色已生效，但本地偏好保存失败：" + failure.getMessage()); }
            return true;
        }
        closeToolbarMenu();
        return applyTextFormat(s -> s.withColor(color));
    }

    private void closeToolbarMenu() {
        toolbarMenu = ToolbarMenu.NONE;
        fontSizeInput = "";
        colorInput = null;
    }

    private void nextPage() {
        int pages = NativeDocumentPresentation.pageCount(editor.document());
        if (pageIndex + 1 < pages) {
            showPage(pageIndex + 1);
            return;
        }
        if (mode.isEditable() && editor.appendEmptyPage()) {
            showPage(pageIndex + 1);
            clearSelection();
        }
    }

    private boolean handleTableCellKey(int keyCode, int modifiers) {
        if (tableCellCaret == null) return false;
        boolean control = (modifiers & GLFW.GLFW_MOD_CONTROL) != 0;
        boolean shift = (modifiers & GLFW.GLFW_MOD_SHIFT) != 0;
        String text = tableCellText(tableCellCaret);
        int from = Math.min(tableSelectionAnchor, tableCellCaret.column());
        int to = Math.max(tableSelectionAnchor, tableCellCaret.column());
        if (control) {
            if (keyCode == GLFW.GLFW_KEY_A) {
                tableSelectionAnchor = 0;
                tableCellCaret = tableCellCaret.withColumn(text.length());
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_C || keyCode == GLFW.GLFW_KEY_X) {
                if (from != to && minecraft != null) {
                    minecraft.keyboardHandler.setClipboard(text.substring(from, to));
                    if (keyCode == GLFW.GLFW_KEY_X) replaceTableCellAtCaret("");
                }
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_V && minecraft != null) {
                if (pasteClipboardImage()) return true;
                return replaceTableCellAtCaret(sanitiseTableInput(minecraft.keyboardHandler.getClipboard()));
            }
            if (keyCode != GLFW.GLFW_KEY_LEFT && keyCode != GLFW.GLFW_KEY_RIGHT
                    && keyCode != GLFW.GLFW_KEY_BACKSPACE && keyCode != GLFW.GLFW_KEY_DELETE
                    && keyCode != GLFW.GLFW_KEY_HOME && keyCode != GLFW.GLFW_KEY_END)
                return false; // Save, undo and format shortcuts use the shared dispatcher.
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            tableCellCaret = null;
            typingStyle = null;
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_BACKSPACE || keyCode == GLFW.GLFW_KEY_DELETE) {
            if (from == to) {
                int direction = keyCode == GLFW.GLFW_KEY_BACKSPACE ? -1 : 1;
                tableSelectionAnchor = control ? TextNavigation.word(text, from, direction)
                        : TextNavigation.character(text, from, direction);
            }
            return replaceTableCellAtCaret("");
        }
        if (keyCode == GLFW.GLFW_KEY_LEFT || keyCode == GLFW.GLFW_KEY_RIGHT
                || keyCode == GLFW.GLFW_KEY_HOME || keyCode == GLFW.GLFW_KEY_END) {
            editor.finishTextEdit();
            typingStyle = null;
            int next;
            if (keyCode == GLFW.GLFW_KEY_HOME) next = 0;
            else if (keyCode == GLFW.GLFW_KEY_END) next = text.length();
            else {
                int direction = keyCode == GLFW.GLFW_KEY_LEFT ? -1 : 1;
                next = !shift && from != to ? (direction < 0 ? from : to)
                        : control ? TextNavigation.word(text, tableCellCaret.column(), direction)
                        : TextNavigation.character(text, tableCellCaret.column(), direction);
            }
            tableCellCaret = tableCellCaret.withColumn(next);
            if (!shift) tableSelectionAnchor = next;
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_UP || keyCode == GLFW.GLFW_KEY_DOWN)
            return moveTableCell(keyCode == GLFW.GLFW_KEY_UP ? -1 : 1, 0);
        if (keyCode == GLFW.GLFW_KEY_TAB) return moveTableCell(0, shift ? -1 : 1);
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER)
            return replaceTableCellAtCaret("\n");
        return false;
    }

    private boolean replaceTableCellAtCaret(String replacement) {
        if (replacement == null || tableCellCaret == null) return true;
        int from = Math.min(tableSelectionAnchor, tableCellCaret.column());
        int to = Math.max(tableSelectionAnchor, tableCellCaret.column());
        if (replaceTableCellRange(from, to, replacement)) {
            tableCellCaret = tableCellCaret.withColumn(from + replacement.length());
            tableSelectionAnchor = tableCellCaret.column();
        }
        return true;
    }

    private boolean replaceTableCellRange(int start, int end, String replacement) {
        if (tableCellCaret == null) {
            return false;
        }
        boolean changed = editor.replaceTextInTableCell(tableCellCaret.blockIndex(), tableCellCaret.row(),
                tableCellCaret.tableColumn(), start, end, replacement, currentTypingStyle());
        if (changed) {
            refreshAfterEdit();
        }
        return changed;
    }

    private boolean moveTableCell(int rowDelta, int columnDelta) {
        if (tableCellCaret == null || tableCellCaret.blockIndex() < 0
                || tableCellCaret.blockIndex() >= editor.document().blocks().size()
                || !(editor.document().blocks().get(tableCellCaret.blockIndex()) instanceof TableBlock table)) {
            tableCellCaret = null;
            return true;
        }
        int row = tableCellCaret.row();
        int column = tableCellCaret.tableColumn();
        if (columnDelta != 0) {
            int flattened = row * table.columnCount() + column + columnDelta;
            int count = table.rows().size() * table.columnCount();
            if (flattened < 0) return true;
            if (flattened >= count) {
                if (!editor.appendTableRow(tableCellCaret.blockIndex())) return true;
                refreshAfterEdit();
            }
            row = flattened / table.columnCount();
            column = flattened % table.columnCount();
        } else {
            row = clamp(row + rowDelta, 0, table.rows().size() - 1);
        }
        int nextColumn = Math.min(tableCellText(tableCellCaret.blockIndex(), row, column).length(),
                tableCellCaret.column());
        editor.finishTextEdit();
        typingStyle = null;
        tableCellCaret = new TableCellCaret(tableCellCaret.blockIndex(), row, column, nextColumn);
        tableSelectionAnchor = nextColumn;
        return true;
    }

    private String tableCellText(TableCellCaret caret) {
        return tableCellText(caret.blockIndex(), caret.row(), caret.tableColumn());
    }

    private String tableCellText(int blockIndex, int row, int column) {
        if (blockIndex < 0 || blockIndex >= editor.document().blocks().size()
                || !(editor.document().blocks().get(blockIndex) instanceof TableBlock table)
                || row < 0 || row >= table.rows().size() || column < 0 || column >= table.columnCount()) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (TextRun run : table.rows().get(row).cells().get(column).runs()) {
            text.append(run.text());
        }
        return text.toString();
    }

    private static String sanitiseTableInput(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        StringBuilder safe = new StringBuilder(input.length());
        for (int offset = 0; offset < input.length();) {
            int codePoint = input.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (codePoint == '\r') {
                if (offset >= input.length() || input.charAt(offset) != '\n') safe.append('\n');
            } else if (codePoint == '\n') {
                safe.append('\n');
            } else if (codePoint == '\t') {
                safe.append(' ');
            } else if (!Character.isISOControl(codePoint)) {
                safe.appendCodePoint(codePoint);
            }
        }
        return safe.toString();
    }

    private boolean replaceCurrentSelection(String replacement) {
        if (!hasEditableCaret()) {
            status("请先点击要输入的位置");
            return true;
        }
        TextSelection selection = currentSelection();
        NormalizedScreenSelection normalized = normalizeScreenSelection(selection);
        TextStyle insertionStyle = currentTypingStyle();
        if (normalized == null || !editor.replaceTextSelection(selection, replacement, insertionStyle)) {
            if (normalized != null && normalized.crossesStructuralBlock(editor.document())) {
                status("不能一次修改跨过图片、表格或分页的文字");
            }
            return true;
        }
        typingStyle = insertionStyle;
        String[] lines = replacement.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        caretBlock = normalized.startBlock() + lines.length - 1;
        caretColumn = lines.length == 1 ? normalized.startColumn() + lines[0].length() : lines[lines.length - 1].length();
        selectionAnchorBlock = caretBlock;
        selectionAnchorColumn = caretColumn;
        refreshAfterEdit();
        return true;
    }

    private boolean selectCurrentPageText() {
        DocumentPages.PageRange page = DocumentPages.range(editor.document(), pageIndex);
        int first = -1;
        int last = -1;
        for (int index = page.startInclusive(); index < page.endExclusive(); index++) {
            if (isEditableBlock(index)) {
                if (first < 0) {
                    first = index;
                }
                last = index;
            }
        }
        if (first < 0) {
            status("本页没有可选择的文字");
            return true;
        }
        selectionAnchorBlock = first;
        selectionAnchorColumn = 0;
        caretBlock = last;
        caretColumn = WikiDocumentEditor.blockText(editor.document().blocks().get(last)).length();
        selectedImageBlock = -1;
        return true;
    }

    private boolean copySelection() {
        String text = selectedText();
        if (text == null) {
            status("请先框选要复制的文字");
            return true;
        }
        if (minecraft != null) {
            boolean copied = NativeRichClipboard.copy(minecraft.keyboardHandler::setClipboard,
                    minecraft.keyboardHandler::getClipboard, text, editor.copyTextFragment(currentSelection()));
            status(copied ? "已复制（完整行保留块格式）" : "剪贴板暂时被占用，原文未改变，请重试");
        }
        return true;
    }

    private boolean cutSelection() {
        String text = selectedText();
        if (text == null) {
            status("请先框选要剪切的文字");
            return true;
        }
        NormalizedScreenSelection normalized = normalizeScreenSelection(currentSelection());
        if (normalized != null && normalized.crossesStructuralBlock(editor.document())) {
            status("不能一次剪切跨过图片、表格或分页的文字");
            return true;
        }
        if (minecraft != null) {
            if (!NativeRichClipboard.copy(minecraft.keyboardHandler::setClipboard,
                    minecraft.keyboardHandler::getClipboard, text, editor.copyTextFragment(currentSelection()))) {
                status("剪贴板暂时被占用，未剪切原文，请重试");
                return true;
            }
        }
        return replaceCurrentSelection("");
    }

    private boolean pasteClipboard() {
        if (minecraft != null && hasEditableCaret()) {
            TextFragment fragment = NativeRichClipboard.matching(NativeRichClipboard.readText(minecraft.keyboardHandler::getClipboard));
            if (fragment != null) {
                WikiDocumentEditor.PasteResult pasted = editor.pasteTextFragment(currentSelection(), fragment);
                if (pasted == null) status("不能一次粘贴跨过图片、表格或分页的文字");
                else {
                    placeCaret(new Caret(pasted.blockIndex(), pasted.column()), false);
                    refreshAfterEdit();
                }
                return true;
            }
        }
        if (pasteClipboardImage()) return true;
        if (!hasEditableCaret()) {
            status("请先点击要粘贴的位置");
            return true;
        }
        if (minecraft == null) {
            return true;
        }
        String clipboard = NativeRichClipboard.readText(minecraft.keyboardHandler::getClipboard);
        if (clipboard == null || clipboard.isEmpty()) {
            return true;
        }
        // Keep paragraph breaks, but discard controls that cannot be part of
        // a document and would make a GUI/font renderer inconsistent.
        StringBuilder safe = new StringBuilder(clipboard.length());
        for (int offset = 0; offset < clipboard.length(); ) {
            int codePoint = clipboard.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (codePoint == '\n' || codePoint == '\r' || codePoint == '\t' || !Character.isISOControl(codePoint)) {
                safe.appendCodePoint(codePoint == '\t' ? ' ' : codePoint);
            }
        }
        return replaceCurrentSelection(safe.toString());
    }

    private String selectedText() {
        NormalizedScreenSelection selection = normalizeScreenSelection(currentSelection());
        if (selection == null || !hasSelection()) {
            return null;
        }
        StringBuilder copied = new StringBuilder();
        boolean appendedTextBlock = false;
        for (int index = selection.startBlock(); index <= selection.endBlock(); index++) {
            Block block = editor.document().blocks().get(index);
            if (!(block instanceof TextualBlock)) {
                continue;
            }
            if (appendedTextBlock) {
                copied.append('\n');
            }
            String text = WikiDocumentEditor.blockText(block);
            int start = index == selection.startBlock() ? selection.startColumn() : 0;
            int end = index == selection.endBlock() ? selection.endColumn() : text.length();
            copied.append(text, start, end);
            appendedTextBlock = true;
        }
        return copied.toString();
    }

    private boolean deleteBeforeCaret() {
        if (!hasEditableCaret()) {
            return true;
        }
        if (hasSelection()) {
            return replaceCurrentSelection("");
        }
        String text = WikiDocumentEditor.blockText(editor.document().blocks().get(caretBlock));
        if (caretColumn <= 0) {
            int previousBlock = caretBlock - 1;
            if (editor.deleteHorizontalRule(previousBlock)) {
                caretBlock--; selectionAnchorBlock=caretBlock; refreshAfterEdit(); return true;
            }
            if (previousBlock >= 0 && editor.document().blocks().get(previousBlock) instanceof TextualBlock previous) {
                int previousLength = WikiDocumentEditor.blockText(previous).length();
                if (editor.mergeTextBlockWithPrevious(caretBlock)) {
                    caretBlock = previousBlock;
                    caretColumn = previousLength;
                    selectionAnchorBlock = previousBlock;
                    selectionAnchorColumn = previousLength;
                    refreshAfterEdit();
                }
            }
            return true;
        }
        int previous = TextNavigation.character(text, caretColumn, -1);
        editor.replaceTextInBlock(caretBlock, previous, caretColumn, "");
        caretColumn = previous;
        selectionAnchorColumn = previous;
        refreshAfterEdit();
        return true;
    }

    private boolean deleteAfterCaret() {
        if (!hasEditableCaret()) {
            return true;
        }
        if (hasSelection()) {
            return replaceCurrentSelection("");
        }
        String text = WikiDocumentEditor.blockText(editor.document().blocks().get(caretBlock));
        if (caretColumn >= text.length()) {
            if (editor.deleteHorizontalRule(caretBlock+1)) { refreshAfterEdit(); return true; }
            if (editor.mergeTextBlockWithNext(caretBlock)) {
                selectionAnchorBlock = caretBlock;
                selectionAnchorColumn = caretColumn;
                refreshAfterEdit();
            }
            return true;
        }
        int next = TextNavigation.character(text, caretColumn, 1);
        editor.replaceTextInBlock(caretBlock, caretColumn, next, "");
        refreshAfterEdit();
        return true;
    }

    private boolean splitAtCaret() {
        editor.finishTextEdit();
        if (hasTextSelection()) {
            NormalizedScreenSelection range = normalizeScreenSelection(currentSelection());
            if (range == null || range.crossesStructuralBlock(editor.document())) return true;
            replaceCurrentSelection("");
        }
        if(hasEditableCaret() && !hasTextSelection() && editor.document().blocks().get(caretBlock) instanceof RichTextBlock r) {
            if (editor.exitFrame(caretBlock)) { refreshAfterEdit(); return true; }
            if(r.kind()==RichTextBlock.Kind.CODE) return insertSoftBreak();
            if(WikiDocumentEditor.blockText(r).isEmpty() && r.kind()!=RichTextBlock.Kind.NORMAL) {
                return formatParagraph(p -> p.withPresentation(p.isTask() ? RichTextBlock.Kind.TASK : p.kind(), false));
            }
            if(editor.splitTextBlock(caretBlock,caretColumn)) {
                placeCaret(new Caret(caretBlock+1,0),false);refreshAfterEdit();return true;
            }
        }
        replaceCurrentSelection("\n");
        editor.finishTextEdit();
        return true;
    }

    private boolean insertSoftBreak() {
        if (!hasEditableCaret()) return true;
        if (normalizeScreenSelection(currentSelection()).crossesStructuralBlock(editor.document())) return true;
        // A soft line break is a newline in the same paragraph, not a new heading/paragraph.
        if (hasSelection()) replaceCurrentSelection("");
        editor.replaceTextInBlock(caretBlock, caretColumn, caretColumn, "\n");
        caretColumn++;
        selectionAnchorColumn = caretColumn;
        refreshAfterEdit();
        return true;
    }

    private boolean moveCaret(int direction, boolean extendSelection) {
        return moveCaret(direction, extendSelection, false);
    }

    private boolean moveCaret(int direction, boolean extendSelection, boolean word) {
        if (!hasEditableCaret()) return true;
        if (hasSelection() && !extendSelection) {
            NormalizedScreenSelection selection = normalizeScreenSelection(currentSelection());
            placeCaret(direction < 0 ? new Caret(selection.startBlock(), selection.startColumn())
                    : new Caret(selection.endBlock(), selection.endColumn()), false);
        } else {
            String text = WikiDocumentEditor.blockText(editor.document().blocks().get(caretBlock));
            if (direction < 0 && caretColumn == 0 || direction > 0 && caretColumn == text.length()) {
                DocumentPages.PageRange page = DocumentPages.range(editor.document(), pageIndex);
                for (int index = caretBlock + direction; index >= page.startInclusive() && index < page.endExclusive(); index += direction) {
                    if (isEditableBlock(index)) {
                        placeCaret(new Caret(index, direction < 0
                                ? WikiDocumentEditor.blockText(editor.document().blocks().get(index)).length() : 0), extendSelection);
                        break;
                    }
                }
            } else {
                int next = word ? TextNavigation.word(text, caretColumn, direction)
                        : TextNavigation.character(text, caretColumn, direction);
                placeCaret(new Caret(caretBlock, next), extendSelection);
            }
        }
        revealCaret();
        return true;
    }

    private LayoutLine caretVisualLine() {
        LayoutLine found = null;
        for (LayoutLine line : layoutLines) {
            if (line.blockIndex() == caretBlock && caretColumn >= line.startColumn() && caretColumn <= line.endColumn()) {
                found = line; // At a soft-wrap boundary use the following line, never two blinking carets.
            }
        }
        return found;
    }

    private float caretX(LayoutLine line) {
        for (Fragment fragment : line.fragments()) {
            if (caretColumn <= fragment.startColumn()) return fragment.x();
        }
        return line.fragments().isEmpty() ? line.indent()
                : line.fragments().getLast().x() + line.fragments().getLast().advance();
    }

    private int hitLine(LayoutLine line, float x) {
        for (Fragment fragment : line.fragments()) {
            if (x < fragment.x() + fragment.advance() / 2) return fragment.startColumn();
        }
        return line.endColumn();
    }

    private boolean moveCaretVertically(int direction, boolean extendSelection) {
        ensureLayout(bounds().bodyWidth());
        LayoutLine current = caretVisualLine();
        if (current == null) return true;
        float x = preferredCaretX == null ? caretX(current) : preferredCaretX;
        int index = layoutLines.indexOf(current) + direction;
        while (index >= 0 && index < layoutLines.size()) {
            LayoutLine target = layoutLines.get(index);
            if (isEditableBlock(target.blockIndex())) {
                placeCaret(new Caret(target.blockIndex(), hitLine(target, x)), extendSelection);
                preferredCaretX = x;
                break;
            }
            index += direction;
        }
        revealCaret();
        return true;
    }

    private boolean moveCaretToBoundary(boolean end, boolean extendSelection) {
        if (!hasEditableCaret()) return true;
        ensureLayout(bounds().bodyWidth());
        LayoutLine line = caretVisualLine();
        if (line != null) placeCaret(new Caret(caretBlock, end ? line.endColumn() : line.startColumn()), extendSelection);
        revealCaret();
        return true;
    }

    private boolean moveCaretToPageBoundary(boolean end, boolean extendSelection) {
        DocumentPages.PageRange page = DocumentPages.range(editor.document(), pageIndex);
        for (int index = end ? page.endExclusive() - 1 : page.startInclusive();
                index >= page.startInclusive() && index < page.endExclusive(); index += end ? -1 : 1) {
            if (isEditableBlock(index)) {
                placeCaret(new Caret(index, end ? WikiDocumentEditor.blockText(editor.document().blocks().get(index)).length() : 0), extendSelection);
                break;
            }
        }
        revealCaret();
        return true;
    }

    private void revealCaret() {
        Bounds bounds = bounds();
        ensureLayout(bounds.bodyWidth());
        LayoutLine line = caretVisualLine();
        if (line != null) {
            int viewport = bounds.footerY() - bounds.bodyTop();
            if (line.y() < bodyScroll) bodyScroll = line.y();
            else if (line.y() + line.height() > bodyScroll + viewport)
                bodyScroll = line.y() + line.height() - viewport;
            bodyScroll = clamp(bodyScroll, 0, maxBodyScroll(bounds));
        }
    }

    private ImageLayout imageAt(Bounds bounds, double mouseX, double mouseY) {
        return imageAt(bounds, mouseX, mouseY, false);
    }

    private ImageLayout imageAt(Bounds bounds, double mouseX, double mouseY, boolean explicitPick) {
        if (!inRect(mouseX, mouseY, bounds.bodyX(), bounds.bodyTop(), bounds.bodyWidth(),
                bounds.footerY() - bounds.bodyTop())) return null;
        ensureLayout(bounds.bodyWidth());
        int documentY = (int) mouseY - bounds.bodyTop() + bodyScroll;
        ImageLayout picked = null;
        int highestLayer = -1;
        for (ImageLayout image : imageLayouts) {
            ImageVisual visual = imageVisual(image);
            int x = bounds.bodyX() + visual.xOffset();
            boolean handle = image.blockIndex() == selectedImageBlock && resizeHandleAt(bounds, image, mouseX, mouseY) != null;
            if (!handle && !inRect(mouseX, documentY, x, visual.y(), visual.width(), visual.height())) continue;
            if (image.layoutMode() == ImageLayoutMode.FLOATING && !explicitPick && !hasAltDown()
                    && image.blockIndex() != selectedImageBlock && textPaintedAt((float) mouseX - bounds.bodyX(), documentY))
                continue; // Behind-text pictures must not steal ordinary prose clicks.
            int layer = handle ? 4 : image.layoutMode() == ImageLayoutMode.COVER ? 3
                    : image.layoutMode() == ImageLayoutMode.FLOATING ? 0 : 2;
            if (layer >= highestLayer) { picked = image; highestLayer = layer; }
        }
        return picked;
    }

    private boolean textPaintedAt(float x, int y) {
        for (LayoutLine line : layoutLines) {
            if (y < line.y() || y >= line.y() + line.height()) continue;
            for (Fragment glyph : line.fragments())
                if (x >= glyph.x() && x < glyph.x() + glyph.advance()) return true;
        }
        return false;
    }

    private TableCellHit tableCellAt(Bounds bounds, double mouseX, double mouseY) {
        ensureLayout(bounds.bodyWidth());
        int documentY = (int) mouseY - bounds.bodyTop() + bodyScroll;
        for (TableLayout table : tableLayouts) {
            if (documentY < table.y() || documentY >= table.y() + table.totalHeight()
                    || mouseX < bounds.bodyX() + table.xOffset() || mouseX >= bounds.bodyX() + table.xOffset() + table.width()) {
                continue;
            }
            int row = table.rowAt(documentY - table.y());
            TableRowLayout rowLayout = table.rowLayouts().get(row);
            int column = Math.min(table.columns() - 1,
                    Math.max(0, ((int) mouseX - bounds.bodyX() - table.xOffset()) / table.baseColumnWidth()));
            int cellX = bounds.bodyX() + table.xOffset() + column * table.baseColumnWidth() + TABLE_CELL_PADDING;
            int cellRight = bounds.bodyX() + table.xOffset() + (column == table.columns() - 1
                    ? table.width() : (column + 1) * table.baseColumnWidth()) - TABLE_CELL_PADDING;
            int localY = documentY - table.y() - rowLayout.yOffset() - TABLE_CELL_PADDING;
            int caret = tableCellCaretAt(rowLayout.cells().get(column), cellX, cellRight,
                    localY, mouseX);
            return new TableCellHit(table.blockIndex(), row, column, caret);
        }
        return null;
    }

    private int tableCellCaretAt(TableCellLayout cell, int x, int right, int localY, double mouseX) {
        TableCellLine line = cell.lineAt(localY);
        int cursorX = x;
        for (Fragment fragment : line.fragments()) {
            cursorX = x + Math.round(fragment.x());
            int characterWidth = measuredFragmentWidth(fragment);
            if (cursorX + characterWidth > right || mouseX < cursorX + characterWidth / 2.0) {
                return fragment.startColumn();
            }
            cursorX += characterWidth;
        }
        return line.endColumn();
    }

    private Caret caretAt(Bounds bounds, double mouseX, double mouseY) {
        ensureLayout(bounds.bodyWidth());
        int documentY = (int) mouseY - bounds.bodyTop() + bodyScroll;
        LayoutLine nearest = null;
        int nearestDistance = Integer.MAX_VALUE;
        for (LayoutLine line : layoutLines) {
            if (line.blockIndex() < 0) {
                continue;
            }
            int distance = documentY < line.y() ? line.y() - documentY
                    : documentY > line.y() + line.height() ? documentY - (line.y() + line.height()) : 0;
            if (distance < nearestDistance) {
                nearest = line;
                nearestDistance = distance;
            }
        }
        if (nearest == null || !isEditableBlock(nearest.blockIndex())) {
            return null;
        }
        int cursorX = bounds.bodyX() + nearest.indent();
        for (Fragment fragment : nearest.fragments()) {
            int characterWidth = measuredFragmentWidth(fragment);
            cursorX = bounds.bodyX() + Math.round(fragment.x());
            if (mouseX < cursorX + characterWidth / 2.0) {
                return new Caret(nearest.blockIndex(), fragment.startColumn());
            }
            cursorX += characterWidth;
        }
        return new Caret(nearest.blockIndex(), nearest.endColumn());
    }

    private void placeCaret(Caret next, boolean extendSelection) {
        if (!isEditableBlock(next.blockIndex())) {
            return;
        }
        if (collapsedBlocks.contains(next.blockIndex())) return;
        editor.finishTextEdit();
        typingStyle = null;
        preferredCaretX = null;
        selectedImageBlock = -1;
        // A table cell owns its own caret.  Clicking ordinary prose must always
        // hand control back to the document caret instead of leaving keyboard
        // input trapped in the last table cell that was edited.
        tableCellCaret = null;
        if (!extendSelection || !isEditableBlock(selectionAnchorBlock)) {
            selectionAnchorBlock = next.blockIndex();
            selectionAnchorColumn = next.column();
        }
        caretBlock = next.blockIndex();
        caretColumn = next.column();
    }

    private TextSelection currentSelection() {
        if (!isEditableBlock(caretBlock) || !isEditableBlock(selectionAnchorBlock)) {
            return null;
        }
        return new TextSelection(selectionAnchorBlock, selectionAnchorColumn, caretBlock, caretColumn);
    }

    private boolean hasEditableCaret() {
        return isEditableBlock(caretBlock) && isEditableBlock(selectionAnchorBlock);
    }

    private boolean isEditableBlock(int blockIndex) {
        return blockIndex >= 0 && blockIndex < editor.document().blocks().size()
                && editor.document().blocks().get(blockIndex) instanceof TextualBlock;
    }

    private boolean hasSelection() {
        return hasEditableCaret() && (caretBlock != selectionAnchorBlock || caretColumn != selectionAnchorColumn);
    }

    private boolean hasTextSelection() {
        return hasSelection();
    }

    private static NormalizedScreenSelection normalizeScreenSelection(TextSelection selection) {
        if (selection == null) {
            return null;
        }
        if (selection.startLine() < selection.endLine()
                || selection.startLine() == selection.endLine() && selection.startColumn() <= selection.endColumn()) {
            return new NormalizedScreenSelection(selection.startLine(), selection.startColumn(),
                    selection.endLine(), selection.endColumn());
        }
        return new NormalizedScreenSelection(selection.endLine(), selection.endColumn(),
                selection.startLine(), selection.startColumn());
    }

    private boolean isFragmentSelected(LayoutLine line, Fragment fragment) {
        NormalizedScreenSelection selection = normalizeScreenSelection(currentSelection());
        if (selection == null || line.blockIndex() < selection.startBlock() || line.blockIndex() > selection.endBlock()) {
            return false;
        }
        int start = line.blockIndex() == selection.startBlock() ? selection.startColumn() : 0;
        int end = line.blockIndex() == selection.endBlock()
                ? selection.endColumn()
                : WikiDocumentEditor.blockText(editor.document().blocks().get(line.blockIndex())).length();
        return fragment.startColumn() < end && fragment.endColumn() > start;
    }

    private boolean isCaretAt(int blockIndex, int column) {
        return mode.isEditable() && hasEditableCaret() && caretBlock == blockIndex && caretColumn == column;
    }

    private void drawCaret(GuiGraphics graphics, int x, int y, int lineHeight) {
        if ((System.currentTimeMillis() / 500L) % 2L == 0L) {
            graphics.fill(x, y - 1, x + 1, y + Math.max(8, lineHeight - 1), CARET_COLOR);
        }
    }

    private void clearSelection() {
        tableContextMenu = null;
        typingStyle = null;
        preferredCaretX = null;
        selectingTableText = false;
        tableSelectionAnchor = 0;
        caretBlock = -1;
        caretColumn = 0;
        selectionAnchorBlock = -1;
        selectionAnchorColumn = 0;
        selectingWithMouse = false;
        selectedImageBlock = -1;
        imageResize = null;
        imageMove = null;
        tableCellCaret = null;
    }

    private String annotationStorageKey() {
        String namespace = session.personalAnnotationKey();
        if (annotationKeyDocument != editor.document() || !namespace.equals(annotationKeyNamespace)) {
            annotationKeyDocument = editor.document();
            annotationKeyNamespace = namespace;
            cachedAnnotationKey = LocalAnnotationStore.versionKey(namespace,
                    new WikiDocumentCodec().contentHash(annotationKeyDocument));
        }
        return cachedAnnotationKey;
    }

    private void saveCurrentDocument() {
        if (saveInProgress) { status("正在保存，请稍候…"); return; }
        finishTitleEditing();
        closeCommentComposer();
        editor.finishTextEdit();
        try {
            annotationStore.saveTextAnnotations(annotationStorageKey(), annotations);
        } catch (IOException exception) {
            status("本地批注保存失败，正文尚未提交");
            closeAfterSave = false;
            return;
        }
        WikiDocument snapshot = editor.document();
        String snapshotHash = new WikiDocumentCodec().contentHash(snapshot);
        saveInProgress = true;
        status(closeAfterSave ? "正在自动保存，完成后退出…" : "正在保存…");
        try {
            session.saveConfirmed(snapshot).whenComplete((result, error) -> minecraft.execute(() -> {
                saveInProgress = false;
                if (error != null || result == null || !result.success()) {
                    closeAfterSave = false;
                    status(error != null ? "保存未完成（连接或写入失败），内容仍保留，请重试"
                            : result == null ? "未收到保存确认，内容仍保留" : result.message());
                    return;
                }
                savedContentHash = snapshotHash;
                status(result.message());
                if (closeAfterSave) {
                    if (hasUnsavedContent()) saveCurrentDocument();
                    else {
                        closeAfterSave = false;
                        if (minecraft.screen == this) super.onClose();
                    }
                }
            }));
        } catch (RuntimeException failure) {
            saveInProgress = false;
            closeAfterSave = false;
            status("保存失败，编辑内容仍保留：" + failure.getMessage());
        }
    }

    private void rebaseAnnotations() {
        WikiDocument next = editor.document();
        if (annotationDocument == next) return;
        List<LocalTextAnnotation> rebased = sortAnnotations(annotationHistory.rebase(
                annotationDocument, next, annotations, editor.retainedDocuments()));
        annotationDocument = next;
        if (!rebased.equals(annotations)) {
            annotations = rebased;
            selectedAnnotationIndex = -1;
            // These are draft anchors. Do not overwrite the original book's
            // comments until this content version is explicitly saved.

        }
    }

    private void refreshAfterEdit() {
        refreshDocument();
        List<WikiDocument> retained = editor.retainedDocuments();
        automaticListCarets.keySet().removeIf(version -> retained.stream().noneMatch(root -> root == version));
        Caret autoformatCaret = automaticListCarets.get(editor.document());
        if (autoformatCaret != null) placeCaret(autoformatCaret, false);
        if (isEditableBlock(caretBlock))
            caretColumn = clamp(caretColumn, 0, WikiDocumentEditor.blockText(editor.document().blocks().get(caretBlock)).length());
        else caretBlock = -1;
        if (isEditableBlock(selectionAnchorBlock))
            selectionAnchorColumn = clamp(selectionAnchorColumn, 0,
                    WikiDocumentEditor.blockText(editor.document().blocks().get(selectionAnchorBlock)).length());
        else selectionAnchorBlock = caretBlock;
        if (tableCellCaret != null) {
            int index = tableCellCaret.blockIndex();
            if (index < 0 || index >= editor.document().blocks().size()
                    || !(editor.document().blocks().get(index) instanceof TableBlock table)
                    || tableCellCaret.row() >= table.rows().size() || tableCellCaret.tableColumn() >= table.columnCount()) {
                tableCellCaret = null;
            } else {
                int length = tableCellText(tableCellCaret).length();
                tableCellCaret = tableCellCaret.withColumn(Math.min(length, tableCellCaret.column()));
                tableSelectionAnchor = Math.min(length, tableSelectionAnchor);
            }
        }
        revealCaret();
    }

    private void drawButton(GuiGraphics graphics, int x, int y, int buttonWidth, int buttonHeight, String label,
            boolean hovered) {
        // Hit rectangles stay forgiving, but the visual controls deliberately
        // use a compact Word-like chrome instead of large Minecraft buttons.
        int inset = buttonWidth > 24 ? 1 : 2;
        int visualWidth = Math.max(font.width(label) + 7, buttonWidth - inset * 2);
        visualWidth = Math.min(buttonWidth, visualWidth);
        int visualHeight = Math.min(16, buttonHeight);
        int visualX = x + (buttonWidth - visualWidth) / 2;
        int visualY = y + (buttonHeight - visualHeight) / 2;
        graphics.fill(visualX, visualY, visualX + visualWidth, visualY + visualHeight,
                hovered ? BUTTON_HOVER_COLOR : BUTTON_COLOR);
        graphics.fill(visualX + 1, visualY + 1, visualX + visualWidth - 1, visualY + 2, 0xFF93AD91);
        drawCentered(graphics, label, visualX + visualWidth / 2,
                visualY + Math.max(2, (visualHeight - font.lineHeight) / 2), 0xFFFFFFFF);
    }

    private void drawCentered(GuiGraphics graphics, String text, int centreX, int y, int color) {
        graphics.drawString(font, text, centreX - font.width(text) / 2, y, color, false);
    }

    private void drawRight(GuiGraphics graphics, String text, int right, int y, int color) {
        graphics.drawString(font, text, right - font.width(text), y, color, false);
    }

    private List<NativeDocumentPresentation.TableOfContentsEntry> directory() {
        return NativeDocumentPresentation.tableOfContents(editor.document());
    }

    /** Opens the heading's authored page and scrolls its exact block into view. */
    private void showDirectoryEntry(NativeDocumentPresentation.TableOfContentsEntry entry) {
        Objects.requireNonNull(entry, "entry");
        showPage(entry.pageIndex());
        Bounds bounds = bounds();
        ensureLayout(bounds.bodyWidth());
        for (LayoutLine line : layoutLines) {
            if (line.blockIndex() == entry.blockIndex()) {
                bodyScroll = clamp(Math.max(0, line.y() - 3), 0, maxBodyScroll(bounds));
                return;
            }
        }
        // A structurally valid directory entry must be on this page, but a
        // malformed imported file should still leave the reader somewhere sane.
        bodyScroll = 0;
    }

    private int maxBodyScroll(Bounds bounds) {
        return Math.max(0, contentHeight - Math.max(1, bounds.footerY() - bounds.bodyTop() - BODY_BOTTOM_GAP));
    }

    private record BodyScrollbar(ContentControl track, int thumbY, int thumbHeight, int maximum) { }

    private BodyScrollbar bodyScrollbar(Bounds b) {
        ensureLayout(b.bodyWidth());
        int maximum = maxBodyScroll(b);
        if (maximum == 0) return null;
        int height = Math.max(1, b.footerY() - b.bodyTop() - 2);
        ContentControl track = new ContentControl(b.bodyX() + b.bodyVisibleWidth(), b.bodyTop() + 1, 4, height);
        int viewport = Math.max(1, b.footerY() - b.bodyTop() - BODY_BOTTOM_GAP);
        int thumb = Math.min(height, Math.max(12, (int)((long)height * viewport / Math.max(1, contentHeight))));
        int thumbY = track.y() + (int)((long)clamp(bodyScroll, 0, maximum) * (height - thumb) / maximum);
        return new BodyScrollbar(track, thumbY, thumb, maximum);
    }

    private void drawBodyScrollbar(GuiGraphics graphics, Bounds b, int mouseX, int mouseY) {
        BodyScrollbar bar = bodyScrollbar(b);
        if (bar == null) return;
        ContentControl t = bar.track();
        graphics.fill(t.x(), t.y(), t.x()+t.width(), t.y()+t.height(), 0xFFD9CCAF);
        graphics.fill(t.x(), bar.thumbY(), t.x()+t.width(), bar.thumbY()+bar.thumbHeight(),
                bodyScrollDragOffset >= 0 || t.contains(mouseX,mouseY) ? 0xFF416F64 : 0xFF819782);
    }

    private boolean handleBodyScrollbarClick(Bounds b, double x, double y) {
        BodyScrollbar bar = bodyScrollbar(b);
        if (bar == null || !bar.track().contains(x,y)) return false;
        editor.finishTextEdit();
        selectingWithMouse = false;
        bodyScrollDragOffset = y >= bar.thumbY() && y < bar.thumbY()+bar.thumbHeight()
                ? (int)y-bar.thumbY() : bar.thumbHeight()/2;
        updateBodyScrollbar(b,y);
        return true;
    }

    private void updateBodyScrollbar(Bounds b, double y) {
        BodyScrollbar bar = bodyScrollbar(b);
        if (bar == null) { bodyScrollDragOffset=-1; return; }
        int travel = bar.track().height()-bar.thumbHeight();
        if (travel <= 0) { bodyScroll=0; return; }
        double fraction = Math.max(0,Math.min(1,(y-bar.track().y()-bodyScrollDragOffset)/travel));
        bodyScroll = (int)Math.round(fraction*bar.maximum());
    }

    private int maxDirectoryScroll(Bounds bounds) {
        int directoryHeight = directory().size() * directoryLineHeight() + 6;
        return Math.max(0, directoryHeight - Math.max(1, bounds.footerY() - bounds.bodyTop()));
    }

    private static int directoryLineHeight() {
        return 14;
    }

    private int clampPage(int requestedPage) {
        return clamp(requestedPage, 0, NativeDocumentPresentation.pageCount(editor.document()) - 1);
    }

    private void invalidateLayout() {
        laidOutDocument = null;
        laidOutPage = -1;
        laidOutImageTextureGeneration = -1L;
        layoutLines = List.of();
        imageLayouts = List.of();
        tableLayouts = List.of();
        contentHeight = 0;
    }

    private Bounds bounds() {
        // Fixed outer canvas and fixed authored paper width. Centre the visible
        // module group; sidebars affect positions only, never text wrapping.
        int panelWidth = Math.min(Math.max(1, width - 12), UI_CANVAS_WIDTH);
        int panelHeight = Math.min(UI_CANVAS_HEIGHT, Math.max(80, height - 42));
        int left = (width - panelWidth) / 2;
        int top = Math.max(0, (height - panelHeight) / 2);
        int bottom = top + panelHeight;
        int innerLeft = left + OUTER_PADDING;
        int innerRight = left + panelWidth - OUTER_PADDING;
        int desiredDirectoryWidth = directoryVisible
                ? clamp(directoryModuleWidth, MODULE_MIN_WIDTH, MODULE_MAX_WIDTH) : 0;
        int bodyPaperWidth = BODY_COLUMN_WIDTH;
        boolean wantsAnnotationPaper = annotationVisible && (commentComposerOpen || !annotations.isEmpty());
        int desiredAnnotationWidth = wantsAnnotationPaper
                ? clamp(annotationModuleWidth, MODULE_MIN_WIDTH, MODULE_MAX_WIDTH) : 0;
        // A smaller game viewport may temporarily fold a sidebar, but never
        // changes the user's saved paper widths when it becomes larger again.
        boolean directoryFits = directoryVisible && desiredDirectoryWidth + COLUMN_GAP + bodyPaperWidth <= innerRight - innerLeft;
        int directoryWidth = directoryFits ? desiredDirectoryWidth : 0;
        boolean annotationFits = wantsAnnotationPaper && bodyPaperWidth
                + (directoryFits ? directoryWidth + COLUMN_GAP : 0) + COLUMN_GAP + desiredAnnotationWidth <= innerRight - innerLeft;
        int groupWidth = bodyPaperWidth + (directoryFits ? directoryWidth + COLUMN_GAP : 0)
                + (annotationFits ? desiredAnnotationWidth + COLUMN_GAP : 0);
        int cursor = innerLeft + Math.max(0, (innerRight - innerLeft - groupWidth) / 2);
        int directoryX = -1;
        if (directoryFits) {
            directoryX = cursor;
            cursor += directoryWidth + COLUMN_GAP;
        }
        int bodyPaperX = cursor;
        int bodyX = bodyPaperX + PAPER_INSET;
        int bodyWidth = Math.max(1, bodyPaperWidth - PAPER_INSET * 2);
        cursor = bodyPaperX + bodyPaperWidth;
        int annotationX = -1;
        if (annotationFits) {
            cursor += COLUMN_GAP;
            annotationX = cursor;
        }
        return new Bounds(left, top, left + panelWidth, bottom,
                directoryFits, directoryX, directoryWidth,
                bodyX, bodyWidth,
                annotationFits, annotationX, annotationFits ? desiredAnnotationWidth : 0,
                top + currentHeaderHeight() + BODY_TOP_GAP, bottom - FOOTER_HEIGHT);
    }

    private boolean canShowAnnotationPaper() {
        int panelWidth = Math.min(Math.max(1, width - 12), UI_CANVAS_WIDTH);
        int innerWidth = panelWidth - OUTER_PADDING * 2;
        int directoryWidth = directoryVisible ? clamp(directoryModuleWidth, MODULE_MIN_WIDTH, MODULE_MAX_WIDTH) : 0;
        int bodyWidth = BODY_COLUMN_WIDTH;
        int annotationWidth = clamp(annotationModuleWidth, MODULE_MIN_WIDTH, MODULE_MAX_WIDTH);
        int gaps = (directoryVisible ? COLUMN_GAP : 0) + COLUMN_GAP;
        return directoryWidth + bodyWidth + annotationWidth + gaps <= innerWidth;
    }

    private static ModuleWidths fitModuleWidths(int desiredDirectoryWidth, int desiredAnnotationWidth,
            int availableWidth) {
        int visibleCount = (desiredDirectoryWidth > 0 ? 1 : 0) + (desiredAnnotationWidth > 0 ? 1 : 0);
        if (visibleCount == 0) {
            return new ModuleWidths(0, 0);
        }
        int desiredTotal = desiredDirectoryWidth + desiredAnnotationWidth;
        if (desiredTotal <= availableWidth) {
            return new ModuleWidths(desiredDirectoryWidth, desiredAnnotationWidth);
        }
        if (visibleCount == 1) {
            return desiredDirectoryWidth > 0
                    ? new ModuleWidths(Math.max(0, availableWidth), 0)
                    : new ModuleWidths(0, Math.max(0, availableWidth));
        }
        int minimumTotal = MODULE_MIN_WIDTH * 2;
        if (availableWidth <= minimumTotal) {
            int directory = Math.max(0, availableWidth / 2);
            return new ModuleWidths(directory, Math.max(0, availableWidth - directory));
        }
        int distributable = availableWidth - minimumTotal;
        int desiredExtraDirectory = desiredDirectoryWidth - MODULE_MIN_WIDTH;
        int desiredExtraAnnotation = desiredAnnotationWidth - MODULE_MIN_WIDTH;
        int desiredExtras = Math.max(1, desiredExtraDirectory + desiredExtraAnnotation);
        int directory = MODULE_MIN_WIDTH + Math.round((float) distributable * desiredExtraDirectory / desiredExtras);
        return new ModuleWidths(directory, availableWidth - directory);
    }

    private int currentHeaderHeight() {
        if (!mode.isEditable()) {
            return lockConfirmationOpen ? EDITOR_HEADER_HEIGHT : READER_HEADER_HEIGHT;
        }
        int toolbarBottom = 43 + toolbarRows(Math.max(1, Math.min(width - 12, UI_CANVAS_WIDTH) - 16)) * 20;
        return (toolbarMenu == ToolbarMenu.NONE || isColorMenu()) && !lockConfirmationOpen
                ? toolbarBottom + 4 : toolbarBottom + (isLinkMenu() ? 44 : 28);
    }

    private static boolean inRect(double mouseX, double mouseY, int x, int y, int rectWidth, int rectHeight) {
        return mouseX >= x && mouseX < x + rectWidth && mouseY >= y && mouseY < y + rectHeight;
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private String truncateToWidth(String text, int maximumWidth) {
        if (font.width(text) <= maximumWidth) {
            return text;
        }
        String ellipsis = "…";
        StringBuilder result = new StringBuilder();
        for (int offset = 0; offset < text.length(); ) {
            int codePoint = text.codePointAt(offset);
            String next = new String(Character.toChars(codePoint));
            if (font.width(result + next + ellipsis) > maximumWidth) {
                break;
            }
            result.append(next);
            offset += Character.charCount(codePoint);
        }
        return result + ellipsis;
    }

    private void status(String message) {
        statusText = message == null ? "" : message;
        statusUntil = System.currentTimeMillis() + 6500;
        if (minecraft != null && minecraft.player != null) {
            minecraft.player.displayClientMessage(Component.literal(message), true);
        }
    }

    private record Fragment(String text, TextStyle style, float scale, boolean link, int startColumn, int endColumn,
            float x, float advance, Component painted) {
    }

    private record LayoutLine(int blockIndex, int startColumn, int endColumn, int y, int height, int indent,
            boolean quote, List<Fragment> fragments) {
    }

    /** One independently sized, non-text document object on the current page. */
    private record ImageLayout(int blockIndex, int y, int xOffset, int width, int height, ImageAlignment alignment,
            ImageLayoutMode layoutMode, WikiImageTextures.LoadedImage loaded) {
    }

    private record ImageAppend(int nextY, TextFlowExclusion flowExclusion) {
    }

    /** A positioned wrapping image removes one middle span from later text lines. */
    private record TextFlowExclusion(int topY, int bottomY, int gapStart, int gapEnd) {
    }


    /**
     * Per-page table layout.  Rows and cells retain their visual lines so input
     * hit-testing uses exactly the same wrapped geometry as drawing.
     */
    private record TableLayout(int blockIndex, int y, int xOffset, int width, int baseColumnWidth, int columns,
            int totalHeight, List<TableRowLayout> rowLayouts, TableBlock table) {
        int columnWidth(int column) {
            return column == columns - 1 ? width - column * baseColumnWidth : baseColumnWidth;
        }

        int rowAt(int localY) {
            for (int row = 0; row < rowLayouts.size(); row++) {
                TableRowLayout layout = rowLayouts.get(row);
                if (localY < layout.yOffset() + layout.height()) {
                    return row;
                }
            }
            return Math.max(0, rowLayouts.size() - 1);
        }
    }

    private record TableRowLayout(int yOffset, int height, List<TableCellLayout> cells) {
    }

    private record TableCellLayout(List<TableCellLine> lines, int contentHeight) {
        TableCellLine lineAt(int localY) {
            if (lines.isEmpty()) {
                return new TableCellLine(0, 0, 0, 0, List.of());
            }
            for (TableCellLine line : lines) {
                if (localY < line.yOffset() + line.height()) {
                    return line;
                }
            }
            return lines.get(lines.size() - 1);
        }
    }

    private record TableCellLine(int yOffset, int height, int startColumn, int endColumn,
            List<Fragment> fragments) {
    }

    private record TableCellHit(int blockIndex, int row, int column, int caretColumn) {
    }

    private record TableCellCaret(int blockIndex, int row, int tableColumn, int column) {
        TableCellCaret withColumn(int nextColumn) {
            return new TableCellCaret(blockIndex, row, tableColumn, Math.max(0, nextColumn));
        }
    }

    private record ImageVisual(int blockIndex, int y, int xOffset, int width, int height) {
    }

    private record ImageResize(int blockIndex, ResizeHandle handle, int startX, int startY, int startWidth,
            int startHeight, int startImageX, int startImageY, int previewWidth) {
        ImageResize withPreviewWidth(int nextWidth) {
            return new ImageResize(blockIndex, handle, startX, startY, startWidth, startHeight,
                    startImageX, startImageY, nextWidth);
        }

        int previewX() {
            return handle.left() ? startImageX + startWidth - previewWidth : startImageX;
        }

        int previewY() {
            int height = Math.max(1, Math.round((float) startHeight * previewWidth / startWidth));
            return handle.top() ? startImageY + startHeight - height : startImageY;
        }
    }

    private record ImageMove(int blockIndex, int startMouseX, int startMouseY, int startImageX, int startImageY,
            int currentMouseX, int currentMouseY) {
        ImageMove(int blockIndex, int startMouseX, int startMouseY, int startImageX, int startImageY) {
            this(blockIndex, startMouseX, startMouseY, startImageX, startImageY, startMouseX, startMouseY);
        }

        ImageMove withCurrent(int x, int y) {
            return new ImageMove(blockIndex, startMouseX, startMouseY, startImageX, startImageY, x, y);
        }

        int deltaX() {
            return currentMouseX - startMouseX;
        }

        int deltaY() {
            return currentMouseY - startMouseY;
        }
    }

    private record TableContextMenu(int blockIndex, int row, int column, int x, int y) {
    }

    private record ImageContextMenu(int blockIndex, int x, int y) {
    }

    private record PendingAnnotation(int startBlock, int startColumn, int endBlock, int endColumn,
            String quotedText) {
    }

    private record AnnotationDisplayLine(int annotationIndex, boolean quoted, String text, int visualIndex) {
    }

    private record CommentInputLine(int start, int end, String text, int visualIndex) {
    }

    /** A saved review card or the one draft card currently being edited. */
    private record AnnotationCardSource(int annotationIndex, PendingAnnotation anchor, String comment,
            boolean composer) {
    }

    /** Precomputed visual rows keep drawing, click handling and scroll limits in agreement. */
    private record AnnotationCardLayout(int annotationIndex, boolean composer, List<String> quoteLines,
            List<String> commentLines, List<CommentInputLine> inputLines, int startRow, int quoteStartRow,
            int commentStartRow, int endRow) {
    }

    private record ModuleWidths(int directoryWidth, int annotationWidth) {
    }

    private enum ModuleDividerDrag {
        NONE,
        DIRECTORY,
        ANNOTATION
    }

    private enum ResizeHandle {
        NORTH_WEST(true, true),
        NORTH_EAST(false, true),
        SOUTH_WEST(true, false),
        SOUTH_EAST(false, false);

        private final boolean left;
        private final boolean top;

        ResizeHandle(boolean left, boolean top) {
            this.left = left;
            this.top = top;
        }

        boolean left() {
            return left;
        }

        boolean top() {
            return top;
        }
    }

    private record NormalizedScreenSelection(int startBlock, int startColumn, int endBlock, int endColumn) {
        boolean crossesStructuralBlock(WikiDocument document) {
            for (int index = startBlock; index <= endBlock; index++) {
                if (!(document.blocks().get(index) instanceof TextualBlock)) {
                    return true;
                }
            }
            return false;
        }
    }

    private record Caret(int blockIndex, int column) {
    }

    private enum ToolbarMenu {
        NONE,
        HEADING,
        FONT_SIZE,
        TABLE,
        COLOR,
        PAPER_COLOR,
        SORT, LINK, COMMAND
    }

    private enum TableSizeInput {
        ROWS,
        COLUMNS
    }

    private record Bounds(int left, int top, int right, int bottom,
            boolean directoryVisible, int directoryX, int directoryWidth,
            int bodyX, int bodyWidth,
            boolean annotationVisible, int annotationX, int annotationWidth,
            int bodyTop, int footerY) {
        int headerY() {
            return top + 4;
        }

        int modeX() {
            return left + 8;
        }

        int lockButtonX() {
            return right - 42;
        }

        int toolbarY() {
            return headerY() + 21;
        }

        int menuY() {
            return toolbarY() + 18 + toolbarRows(Math.max(1, right - left - 16)) * 20 + 2;
        }

        int lockConfirmationX() {
            return right - 100;
        }

        int lockConfirmationY(boolean editable) {
            return editable ? menuY() : toolbarY();
        }

        int previousX() {
            return bodyX;
        }

        int nextX() {
            return bodyX + bodyVisibleWidth() - 50;
        }

        int pageNumberWidth() {
            return 46;
        }

        int pageNumberX() {
            return bodyX + (bodyVisibleWidth() - pageNumberWidth()) / 2;
        }

        int bodyPaperX() {
            return bodyX - PAPER_INSET;
        }

        int bodyVisibleWidth() {
            // Extremely small viewports clip the fixed-width sheet rather than reflowing copied lines.
            return Math.max(1, Math.min(bodyWidth, right - OUTER_PADDING - PAPER_INSET - bodyX));
        }

        int bodyPaperWidth() {
            return bodyVisibleWidth() + PAPER_INSET * 2;
        }

        int directoryToggleX() {
            // Visible controls occupy their neighbouring inter-module gap.
            // Hidden controls occupy the panel's outer padding. Neither can
            // cover the directory, annotation or document text.
            return directoryVisible ? bodyX - COLUMN_GAP + 2 : left;
        }

        int annotationToggleX() {
            return annotationVisible ? annotationX - COLUMN_GAP + 2 : right - MODULE_TOGGLE_WIDTH;
        }

        int moduleToggleY() {
            return bodyTop + 2;
        }

        int directoryResizeX() {
            return directoryX + directoryWidth;
        }

        int annotationResizeX() {
            return annotationX + annotationWidth;
        }
    }
}
