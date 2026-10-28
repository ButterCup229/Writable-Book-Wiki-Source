package com.writablebookwiki.local.docx;

import com.writablebookwiki.local.ImportedImageAsset;
import com.writablebookwiki.local.DocxIO.*;

import com.writablebookwiki.core.document.*;
import static com.writablebookwiki.local.docx.DocxArchive.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import javax.imageio.ImageIO;

/** Emits normal Word paragraphs, runs, numbering, tables and DrawingML pictures. */
final class DocxWriter {
    private final ZipOutputStream zip;
    private final ImageResolver images;
    private final StringBuilder rels = new StringBuilder(), numbering = new StringBuilder();
    final Set<String> warnings = new LinkedHashSet<>();
    private int relationship, picture, listId, activeList;
    private RichTextBlock.Kind previousKind;
    DocxWriter(ZipOutputStream zip, ImageResolver images) { this.zip = zip; this.images = images; }

    void write(WikiDocument doc) throws IOException {
        relationship("styles", "styles.xml", false);
        relationship("numbering", "numbering.xml", false);
        StringBuilder body = new StringBuilder();
        int page = 1;
        body.append(bookmark(page));
        BlockFrame activeFrame = null;
        for (Block block : doc.blocks()) {
            if (!Objects.equals(activeFrame, block.frame())) {
                previousKind = null; // A new container starts its own ordered list.
                if (activeFrame != null) body.append("</w:sdtContent></w:sdt>");
                activeFrame = block.frame();
                if (activeFrame != null) {
                    body.append("<w:sdt><w:sdtPr><w:tag w:val=\"WritableBookWikiFrame:").append(activeFrame.kind())
                            .append("\"/></w:sdtPr><w:sdtContent>");
                    warnings.add("引用／提示／警告混合框导出为 Word 内容控件和底色；框内边距与折叠不保证完全一致");
                }
            }
            if (block instanceof TextualBlock text) body.append(paragraph(text));
            else if (block instanceof PageBreak) {
                body.append("<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>").append(bookmark(++page));
                previousKind = null;
            } else if (block instanceof HorizontalRule) {
                body.append("<w:p><w:pPr><w:pStyle w:val=\"WikiRule\"/><w:pBdr><w:bottom w:val=\"single\" w:sz=\"6\" w:color=\"9AABBD\"/></w:pBdr></w:pPr></w:p>");
                previousKind = null;
            } else if (block instanceof TableBlock table) { body.append(table(table)); previousKind = null; }
            else if (block instanceof ImageBlock image) { body.append(image(image)); previousKind = null; }
        }
        if (activeFrame != null) body.append("</w:sdtContent></w:sdt>");
        body.append("<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"1134\" w:right=\"1134\" w:bottom=\"1134\" w:left=\"1134\"/></w:sectPr>");
        put(zip, "word/document.xml", XML + "<w:document xmlns:w=\""+W+"\" xmlns:r=\""+R+"\" xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\" xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"><w:body>"+body+"</w:body></w:document>");
        put(zip, "word/_rels/document.xml.rels", XML+"<Relationships xmlns=\""+REL+"\">"+rels+"</Relationships>");
        put(zip, "word/styles.xml", styles());
        put(zip, "word/numbering.xml", XML+"<w:numbering xmlns:w=\""+W+"\">"+numbering+"</w:numbering>");
        put(zip, "docProps/core.xml", XML+"<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:title>"+esc(doc.title())+"</dc:title><dc:creator>"+esc(doc.metadata().creatorName())+"</dc:creator></cp:coreProperties>");
        put(zip, "_rels/.rels", XML+"<Relationships xmlns=\""+REL+"\"><Relationship Id=\"rId1\" Type=\""+R+"/officeDocument\" Target=\"word/document.xml\"/><Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\" Target=\"docProps/core.xml\"/></Relationships>");
        put(zip, "[Content_Types].xml", XML+"<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Default Extension=\"png\" ContentType=\"image/png\"/><Default Extension=\"jpg\" ContentType=\"image/jpeg\"/><Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/><Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/><Override PartName=\"/word/numbering.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml\"/><Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/></Types>");
    }
    private String bookmark(int page) { return "<w:p><w:pPr><w:spacing w:before=\"0\" w:after=\"0\" w:line=\"20\" w:lineRule=\"exact\"/></w:pPr><w:bookmarkStart w:id=\""+page+"\" w:name=\"WikiPage"+page+"\"/><w:bookmarkEnd w:id=\""+page+"\"/></w:p>"; }
    private String relationship(String type, String target, boolean external) {
        String id = "rId"+(++relationship);
        rels.append("<Relationship Id=\"").append(id).append("\" Type=\"").append(R).append('/').append(type)
                .append("\" Target=\"").append(esc(target)).append('"');
        if(external) rels.append(" TargetMode=\"External\"");
        rels.append("/>"); return id;
    }
    private String paragraph(TextualBlock text) {
        RichTextBlock rich = RichTextBlock.from(text);
        StringBuilder props = new StringBuilder();
        String style = rich.headingLevel()>0 ? "Heading"+rich.headingLevel() : switch(rich.kind()) {
            case QUOTE -> "WikiQuote"; case QUOTE_TASK -> "WikiQuoteTask"; case CODE -> "WikiCodeBlock"; case INFO -> "WikiInfo";
            case WARNING -> "WikiWarning"; case TASK -> "WikiTask"; case STEP -> "WikiStep"; default -> "Normal";
        };
        props.append("<w:pStyle w:val=\"").append(style).append("\"/>");
        props.append(frameProperties(text.frame()));
        if (rich.kind()==RichTextBlock.Kind.BULLET || rich.kind()==RichTextBlock.Kind.NUMBERED || rich.kind()==RichTextBlock.Kind.STEP) {
            if (rich.kind()!=previousKind) activeList = newList(rich.kind());
            props.append("<w:numPr><w:ilvl w:val=\"0\"/><w:numId w:val=\"").append(activeList).append("\"/></w:numPr>");
        }
        previousKind = rich.kind();
        props.append("<w:jc w:val=\"").append(switch(rich.alignment()) {case LEFT->"left";case CENTER->"center";case RIGHT->"right";case JUSTIFY->"both";}).append("\"/>");
        String prefix = rich.isTask() ? run(new TextRun(rich.checked()?"☑ ":"☐ ",TextStyle.DEFAULT)) : "";
        List<TextRun> visible = rich.runs();
        if(rich.headingLevel()>0) {
            float scale=switch(rich.headingLevel()){case 1->1.52f;case 2->1.28f;default->1.12f;};
            visible=visible.stream().map(r->new TextRun(r.text(),r.style().withFontSize(Math.max(1,Math.round(r.style().fontSize()*scale))),r.linkTarget())).toList();
        }
        return "<w:p><w:pPr>"+props+"</w:pPr>"+prefix+runs(visible)+"</w:p>";
    }
    private int newList(RichTextBlock.Kind kind) {
        int id = ++listId;
        String fmt=kind==RichTextBlock.Kind.BULLET?"bullet":"decimal", label=kind==RichTextBlock.Kind.BULLET?"•":kind==RichTextBlock.Kind.STEP?"步骤 %1":"%1.";
        numbering.append("<w:abstractNum w:abstractNumId=\"").append(id).append("\"><w:multiLevelType w:val=\"singleLevel\"/><w:lvl w:ilvl=\"0\"><w:start w:val=\"1\"/><w:numFmt w:val=\"").append(fmt).append("\"/><w:lvlText w:val=\"").append(label).append("\"/><w:lvlJc w:val=\"left\"/><w:pPr><w:tabs><w:tab w:val=\"num\" w:pos=\"720\"/></w:tabs><w:ind w:left=\"720\" w:hanging=\"360\"/></w:pPr></w:lvl></w:abstractNum>");
        // num elements are emitted separately at the end of styles in finishNumbering order below.
        return id;
    }
    private String runs(List<TextRun> runs) {
        StringBuilder result = new StringBuilder();
        for(TextRun text:runs) {
            String r = run(text);
            if(text.isLink()) {
                try {
                    DocumentLink link=DocumentLink.parse(text.linkTarget());
                    if(link.kind()==DocumentLink.Kind.WEB) r="<w:hyperlink r:id=\""+relationship("hyperlink",link.value(),true)+"\">"+r+"</w:hyperlink>";
                    else if(link.kind()==DocumentLink.Kind.PAGE) r="<w:hyperlink w:anchor=\"WikiPage"+link.value()+"\">"+r+"</w:hyperlink>";
                    else { r+=run(new TextRun(" [ /"+link.value()+" ]",TextStyle.DEFAULT.withCode(true))); warnings.add("MC 指令导出为可见文字，Word 中不会执行"); }
                } catch(IllegalArgumentException e) { warnings.add("不支持的链接已保留显示文字"); }
            }
            result.append(r);
        }
        return result.toString();
    }
    private String run(TextRun run) {
        TextStyle s=run.style();StringBuilder p=new StringBuilder();
        if(s.code()) p.append("<w:rStyle w:val=\"WikiInlineCode\"/><w:rFonts w:ascii=\"Consolas\" w:hAnsi=\"Consolas\"/><w:shd w:fill=\"E7EDF3\"/>");
        if(s.bold())p.append("<w:b/>"); if(s.italic())p.append("<w:i/>"); if(s.strikethrough())p.append("<w:strike/>");
        if(s.underline() || run.isLink())p.append("<w:u w:val=\"single\"/>");
        if(s.script()!=0)p.append("<w:vertAlign w:val=\"").append(s.script()>0?"superscript":"subscript").append("\"/>");
        if(s.hidden()) { p.append("<w:vanish/>"); warnings.add("剧透文字转换为 Word 隐藏文字"); }
        p.append("<w:color w:val=\"").append(String.format(Locale.ROOT,"%06X",s.rgbColor())).append("\"/><w:sz w:val=\"").append(s.fontSize()*2).append("\"/>");
        String text = esc(run.text()).replace("\r", "").replace("\n", "</w:t><w:br/><w:t xml:space=\"preserve\">").replace("\t","</w:t><w:tab/><w:t xml:space=\"preserve\">");
        return "<w:r><w:rPr>"+p+"</w:rPr><w:t xml:space=\"preserve\">"+text+"</w:t></w:r>";
    }
    private String frameColor(BlockFrame frame) {
        return switch (frame.kind()) { case QUOTE -> "E9EEF3"; case INFO -> "E5F0FB"; case WARNING -> "FFF0D0"; };
    }
    private String frameProperties(BlockFrame frame) {
        return frame == null ? "" : "<w:ind w:left=\"180\" w:right=\"120\"/><w:shd w:fill=\"" + frameColor(frame) + "\"/>";
    }

    private String table(TableBlock table) {
        int width=9638/table.columnCount();StringBuilder out=new StringBuilder("<w:tbl><w:tblPr><w:tblW w:w=\"9638\" w:type=\"dxa\"/><w:tblBorders>");
        for(String edge:List.of("top","left","bottom","right","insideH","insideV"))out.append("<w:").append(edge).append(" w:val=\"single\" w:sz=\"4\" w:color=\"AEB8C3\"/>");
        out.append("</w:tblBorders><w:tblLayout w:type=\"fixed\"/>");
        if (table.frame() != null) out.append("<w:shd w:fill=\"").append(frameColor(table.frame())).append("\"/>");
        out.append("</w:tblPr><w:tblGrid>");
        for(int i=0;i<table.columnCount();i++)out.append("<w:gridCol w:w=\"").append(width).append("\"/>");out.append("</w:tblGrid>");
        for(TableRow row:table.rows()){out.append("<w:tr>");for(TableCell cell:row.cells())out.append("<w:tc><w:tcPr><w:tcW w:w=\"").append(width).append("\" w:type=\"dxa\"/></w:tcPr><w:p>").append(runs(cell.runs())).append("</w:p></w:tc>");out.append("</w:tr>");}
        return out+"</w:tbl>";
    }
    private String image(ImageBlock image) throws IOException {
        Path file=images.resolve(image.assetHash());
        if(file==null || !Files.isRegularFile(file))throw new IOException("图片尚未下载，无法完整导出："+image.assetHash().substring(0,8));
        if(Files.size(file)>MAX_ASSET)throw new IOException("导出图片过大");
        int width,height;String extension;
        try(var input=ImageIO.createImageInputStream(file.toFile())) {
            var readers=ImageIO.getImageReaders(input);if(!readers.hasNext())throw new IOException("图片格式无效");
            var reader=readers.next();try{reader.setInput(input);width=reader.getWidth(0);height=reader.getHeight(0);extension=reader.getFormatName().equalsIgnoreCase("png")?"png":"jpg";}finally{reader.dispose();}
        }
        int id=++picture;String name="media/image"+id+"."+extension;
        zip.putNextEntry(new ZipEntry("word/"+name));Files.copy(file,zip);zip.closeEntry();
        String rel=relationship("image",name,false);
        long cx=Math.round(Math.min(642, image.requestedWidth()>0?image.requestedWidth():width)*9525d),cy=Math.round((double)cx*height/width);
        if(image.layoutMode()!=ImageLayoutMode.INLINE)warnings.add("浮动和环绕图片已转换为行内图片");
        return "<w:p><w:pPr>"+frameProperties(image.frame())+"<w:jc w:val=\""+image.alignment().name().toLowerCase(Locale.ROOT)+"\"/></w:pPr><w:r><w:drawing><wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\"><wp:extent cx=\""+cx+"\" cy=\""+cy+"\"/><wp:docPr id=\""+id+"\" name=\"Picture "+id+"\" descr=\""+esc(image.caption())+"\"/><wp:cNvGraphicFramePr><a:graphicFrameLocks noChangeAspect=\"1\"/></wp:cNvGraphicFramePr><a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"><pic:pic><pic:nvPicPr><pic:cNvPr id=\""+id+"\" name=\"image"+id+"\"/><pic:cNvPicPr/></pic:nvPicPr><pic:blipFill><a:blip r:embed=\""+rel+"\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill><pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\""+cx+"\" cy=\""+cy+"\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr></pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>";
    }
    private String styles() {
        // All abstract definitions must precede their num instances in the schema.
        for(int i=1;i<=listId;i++)numbering.append("<w:num w:numId=\"").append(i).append("\"><w:abstractNumId w:val=\"").append(i).append("\"/></w:num>");
        StringBuilder s=new StringBuilder(XML+"<w:styles xmlns:w=\""+W+"\"><w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"Calibri\" w:hAnsi=\"Calibri\" w:eastAsia=\"Microsoft YaHei\"/><w:sz w:val=\"22\"/></w:rPr></w:rPrDefault></w:docDefaults><w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/><w:pPr><w:spacing w:after=\"120\"/></w:pPr></w:style>");
        for(int i=1;i<=3;i++)s.append("<w:style w:type=\"paragraph\" w:styleId=\"Heading").append(i).append("\"><w:name w:val=\"heading ").append(i).append("\"/><w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:pPr><w:keepNext/><w:outlineLvl w:val=\"").append(i-1).append("\"/><w:spacing w:before=\"240\" w:after=\"120\"/></w:pPr><w:rPr><w:b/><w:color w:val=\"000000\"/><w:sz w:val=\"").append(38-i*4).append("\"/></w:rPr></w:style>");
        for(String name:List.of("WikiQuote","WikiQuoteTask","WikiCodeBlock","WikiInfo","WikiWarning","WikiTask","WikiStep","WikiRule")) {
            s.append("<w:style w:type=\"paragraph\" w:styleId=\"").append(name).append("\"><w:name w:val=\"").append(name).append("\"/><w:basedOn w:val=\"Normal\"/>");
            if(List.of("WikiQuote","WikiQuoteTask","WikiCodeBlock","WikiInfo","WikiWarning","WikiStep").contains(name))s.append("<w:pPr><w:ind w:left=\"180\" w:right=\"120\"/><w:shd w:fill=\"").append(name.equals("WikiWarning")?"FFF0D0":name.equals("WikiInfo")?"E5F0FB":"EEF1F5").append("\"/></w:pPr>");
            if(name.equals("WikiCodeBlock"))s.append("<w:rPr><w:rFonts w:ascii=\"Consolas\" w:hAnsi=\"Consolas\"/></w:rPr>");
            s.append("</w:style>");
        }
        return s+"<w:style w:type=\"character\" w:styleId=\"WikiInlineCode\"><w:name w:val=\"Inline Code\"/></w:style></w:styles>";
    }
}
