package com.yizhaoqi.oaarchive.archive;

import java.util.Locale;
import java.util.Set;

/**
 * 文件名清洗（技术文档 6.7.2）：Windows 非法字符换成全角，去掉首尾空格和点号，避开保留名。
 */
public final class FileNameSanitizer {

    private static final String ILLEGAL = "\\/:*?\"<>|";
    private static final String FULL_WIDTH = "＼／：＊？＂＜＞｜";
    private static final Set<String> RESERVED = Set.of("CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");

    private FileNameSanitizer() {
    }

    public static String sanitize(String name) {
        if (name == null) {
            return "未命名";
        }
        StringBuilder sb = new StringBuilder(name.length());
        for (char c : name.toCharArray()) {
            int idx = ILLEGAL.indexOf(c);
            if (idx >= 0) {
                sb.append(FULL_WIDTH.charAt(idx));
            } else if (c >= 32 && c != 127) {
                sb.append(c);
            }
        }
        String s = sb.toString().strip().replaceAll("[. ]+$", "");
        s = s.replaceAll("_{2,}", "_").replaceAll("^[_\\-]+|[_\\-]+$", "");
        if (s.isEmpty()) {
            return "未命名";
        }
        String base = s.contains(".") ? s.substring(0, s.indexOf('.')) : s;
        if (RESERVED.contains(base.toUpperCase(Locale.ROOT))) {
            s = s + "_";
        }
        return s;
    }
}
