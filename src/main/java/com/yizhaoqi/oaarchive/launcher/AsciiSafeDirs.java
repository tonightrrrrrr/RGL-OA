package com.yizhaoqi.oaarchive.launcher;

import java.nio.file.Path;

/**
 * OCR 原生库通过 C 接口读取模型和图片文件，路径中含中文时可能失败。
 * 启动时如果系统临时目录含非 ASCII 字符（例如中文用户名），就把 java.io.tmpdir 改到 ASCII 路径。
 */
public final class AsciiSafeDirs {

    private AsciiSafeDirs() {
    }

    public static boolean isAscii(String s) {
        return s.chars().allMatch(c -> c < 128);
    }

    /** 必须在 Spring 和 OCR 库初始化之前调用 */
    public static void ensureAsciiTempDir(Path home) {
        String tmp = System.getProperty("java.io.tmpdir");
        if (tmp != null && isAscii(tmp)) {
            return;
        }
        for (Path candidate : candidates(home)) {
            if (isAscii(candidate.toString()) && AppHome.isWritable(candidate)) {
                System.setProperty("java.io.tmpdir", candidate.toString());
                return;
            }
        }
        // 找不到 ASCII 路径时保持原样，OCR 可能不可用，但其他功能不受影响
    }

    private static Path[] candidates(Path home) {
        String publicDir = System.getenv("PUBLIC");
        String programData = System.getenv("ProgramData");
        return new Path[]{
                home.resolve("tmp"),
                publicDir != null ? Path.of(publicDir, "OA-Archive-Agent", "tmp") : home.resolve("tmp"),
                programData != null ? Path.of(programData, "OA-Archive-Agent", "tmp") : home.resolve("tmp"),
                Path.of("C:\\OA-Archive-Agent-tmp")
        };
    }
}
