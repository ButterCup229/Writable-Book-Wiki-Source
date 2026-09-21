package com.writablebookwiki.local;

import com.writablebookwiki.core.document.*;
import static org.junit.jupiter.api.Assertions.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.zip.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocxIOTest {
    @TempDir Path root;
    private DocumentMetadata metadata() {UUID id=new UUID(0,1);return new DocumentMetadata(id,"测试",Instant.EPOCH,id,"测试",Instant.EPOCH);}
    private TextRun run(String text) {return new TextRun(text,TextStyle.DEFAULT);}
    @Test void realDocxRoundTripIncludesStylesListsTablePageLinksAndEmbeddedImage() throws Exception {
        var store=new LocalImageAssetStore(root.resolve("assets"));Path png=root.resolve("sample.png");
        BufferedImage image=new BufferedImage(120,60,BufferedImage.TYPE_INT_RGB);
        var g=image.createGraphics();g.setColor(new java.awt.Color(0x426D9E));g.fillRect(0,0,120,60);g.setColor(java.awt.Color.WHITE);g.drawString("Wiki Word",20,35);g.dispose();ImageIO.write(image,"png",png.toFile());
        var asset=store.importImage(png);
        List<Block> blocks=new ArrayList<>();blocks.add(new Heading(1,List.of(run("编辑书与 Word 互通验证"))));
        blocks.add(new Paragraph(List.of(run("普通文字 "),new TextRun("粗体斜体颜色",TextStyle.DEFAULT.withBold(true).withItalic(true).withColor(0x1F5E99)),new TextRun(" 2",TextStyle.DEFAULT.withScript(1)),new TextRun("隐藏",TextStyle.DEFAULT.withHidden(true)),new TextRun(" code",TextStyle.DEFAULT.withCode(true)),new TextRun("网页",TextStyle.DEFAULT,"https://example.com"))));
        for(var kind:RichTextBlock.Kind.values())blocks.add(new RichTextBlock(kind,RichTextBlock.Alignment.CENTER,0,true,List.of(run("段落示例 "+kind))));
        blocks.add(new TableBlock(List.of(new TableRow(List.of(new TableCell(List.of(run("名称"))),new TableCell(List.of(run("数量"))))),new TableRow(List.of(new TableCell(List.of(run("石头"))),new TableCell(List.of(new TextRun("64",TextStyle.DEFAULT.withUnderline(true)))))))));
        blocks.add(new ImageBlock(asset.assetHash(),"蓝色样例",ImageAlignment.CENTER,120));blocks.add(HorizontalRule.INSTANCE);
        blocks.add(PageBreak.INSTANCE);blocks.add(new Paragraph(List.of(new TextRun("返回第一页",TextStyle.DEFAULT,"page:1"))));
        WikiDocument doc=new WikiDocument(WikiDocument.CURRENT_FORMAT_VERSION,"Word 互通样例",blocks,metadata());
        Path output=root.resolve("sample.docx");var warnings=DocxIO.write(output,doc,hash->asset.storedFile());
        assertTrue(warnings.stream().anyMatch(x->x.contains("隐藏")));
        var imported=DocxIO.read(output,metadata(),store::importImage);assertEquals(doc.title(),imported.document().title());
        assertEquals(2,DocumentPages.count(imported.document()));assertEquals(1,imported.document().blocks().stream().filter(b->b instanceof TableBlock).count());
        assertTrue(imported.document().blocks().stream().anyMatch(b->b instanceof ImageBlock im && im.assetHash().equals(asset.assetHash()) && im.requestedWidth()==120));
        assertTrue(imported.document().blocks().stream().flatMap(b->b.runs().stream()).anyMatch(r->r.style().script()==1));
        assertTrue(imported.document().blocks().stream().flatMap(b->b.runs().stream()).anyMatch(r->r.style().code()));
        assertTrue(imported.document().blocks().stream().anyMatch(b->b instanceof RichTextBlock r&&r.kind()==RichTextBlock.Kind.TASK&&r.checked()));
        assertTrue(imported.document().blocks().stream().anyMatch(b->b instanceof RichTextBlock r&&r.kind()==RichTextBlock.Kind.QUOTE_TASK&&r.checked()));
        assertTrue(imported.document().blocks().stream().anyMatch(b->b instanceof RichTextBlock r&&r.kind()==RichTextBlock.Kind.BULLET));
        assertTrue(imported.document().blocks().stream().flatMap(b->b.runs().stream()).anyMatch(r->"page:1".equals(r.linkTarget())));
        try(ZipFile zip=new ZipFile(output.toFile())){assertNotNull(zip.getEntry("word/media/image1.png"));assertNotNull(zip.getEntry("word/numbering.xml"));assertNull(zip.getEntry("word/vbaProject.bin"));}
        Path qa=Path.of("build/docx-qa");Files.createDirectories(qa);Files.copy(output,qa.resolve("wiki-word-sample.docx"),StandardCopyOption.REPLACE_EXISTING);
    }
    @Test void mixedFramesRoundTripTextTaskImageAndTableWithoutDroppingMembership() throws Exception {
        var store = new LocalImageAssetStore(root.resolve("frame-assets"));
        Path png = root.resolve("frame.png");
        ImageIO.write(new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB), "png", png.toFile());
        var asset = store.importImage(png);
        List<Block> blocks = new ArrayList<>();
        for (var kind : BlockFrame.Kind.values()) {
            BlockFrame frame = new BlockFrame(kind, kind.ordinal() + 1);
            blocks.add(new RichTextBlock(RichTextBlock.Kind.NORMAL, RichTextBlock.Alignment.LEFT, 0, false, List.of(run("intro")), frame));
            blocks.add(new RichTextBlock(RichTextBlock.Kind.TASK, RichTextBlock.Alignment.LEFT, 0, true, List.of(run("task")), frame));
            blocks.add(new ImageBlock(asset.assetHash(), "", ImageAlignment.LEFT, 16).withFrame(frame));
            blocks.add(new TableBlock(List.of(new TableRow(List.of(new TableCell(List.of(run("cell"))))))).withFrame(frame));
        }
        var doc = new WikiDocument(WikiDocument.CURRENT_FORMAT_VERSION, "frames", blocks, metadata());
        Path output = root.resolve("frames.docx");
        assertTrue(DocxIO.write(output, doc, hash -> asset.storedFile()).stream().anyMatch(w -> w.contains("混合框")));
        var restored = DocxIO.read(output, metadata(), store::importImage).document();
        assertEquals(blocks.size(), restored.blocks().size());
        for (int i = 0; i < blocks.size(); i++) {
            assertEquals(blocks.get(i).frame(), restored.blocks().get(i).frame());
            assertEquals(WikiDocumentEditor.blockText(blocks.get(i)), WikiDocumentEditor.blockText(restored.blocks().get(i)));
        }
        assertTrue(((RichTextBlock)restored.blocks().get(1)).checked());
        assertEquals(asset.assetHash(), ((ImageBlock)restored.blocks().get(2)).assetHash());
        assertInstanceOf(TableBlock.class, restored.blocks().get(3));
    }

    @Test void framedListsRoundTripAndSeparateContainersRestartTheirNumbering() throws Exception {
        List<Block> blocks = new ArrayList<>();
        for (var kind : BlockFrame.Kind.values()) {
            BlockFrame frame = new BlockFrame(kind, kind.ordinal()+1);
            for (int i=0;i<2;i++) blocks.add(new RichTextBlock(RichTextBlock.Kind.NUMBERED,
                    RichTextBlock.Alignment.LEFT,0,false,List.of(run("item "+i)),frame));
        }
        blocks.add(new RichTextBlock(RichTextBlock.Kind.NUMBERED,RichTextBlock.Alignment.LEFT,0,false,List.of(run("outside"))));
        blocks.add(new RichTextBlock(RichTextBlock.Kind.BULLET,RichTextBlock.Alignment.LEFT,0,false,List.of(run("bullet")),new BlockFrame(BlockFrame.Kind.INFO,4)));
        Path output = root.resolve("lists.docx");
        DocxIO.write(output,new WikiDocument(WikiDocument.CURRENT_FORMAT_VERSION,"lists",blocks,metadata()),h->{throw new IOException("no images");});
        var restored=DocxIO.read(output,metadata(),p->{throw new IOException("no images");}).document();
        assertEquals(blocks,restored.blocks());
        try(ZipFile zip=new ZipFile(output.toFile())) {
            String xml=new String(zip.getInputStream(zip.getEntry("word/document.xml")).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            var matcher=java.util.regex.Pattern.compile("<w:numId w:val=\"(\\d+)\"").matcher(xml);
            List<String> ids=new ArrayList<>();while(matcher.find())ids.add(matcher.group(1));
            assertEquals(List.of("1","1","2","2","3","3","4","5"),ids);
        }
    }

    @Test void importsIndependentWordProducerFixtureWithInheritedStylesAndMergedCells() throws Exception {
        Path file=root.resolve("independent.docx");
        try(InputStream in=getClass().getResourceAsStream("/docx/independent.docx")){assertNotNull(in);Files.copy(in,file);}
        var store=new LocalImageAssetStore(root.resolve("store"));var result=DocxIO.read(file,metadata(),store::importImage);
        assertTrue(result.document().blocks().stream().anyMatch(b->b instanceof Heading));
        assertTrue(result.document().blocks().stream().flatMap(b->b.runs().stream()).anyMatch(r->r.text().contains("inherited")&&r.style().bold()&&r.style().fontSize()==14));
        assertTrue(result.document().blocks().stream().flatMap(b->b.runs().stream()).anyMatch(r->r.text().equals("sub")&&r.style().script()==-1));
        assertTrue(result.document().blocks().stream().anyMatch(b->b instanceof ImageBlock));
        assertTrue(result.warnings().stream().anyMatch(w->w.contains("合并")));
    }
    @Test void maliciousXmlExternalImagesAndFailedExportCannotOverwriteExistingFile() throws Exception {
        Path invalid=root.resolve("xxe.docx");
        try(ZipOutputStream zip=new ZipOutputStream(Files.newOutputStream(invalid))){DocxIO.put(zip,"word/document.xml","<!DOCTYPE document [<!ENTITY x SYSTEM 'file:///C:/secret'>]><document>&x;</document>");}
        assertThrows(IOException.class,()->DocxIO.read(invalid,metadata(),p->{fail("must not import anything");return null;}));
        Path target=root.resolve("keep.docx");Files.writeString(target,"keep original");
        var document=new WikiDocument(WikiDocument.CURRENT_FORMAT_VERSION,"test",List.of(new ImageBlock("a".repeat(64),"",ImageAlignment.LEFT,100)),metadata());
        assertThrows(IOException.class,()->DocxIO.write(target,document,h->root.resolve("missing")));
        assertEquals("keep original",Files.readString(target));
        try(var files=Files.list(root)){assertFalse(files.anyMatch(p->p.getFileName().toString().endsWith(".tmp")));}
    }
}
