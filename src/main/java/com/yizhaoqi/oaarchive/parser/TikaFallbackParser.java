package com.yizhaoqi.oaarchive.parser;

import org.apache.tika.Tika;
import org.apache.tika.metadata.Metadata;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 兜底解析：txt、rtf、odt、wps 等格式交给 Tika 抽取纯文本（没有字号和坐标）。
 */
@Component
public class TikaFallbackParser implements DocumentParser {

    private static final int MAX_CHARS = 200_000;
    private final Tika tika = new Tika();

    public TikaFallbackParser() {
        tika.setMaxStringLength(MAX_CHARS);
    }

    @Override
    public boolean supports(String ext) {
        return true; // 由 ParserRegistry 保证只在其他解析器都不支持时才用
    }

    @Override
    public DocModel parse(Path file, String originalName) throws Exception {
        Metadata md = new Metadata();
        String text;
        try (InputStream in = Files.newInputStream(file)) {
            text = tika.parseToString(in, md);
        }
        List<Block> blocks = new ArrayList<>();
        for (String line : text.split("\\r?\\n")) {
            if (!line.isBlank()) {
                blocks.add(Block.text(line, 1, 0, null, false));
            }
        }
        String ext = originalName.contains(".") ? originalName.substring(originalName.lastIndexOf('.') + 1).toLowerCase() : "unknown";
        String type = md.get("Content-Type");
        return new DocModel(originalName, ext, 1, blocks, type == null ? Map.of() : Map.of("contentType", type), false, new ArrayList<>());
    }
}
