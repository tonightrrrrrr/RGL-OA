package com.yizhaoqi.oaarchive.launcher;

import java.awt.Desktop;
import java.io.File;
import java.net.URI;

/**
 * 调用操作系统打开浏览器、文件和文件夹。Desktop API 不可用时退回到 Windows 命令。
 */
public final class DesktopSupport {

    private DesktopSupport() {
    }

    public static void browse(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
                return;
            }
        } catch (Exception ignored) {
            // 退回到命令方式
        }
        exec("rundll32", "url.dll,FileProtocolHandler", url);
    }

    public static void open(File file) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(file);
                return;
            }
        } catch (Exception ignored) {
            // 退回到命令方式
        }
        exec("explorer", file.getAbsolutePath());
    }

    /** 打开资源管理器并选中文件 */
    public static void reveal(File file) {
        if (isWindows()) {
            exec("explorer", "/select,", file.getAbsolutePath());
        } else {
            open(file.getParentFile());
        }
    }

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static void exec(String... cmd) {
        try {
            new ProcessBuilder(cmd).start();
        } catch (Exception ignored) {
            // 打不开时由调用方在界面上提示
        }
    }
}
