package com.yizhaoqi.oaarchive.fileops;

import com.yizhaoqi.oaarchive.common.BizException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * 删除到系统回收站，不做永久删除。
 * 优先用 Java 自带的 Desktop.moveToTrash；不可用时（比如没有图形界面）在 Windows 上调用系统的“删除到回收站”。
 * 自动化测试里配置 oa.fileops.trash-dir，删除的文件移到这个目录，不碰真正的回收站。
 */
@Component
public class Trash {

    private final String testDir;

    public Trash(@Value("${oa.fileops.trash-dir:}") String testDir) {
        this.testDir = testDir;
    }

    public void delete(Path p) {
        if (!Files.exists(p)) {
            throw new BizException("要删除的文件已经不存在：" + p);
        }
        if (!testDir.isBlank()) {
            moveToTestDir(p);
            return;
        }
        if (!GraphicsEnvironment.isHeadless() && Desktop.isDesktopSupported()
                && Desktop.getDesktop().isSupported(Desktop.Action.MOVE_TO_TRASH)) {
            if (Desktop.getDesktop().moveToTrash(p.toFile()) && !Files.exists(p)) {
                return;
            }
        }
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            windowsRecycle(p);
            return;
        }
        throw new BizException("当前系统不支持删除到回收站，为避免永久删除，已取消操作");
    }

    private void windowsRecycle(Path p) {
        String path = p.toString().replace("'", "''");
        String method = Files.isDirectory(p) ? "DeleteDirectory" : "DeleteFile";
        String script = "Add-Type -AssemblyName Microsoft.VisualBasic; [Microsoft.VisualBasic.FileIO.FileSystem]::" + method
                + "('" + path + "', 'OnlyErrorDialogs', 'SendToRecycleBin')";
        try {
            Process proc = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", script)
                    .redirectErrorStream(true).start();
            proc.getInputStream().readAllBytes();
            if (!proc.waitFor(60, TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                throw new BizException("删除到回收站超时：" + p);
            }
        } catch (IOException e) {
            throw new BizException("删除到回收站失败：" + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException("删除被中断");
        }
        if (Files.exists(p)) {
            throw new BizException("没能删除到回收站（文件可能正被其他程序占用）：" + p);
        }
    }

    private void moveToTestDir(Path p) {
        try {
            Path dir = Path.of(testDir);
            Files.createDirectories(dir);
            String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HHmmssSSS"));
            Files.move(p, dir.resolve(stamp + "_" + p.getFileName()));
        } catch (IOException e) {
            throw new BizException("删除失败：" + e.getMessage());
        }
    }
}
