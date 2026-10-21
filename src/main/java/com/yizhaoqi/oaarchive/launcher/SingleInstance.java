package com.yizhaoqi.oaarchive.launcher;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 单实例控制：在数据目录持有文件锁，并把当前端口写入 instance.port。
 * 第二次启动时读取端口，直接打开已有页面后退出。
 */
public final class SingleInstance {

    private final Path home;
    private final Path portFile;
    // 锁在进程生命周期内一直持有，进程退出时由操作系统释放
    private FileChannel channel;
    private FileLock lock;

    public SingleInstance(Path home) {
        this.home = home;
        this.portFile = home.resolve("instance.port");
    }

    /** 拿到锁返回 true；已有实例在运行返回 false */
    public boolean tryAcquire() throws IOException {
        Files.createDirectories(home);
        channel = FileChannel.open(home.resolve("instance.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        lock = channel.tryLock();
        if (lock == null) {
            channel.close();
            return false;
        }
        return true;
    }

    public void writePort(int port) {
        try {
            Files.writeString(portFile, String.valueOf(port), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // 写不了端口文件只影响“重复打开时跳转到已有页面”，不影响主功能
        }
    }

    public Integer readPort() {
        try {
            return Integer.parseInt(Files.readString(portFile, StandardCharsets.UTF_8).trim());
        } catch (IOException | NumberFormatException e) {
            return null;
        }
    }
}
