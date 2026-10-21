package com.yizhaoqi.oaarchive.recognize;

import com.yizhaoqi.oaarchive.parser.Block;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 区域切分结果（技术文档 6.4.1）。
 *
 * @param title  标题区
 * @param header 抬头字段区（标题之后、正文之前的“字段名：字段值”行）
 * @param body   正文
 * @param footer 落款区
 */
public record Regions(List<Block> title, List<Block> header, List<Block> body, List<Block> footer) {

    public String titleText() {
        return title.stream().map(Block::text).collect(Collectors.joining());
    }

    public static String join(List<Block> blocks) {
        return blocks.stream().map(Block::text).collect(Collectors.joining("\n"));
    }
}
