package com.yizhaoqi.oaarchive.recognize;

import com.yizhaoqi.oaarchive.parser.TextCleaner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 文件名解析：去掉扩展名、按分隔符切段，供部门、类型、日期、标题识别使用。
 * 先把日期整体抽走再切分，避免“2026-03-31”被“-”切碎。
 */
public record FileNameInfo(String original, String base, List<String> segments, List<String> dates) {

    static final Pattern DATE_IN_NAME = Pattern.compile(
            "(?<!\\d)(19|20)\\d{2}\\s*[-/._年]\\s*\\d{1,2}\\s*[-/._月]\\s*\\d{1,2}\\s*日?(?!\\d)|(?<!\\d)(19|20)\\d{6}(?!\\d)");
    private static final Pattern SPLIT = Pattern.compile("[_\\s　、+＋—–()（）\\[\\]【】]+");

    public static FileNameInfo of(String fileName) {
        String base = fileName;
        int dot = base.lastIndexOf('.');
        if (dot > 0) {
            base = base.substring(0, dot);
        }
        base = TextCleaner.clean(base);
        List<String> dates = new ArrayList<>();
        var m = DATE_IN_NAME.matcher(base);
        while (m.find()) {
            dates.add(m.group());
        }
        String withoutDates = DATE_IN_NAME.matcher(base).replaceAll(" ");
        List<String> segs = new ArrayList<>(Arrays.stream(SPLIT.split(withoutDates))
                .map(String::strip).filter(s -> !s.isEmpty()).toList());
        return new FileNameInfo(fileName, base, segs, dates);
    }

    private static final Pattern GENERIC = Pattern.compile(
            "(?i)^(img|dsc|scan|image|photo|pic|doc|file|微信图片|扫描件|扫描|文档|新建文档|新建|未命名|截图|屏幕截图)?[\\d\\-_.()（） ]*$");

    /** 文件名是否有实际含义（排除“扫描件001”“IMG_2034”“文档1”这类） */
    public boolean meaningful() {
        String joined = String.join("", segments);
        return joined.replaceAll("[\\d\\s]", "").length() >= 2 && !GENERIC.matcher(joined).matches();
    }
}
