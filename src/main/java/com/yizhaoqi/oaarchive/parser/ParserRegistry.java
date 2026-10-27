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

    /** 扩展名最长的长度，和 file_record.ext 列一致 */
    static final int MAX_EXT_LENGTH = 16;

    /**
     * 小写扩展名；没有扩展名，或“扩展名”不像扩展名（超长、含空格等，如缓存文件名里的哈希）时返回空串，
     * 交给 Tika 按内容判断。否则超长扩展名会写不进数据库，整个导入失败。
     */
    public static String extension(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0) {
            return "";
        }
        String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        return ext.length() <= MAX_EXT_LENGTH && ext.chars().allMatch(Character::isLetterOrDigit) ? ext : "";
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
