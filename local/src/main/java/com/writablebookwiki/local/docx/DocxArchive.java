package com.writablebookwiki.local.docx;

import com.writablebookwiki.local.DocxIO.*;

import com.writablebookwiki.local.storage.AtomicFiles;

import com.writablebookwiki.core.document.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;

/** Bounded OOXML interchange. No Office installation, macros, remote fetches or scripts are used. */
public final class DocxArchive {
    private DocxArchive() { }
    static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    static final String R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    static final String REL = "http://schemas.openxmlformats.org/package/2006/relationships";
    static final String XML = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>";
    static final int MAX_XML = 8 * 1024 * 1024, MAX_ASSET = 32 * 1024 * 1024;
    static final long MAX_TOTAL = 128L * 1024 * 1024;

    public static ImportResult read(Path source, DocumentMetadata metadata, ImageImporter images) throws IOException {
        if (Files.size(source) > MAX_TOTAL) throw new IOException("DOCX 文件超过 128 MB");
        try (ZipFile zip = new ZipFile(source.toFile())) {
            if (zip.size() > 2048) throw new IOException("DOCX 文件包含过多部件");
            Set<String> names = new HashSet<>();
            long total = 0;
            for (var entries = zip.entries(); entries.hasMoreElements();) {
                ZipEntry e = entries.nextElement();
                if (!names.add(e.getName()) || e.getSize() < 0 || e.getSize() > MAX_ASSET)
                    throw new IOException("DOCX 部件重复、过大或长度无效");
                total += e.getSize();
                if (total > MAX_TOTAL) throw new IOException("DOCX 解压内容超过 128 MB");
            }
            return new DocxReader(zip, metadata, images).read(source.getFileName().toString());
        } catch (IllegalArgumentException e) { throw new IOException("DOCX 内容无效：" + e.getMessage(), e); }
    }

    /** Writes to a sibling temporary file and replaces the destination only after the ZIP is complete. */
    public static List<String> write(Path destination, WikiDocument document, ImageResolver images) throws IOException {
        return AtomicFiles.replace(destination, temporary -> {
            DocxWriter writer;
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(temporary))) {
                writer = new DocxWriter(zip, images);
                writer.write(document);
            }
            return List.copyOf(writer.warnings);
        });
    }

    static byte[] part(ZipFile zip, String name, int limit) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) return null;
        if (entry.getSize() > limit) throw new IOException("DOCX 部件过大：" + name);
        try (InputStream stream = zip.getInputStream(entry)) {
            byte[] bytes = stream.readNBytes(limit + 1);
            if (bytes.length > limit) throw new IOException("DOCX 部件超出限制：" + name);
            return bytes;
        }
    }
    static Element xml(byte[] bytes) throws IOException {
        if (bytes == null) return null;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            return factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes)).getDocumentElement();
        } catch (Exception e) { throw new IOException("DOCX XML 无法读取", e); }
    }
    static List<Element> children(Element parent) {
        if (parent == null) return List.of();
        List<Element> result = new ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) if (n instanceof Element e) result.add(e);
        return result;
    }
    static Element child(Element parent, String local) {
        return children(parent).stream().filter(e -> local.equals(e.getLocalName())).findFirst().orElse(null);
    }
    static List<Element> all(Element parent, String local) {
        if (parent == null) return List.of();
        NodeList nodes = parent.getElementsByTagNameNS("*", local);
        List<Element> result = new ArrayList<>();
        for (int i = 0; i < nodes.getLength(); i++) result.add((Element) nodes.item(i));
        return result;
    }
    static String attr(Element e, String name) {
        if (e == null) return "";
        NamedNodeMap attrs = e.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) if (name.equals(attrs.item(i).getLocalName()) || name.equals(attrs.item(i).getNodeName())) return attrs.item(i).getNodeValue();
        return "";
    }
    static String val(Element e) { return attr(e, "val"); }
    static int number(String text, int fallback) { try { return Integer.parseInt(text); } catch (NumberFormatException e) { return fallback; } }
    static String esc(String text) {
        StringBuilder clean = new StringBuilder();
        text.codePoints().filter(c -> c == 9 || c == 10 || c == 13 || c >= 32 && c <= 0xD7FF || c >= 0xE000 && c <= 0xFFFD || c >= 0x10000 && c <= 0x10FFFF).forEach(clean::appendCodePoint);
        return clean.toString().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
    static void put(ZipOutputStream zip, String name, String text) throws IOException {
        zip.putNextEntry(new ZipEntry(name)); zip.write(text.getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
    }
}
