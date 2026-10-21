package com.yizhaoqi.oaarchive.parser;

import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 按扩展名选择解析器，解析后统一做文本清洗。
 */
@Component
public class ParserRegistry {

    /** 导入时接受的扩展名（技术文档 6.1.2）；其他扩展名也会尝试用 Tika 解析 */
    public static final Set<String> KNOWN_EXTS = Set.of(
            "docx", "doc", "xlsx", "xls", "pdf", "txt", "rtf", "odt", "ods", "wps", "et",
            "jpg", "jpeg", "png", "bmp", "tif", "tiff", "gif", "webp");

    private final List<DocumentParser> specific;
    private final TikaFallbackParser fallback;

    public ParserRegistry(List<DocumentParser> parsers, TikaFallbackParser fallback) {
        this.specific = parsers.stream().filter(p -> !(p instanceof TikaFallbackParser)).toList();
        this.fallback = fallback;
    }

    public DocModel parse(Path file, String originalName) throws Exception {
        String ext = extension(originalName);
        DocumentParser parser = specific.stream().filter(p -> p.supports(ext)).findFirst().orElse(fallback);
        return clean(parser.parse(file, originalName));
    }

    public static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static DocModel clean(DocModel m) {
        List<Block> cleaned = new ArrayList<>();
        for (Block b : m.blocks()) {
            String t = TextCleaner.clean(b.text());
            if (!t.isEmpty()) {
                cleaned.add(b.withText(t));
            }
        }
        return new DocModel(m.fileName(), m.format(), m.pageCount(), cleaned, m.meta(), m.ocrUsed(), m.notes());
    }
}
