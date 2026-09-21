package com.writablebookwiki.local;

import com.writablebookwiki.core.document.*;
import static com.writablebookwiki.local.DocxIO.*;
import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;
import org.w3c.dom.Element;

/** Imports the visible document body, applying inherited Word paragraph and character styles. */
final class DocxReader {
    private final ZipFile zip;
    private final DocumentMetadata metadata;
    private final ImageImporter images;
    private final Set<String> warnings = new LinkedHashSet<>();
    private final Map<String, Element> styles = new HashMap<>();
    private final Map<String, Relationship> relationships = new HashMap<>();
    private final Map<String, String> numbers = new HashMap<>();
    private final Map<String, ImportedImageAsset> importedImages = new HashMap<>();
    private TextStyle defaults = TextStyle.DEFAULT;
    private int blockCount, tableDepth, frameSequence;
    private record Relationship(String target, boolean external, String type) { }
    DocxReader(ZipFile zip, DocumentMetadata metadata, ImageImporter images) {
        this.zip=zip;this.metadata=metadata;this.images=images;
    }
    ImportResult read(String filename) throws IOException {
        Element document=xml(part(zip,"word/document.xml",MAX_XML));
        if(document==null || !"document".equals(document.getLocalName()))throw new IOException("不是可读取的 DOCX 文档");
        Element rels=xml(part(zip,"word/_rels/document.xml.rels",MAX_XML));
        for(Element r:children(rels)) relationships.put(attr(r,"Id"),new Relationship(attr(r,"Target"),"External".equals(attr(r,"TargetMode")),attr(r,"Type")));
        Element styleRoot=xml(part(zip,"word/styles.xml",MAX_XML));
        if(styleRoot!=null) {
            defaults=style(defaults,child(child(child(styleRoot,"docDefaults"),"rPrDefault"),"rPr"));
            for(Element e:children(styleRoot))if("style".equals(e.getLocalName()))styles.put(attr(e,"styleId"),e);
        }
        Element numbering=xml(part(zip,"word/numbering.xml",MAX_XML));
        Map<String,Element> abstracts=new HashMap<>();
        for(Element e:children(numbering))if("abstractNum".equals(e.getLocalName()))abstracts.put(attr(e,"abstractNumId"),e);
        for(Element e:children(numbering))if("num".equals(e.getLocalName())) {
            Element def=abstracts.get(val(child(e,"abstractNumId")));
            for(Element level:children(def))if("lvl".equals(level.getLocalName()))numbers.put(attr(e,"numId")+":"+attr(level,"ilvl"),val(child(level,"numFmt")));
            if (!all(e,"lvlOverride").isEmpty() || all(def,"start").stream().anyMatch(start -> !val(start).equals("1")))
                warnings.add("自定义列表起始编号按连续列表重排");
        }
        if(zip.getEntry("word/vbaProject.bin")!=null)warnings.add("宏未导入");
        for(String unsupported:List.of("sectPr","footnoteReference","endnoteReference","commentReference","oMath","txbxContent","pict","altChunk","fldChar","ins","del")) {
            if(!all(document,unsupported).isEmpty() && !unsupported.equals("sectPr"))warnings.add("复杂对象、修订和域按可读取的正文处理；页眉页脚、公式和文本框不保留原排版");
        }
        if(all(document,"sectPr").size()>1)warnings.add("分节、分栏及页眉页脚不保留原排版");
        List<Block> blocks=new ArrayList<>();
        readBlocks(child(document,"body"),blocks,0);
        if(blocks.isEmpty())blocks.add(new Paragraph(List.of()));
        Element props=xml(part(zip,"docProps/core.xml",MAX_XML));
        String title=filename.replaceFirst("(?i)\\.docx$","");
        List<Element> titles=all(props,"title");if(!titles.isEmpty()&&!titles.getFirst().getTextContent().isBlank())title=titles.getFirst().getTextContent();
        return new ImportResult(new WikiDocument(WikiDocument.CURRENT_FORMAT_VERSION,title,blocks,metadata),List.copyOf(warnings));
    }
    private void readBlocks(Element parent,List<Block> blocks,int depth) throws IOException {
        if(depth>32)throw new IOException("DOCX 内容嵌套过深");
        for(Element e:children(parent)) {
            if(++blockCount>20000)throw new IOException("DOCX 段落数量过多");
            switch(e.getLocalName()) {
                case "p" -> paragraph(e,blocks);
                case "tbl" -> blocks.add(table(e));
                case "sdt" -> {
                    int first = blocks.size();
                    readBlocks(child(e,"sdtContent"),blocks,depth+1);
                    String tag = val(child(child(e,"sdtPr"),"tag"));
                    if (tag.matches("WritableBookWikiFrame:(QUOTE|INFO|WARNING)")) {
                        BlockFrame frame = new BlockFrame(BlockFrame.Kind.valueOf(tag.substring(tag.indexOf(':') + 1)), ++frameSequence);
                        for (int i = first; i < blocks.size(); i++) {
                            Block block = blocks.get(i);
                            if (block instanceof TextualBlock || block instanceof ImageBlock || block instanceof TableBlock)
                                blocks.set(i, BlockFrame.attach(block, frame));
                        }
                    }
                }
                case "customXml", "ins" -> readBlocks(e,blocks,depth+1);
                default -> { }
            }
        }
    }
    private List<Element> styleChain(String id) {
        LinkedList<Element> chain=new LinkedList<>();Set<String> seen=new HashSet<>();
        while(!id.isBlank() && seen.add(id) && chain.size()<32) {
            Element e=styles.get(id);if(e==null)break;chain.addFirst(e);id=val(child(e,"basedOn"));
        }
        return chain;
    }
    private void paragraph(Element p,List<Block> blocks) throws IOException {
        // Exported page bookmarks are anchors, not authored blank paragraphs.
        if(all(p,"bookmarkStart").stream().anyMatch(e->attr(e,"name").startsWith("WikiPage"))
                && all(p,"t").isEmpty() && all(p,"drawing").isEmpty())return;
        Element direct=child(p,"pPr");String id=val(child(direct,"pStyle"));
        List<Element> chain=styleChain(id.isBlank()?"Normal":id);
        TextStyle base=defaults;String align="left",num="",level="0";int heading=0;boolean pageBefore=false;
        List<Element> props=new ArrayList<>();
        for(Element e:chain){base=style(base,child(e,"rPr"));props.add(child(e,"pPr"));}
        props.add(direct);
        for(Element pr:props) {
            if(child(pr,"jc")!=null)align=val(child(pr,"jc"));
            if(child(pr,"outlineLvl")!=null)heading=number(val(child(pr,"outlineLvl")),9)+1;
            if(child(pr,"numPr")!=null){num=val(child(child(pr,"numPr"),"numId"));level=val(child(child(pr,"numPr"),"ilvl"));}
            if(child(pr,"pageBreakBefore")!=null)pageBefore=on(child(pr,"pageBreakBefore"));
        }
        if(heading==0 && id.matches("(?i)heading[123]"))heading=number(id.substring(id.length()-1),0);
        heading=heading>=1&&heading<=3?heading:0;
        RichTextBlock.Kind kind=switch(id) {
            case "WikiQuote","Quote","IntenseQuote"->RichTextBlock.Kind.QUOTE;
            case "WikiQuoteTask"->RichTextBlock.Kind.QUOTE_TASK;
            case "WikiCodeBlock"->RichTextBlock.Kind.CODE;case "WikiInfo"->RichTextBlock.Kind.INFO;
            case "WikiWarning"->RichTextBlock.Kind.WARNING;case "WikiTask"->RichTextBlock.Kind.TASK;
            case "WikiStep"->RichTextBlock.Kind.STEP;default->RichTextBlock.Kind.NORMAL;
        };
        if(!num.isBlank()&&!num.equals("0")&&kind!=RichTextBlock.Kind.STEP) {
            kind="bullet".equals(numbers.get(num+":"+(level.isBlank()?"0":level)))?RichTextBlock.Kind.BULLET:RichTextBlock.Kind.NUMBERED;
            String format=numbers.get(num+":"+(level.isBlank()?"0":level));
            if(format!=null&&!format.equals("bullet")&&!format.equals("decimal"))warnings.add("字母、罗马数字等编号已转换为十进制编号");
            if(!level.isBlank()&&!level.equals("0"))warnings.add("多级列表已转换为单级列表");
        }
        RichTextBlock.Alignment alignment=switch(align){case "center"->RichTextBlock.Alignment.CENTER;case "right","end"->RichTextBlock.Alignment.RIGHT;case "both","distribute"->RichTextBlock.Alignment.JUSTIFY;default->RichTextBlock.Alignment.LEFT;};
        List<Object> pieces=new ArrayList<>();readInline(p,base,null,pieces,0);
        boolean checked=false;
        if((kind==RichTextBlock.Kind.TASK || kind==RichTextBlock.Kind.QUOTE_TASK) && !pieces.isEmpty() && pieces.getFirst() instanceof TextRun first) {
            checked=first.text().startsWith("☑");
            if(first.text().startsWith("☐ ")||first.text().startsWith("☑ "))pieces.set(0,new TextRun(first.text().substring(2),first.style(),first.linkTarget()));
        }
        if(pageBefore&&!blocks.isEmpty()&&!(blocks.getLast() instanceof PageBreak))blocks.add(PageBreak.INSTANCE);
        if(id.equals("WikiRule") || child(direct,"pBdr")!=null&&pieces.isEmpty()) {blocks.add(HorizontalRule.INSTANCE);return;}
        List<TextRun> runs=new ArrayList<>();boolean emitted=false;
        for(Object piece:pieces) {
            if(piece instanceof TextRun run) {
                if(heading>0) {
                    float scale=switch(heading){case 1->1.52f;case 2->1.28f;default->1.12f;};
                    run=new TextRun(run.text(),run.style().withFontSize(Math.max(1,Math.round(run.style().fontSize()/scale))),run.linkTarget());
                }
                runs.add(run);
            }
            else if(piece instanceof Block object) {
                if(!runs.isEmpty()){blocks.add(textBlock(kind,alignment,heading,checked,runs));runs=new ArrayList<>();}
                if(object instanceof ImageBlock image)object=new ImageBlock(image.assetHash(),image.caption(),switch(alignment){case CENTER->ImageAlignment.CENTER;case RIGHT->ImageAlignment.RIGHT;default->ImageAlignment.LEFT;},image.requestedWidth());
                blocks.add(object);emitted=true;
            }
        }
        if(!runs.isEmpty()||!emitted)blocks.add(textBlock(kind,alignment,heading,checked,runs));
    }
    private Block textBlock(RichTextBlock.Kind kind,RichTextBlock.Alignment align,int heading,boolean checked,List<TextRun> runs) {
        if(kind==RichTextBlock.Kind.NORMAL&&align==RichTextBlock.Alignment.LEFT)return heading>0?new Heading(heading,runs):new Paragraph(runs);
        return new RichTextBlock(kind,align,heading,checked,runs);
    }
    private void readInline(Element parent,TextStyle inherited,String link,List<Object> out,int depth) throws IOException {
        if(depth>40)throw new IOException("DOCX 文字嵌套过深");
        for(Element e:children(parent))switch(e.getLocalName()) {
            case "r" -> {
                TextStyle s=inherited;Element pr=child(e,"rPr");
                for(Element st:styleChain(val(child(pr,"rStyle"))))s=style(s,child(st,"rPr"));
                if("WikiInlineCode".equals(val(child(pr,"rStyle"))))s=s.withCode(true);
                s=style(s,pr);readInline(e,s,link,out,depth+1);
            }
            case "t" -> out.add(new TextRun(e.getTextContent(),inherited,link));
            case "tab" -> out.add(new TextRun("    ",inherited,link));
            case "br" -> { if("page".equals(attr(e,"type")))out.add(PageBreak.INSTANCE);else out.add(new TextRun("\n",inherited,link)); }
            case "cr" -> out.add(new TextRun("\n",inherited,link));
            case "hyperlink" -> {
                String target=null,anchor=attr(e,"anchor");Relationship rel=relationships.get(attr(e,"id"));
                if(anchor.matches("WikiPage[0-9]+"))target="page:"+anchor.substring(8);
                else if(rel!=null && rel.external && rel.type.endsWith("/hyperlink")) {
                    try {DocumentLink parsed=DocumentLink.parse(rel.target);if(parsed.kind()==DocumentLink.Kind.WEB)target=parsed.target();else warnings.add("不支持的外部链接已转换为文字");}
                    catch(IllegalArgumentException ex){warnings.add("不支持的外部链接已转换为文字");}
                } else if(!anchor.isEmpty())warnings.add("Word 书签链接仅保留显示文字");
                readInline(e,inherited,target,out,depth+1);
            }
            case "drawing" -> { ImageBlock image=image(e);if(image!=null)out.add(image); }
            case "sdt" -> readInline(child(e,"sdtContent"),inherited,link,out,depth+1);
            case "ins","smartTag","customXml","fldSimple" -> readInline(e,inherited,link,out,depth+1);
            case "pict","object" -> {warnings.add("旧式图片或嵌入对象不能导入");out.add(new TextRun("[不支持的对象]",TextStyle.DEFAULT));}
            default -> { }
        }
    }
    private ImageBlock image(Element drawing) throws IOException {
        List<Element> blips=all(drawing,"blip");if(blips.isEmpty()){warnings.add("图表或绘图对象未导入");return null;}
        String id=attr(blips.getFirst(),"embed");Relationship rel=relationships.get(id);
        if(rel==null||rel.external||!rel.type.endsWith("/image")){warnings.add("外部链接图片未下载");return null;}
        String path;
        try {
            URI uri=URI.create("/word/document.xml").resolve(rel.target).normalize();
            if(uri.getScheme()!=null||uri.getAuthority()!=null||uri.getQuery()!=null||uri.getFragment()!=null||!uri.getPath().startsWith("/word/"))throw new IllegalArgumentException();
            path=uri.getPath().substring(1);
        } catch(IllegalArgumentException e){throw new IOException("图片关系路径无效");}
        ImportedImageAsset imported=importedImages.get(path);
        if(imported==null) {
            byte[] data=part(zip,path,MAX_ASSET);if(data==null)throw new IOException("DOCX 缺少图片部件");
            boolean png=data.length>8&&data[0]==(byte)0x89&&data[1]=='P'&&data[2]=='N'&&data[3]=='G';
            boolean jpg=data.length>3&&data[0]==(byte)0xFF&&data[1]==(byte)0xD8;
            if(!png&&!jpg){warnings.add("仅导入 PNG/JPEG 图片；其他图片格式未导入");return null;}
            Path temp=Files.createTempFile("wiki-docx-image-",png?".png":".jpg");
            try {Files.write(temp,data);imported=images.importImage(temp);importedImages.put(path,imported);}
            finally {Files.deleteIfExists(temp);}
        }
        List<Element> sizes=all(drawing,"extent");int width=0;
        if(!sizes.isEmpty())try{width=(int)Math.max(1,Math.min(16384,Long.parseLong(attr(sizes.getFirst(),"cx"))/9525));}catch(NumberFormatException ignored){}
        List<Element> descriptions=all(drawing,"docPr");String caption=descriptions.isEmpty()?"":attr(descriptions.getFirst(),"descr");
        if(!all(drawing,"anchor").isEmpty())warnings.add("浮动和环绕图片已转换为行内图片");
        return new ImageBlock(imported.assetHash(),caption,ImageAlignment.LEFT,width);
    }
    private TableBlock table(Element element) throws IOException {
        if (++tableDepth > 16) throw new IOException("DOCX 表格嵌套过深");
        List<List<TableCell>> rows=new ArrayList<>();int max=0;
        for(Element tr:children(element))if("tr".equals(tr.getLocalName())) {
            List<TableCell> cells=new ArrayList<>();
            for(Element tc:children(tr))if("tc".equals(tc.getLocalName())) {
                if(cells.size() >= 64) throw new IOException("DOCX 表格列数超过 64");
                List<Block> content=new ArrayList<>();readBlocks(tc,content,0);List<TextRun> runs=new ArrayList<>();
                for(Block b:content) {
                    if(!runs.isEmpty())runs.add(new TextRun("\n",TextStyle.DEFAULT));
                    if(b instanceof TextualBlock)runs.addAll(b.runs());
                    else {runs.add(new TextRun(b instanceof ImageBlock?"[图片]":"[嵌套对象]",TextStyle.DEFAULT));warnings.add("表格内图片和嵌套对象以文字占位保留");}
                }
                cells.add(new TableCell(runs));Element pr=child(tc,"tcPr");
                int span=number(val(child(pr,"gridSpan")),1);
                if(span>64||span<1)throw new IOException("表格合并跨度超出限制");
                if(span>1||child(pr,"vMerge")!=null)warnings.add("合并单元格展开为普通单元格");
                for(int i=1;i<span;i++)cells.add(new TableCell(List.of()));
            }
            if(!cells.isEmpty()){rows.add(cells);max=Math.max(max,cells.size());}
            if(max>64||rows.size()>2000)throw new IOException("DOCX 表格过大");
        }
        if(rows.isEmpty()) { tableDepth--; return new TableBlock(List.of(new TableRow(List.of(new TableCell(List.of()))))); }
        List<TableRow> result=new ArrayList<>();for(List<TableCell> row:rows){while(row.size()<max)row.add(new TableCell(List.of()));result.add(new TableRow(row));}
        tableDepth--; return new TableBlock(result);
    }
    private static boolean on(Element e) { return !Set.of("false","0","off","none").contains(val(e)); }
    private static TextStyle style(TextStyle base,Element p) {
        if(p==null)return base;TextStyle s=base;
        if(child(p,"b")!=null)s=s.withBold(on(child(p,"b")));
        if(child(p,"i")!=null)s=s.withItalic(on(child(p,"i")));
        if(child(p,"strike")!=null)s=s.withStrikethrough(on(child(p,"strike")));
        if(child(p,"u")!=null)s=s.withUnderline(on(child(p,"u")));
        if(child(p,"vanish")!=null)s=s.withHidden(on(child(p,"vanish")));
        if(child(p,"sz")!=null)s=s.withFontSize(Math.max(6,Math.min(72,Math.round(number(val(child(p,"sz")),22)/2f))));
        if(child(p,"vertAlign")!=null)s=s.withScript(switch(val(child(p,"vertAlign"))){case "superscript"->1;case "subscript"->-1;default->0;});
        String color=val(child(p,"color"));if(color.matches("[0-9a-fA-F]{6}"))s=s.withColor(Integer.parseInt(color,16));
        return s;
    }
}
