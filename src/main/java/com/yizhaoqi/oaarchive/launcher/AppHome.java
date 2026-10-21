package com.yizhaoqi.oaarchive.launcher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 确定数据目录（技术文档 6.10）：
 * 显式指定（oa.home / OA_HOME）→ 程序目录下的 data（可写时）→ %LOCALAPPDATA%\OA-Archive-Agent\data。
 */
public final class AppHome {

    private AppHome() {
    }

    public static Path resolve() {
        String explicit = firstNonBlank(System.getProperty("oa.home"), System.getenv("OA_HOME"));
        if (explicit != null) {
            return Path.of(explicit).toAbsolutePath().normalize();
        }
        Path candidate = appDirectory().resolve("data");
        if (isWritable(candidate)) {
            return candidate;
        }
        String localAppData = System.getenv("LOCALAPPDATA");
        Path base = localAppData != null ? Path.of(localAppData) : Path.of(System.getProperty("user.home"));
        return base.resolve("OA-Archive-Agent").resolve("data");
    }

    /** jpackage 打包后为 exe 所在目录；开发时为当前工作目录 */
    static Path appDirectory() {
        String exe = System.getProperty("jpackage.app-path");
        if (exe != null) {
            return Path.of(exe).toAbsolutePath().getParent();
        }
        return Path.of(System.getProperty("user.dir")).toAbsolutePath();
    }

    static boolean isWritable(Path dir) {
        try {
            Files.createDirectories(dir);
            Path probe = Files.createTempFile(dir, ".probe", ".tmp");
            Files.delete(probe);
            return true;
        } catch (IOException | SecurityException e) {
            return false;
        }
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }
}
