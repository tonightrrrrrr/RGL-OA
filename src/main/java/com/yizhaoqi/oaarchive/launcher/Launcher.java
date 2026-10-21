package com.yizhaoqi.oaarchive.launcher;

import com.yizhaoqi.oaarchive.OaArchiveApplication;
import com.yizhaoqi.oaarchive.config.OaProperties;
import com.yizhaoqi.oaarchive.model.FileRecord;
import com.yizhaoqi.oaarchive.repository.FileRecordRepository;
import com.yizhaoqi.oaarchive.repository.LedgerEntryRepository;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import javax.swing.JOptionPane;

/**
 * 程序入口（技术文档 6.10）：确定数据目录 → 单实例检查 → 选端口 → 启动 Spring Boot → 托盘与浏览器。
 */
public final class Launcher {

    private static final int DEFAULT_PORT = 18080;
    private static final int PORT_ATTEMPTS = 20;
    /** 进程生命周期内持有单实例锁 */
    private static volatile SingleInstance INSTANCE;

    private Launcher() {
    }

    public static void main(String[] args) {
        boolean docker = "docker".equalsIgnoreCase(System.getenv("OA_MODE"));
        if (docker) {
            // Docker 模式：不弹窗、不托盘、不选端口，配置来自 application-docker.yml
            System.setProperty("spring.profiles.active", "docker");
            new SpringApplicationBuilder(OaArchiveApplication.class).headless(true).run(args);
            return;
        }

        Path home = AppHome.resolve();
        System.setProperty("oa.home", home.toString());
        AsciiSafeDirs.ensureAsciiTempDir(home);

        SingleInstance instance = new SingleInstance(home);
        INSTANCE = instance; // 必须用静态字段持有：main 返回后局部变量会被回收，文件锁随之释放，第二个实例就能启动并锁死数据库
        try {
            if (!instance.tryAcquire()) {
                Integer port = instance.readPort();
                DesktopSupport.browse("http://127.0.0.1:" + (port != null ? port : DEFAULT_PORT) + "/");
                return;
            }
        } catch (IOException e) {
            // 无法加锁时仍然继续启动，只是失去单实例保护
        }

        if (System.getProperty("server.port") == null && System.getenv("SERVER_PORT") == null) {
            System.setProperty("server.port", String.valueOf(findFreePort()));
        }

        ConfigurableApplicationContext ctx;
        try {
            // 关闭 headless：选文件夹窗口和托盘图标都需要图形界面
            ctx = new SpringApplicationBuilder(OaArchiveApplication.class).headless(false).run(args);
        } catch (Exception e) {
            showStartupError(e, home);
            System.exit(1);
            return;
        }

        int port = Integer.parseInt(ctx.getEnvironment().getProperty("local.server.port", String.valueOf(DEFAULT_PORT)));
        instance.writePort(port);
        String url = "http://127.0.0.1:" + port + "/";

        OaProperties props = ctx.getBean(OaProperties.class);
        if (props.getDesktop().isTray()) {
            FileRecordRepository files = ctx.getBean(FileRecordRepository.class);
            LedgerEntryRepository ledger = ctx.getBean(LedgerEntryRepository.class);
            TrayManager.install(url,
                    () -> new TrayPanel.Stats(
                            files.countByStatus(FileRecord.Status.READY) + files.countByStatus(FileRecord.Status.UNDONE),
                            files.countByStatus(FileRecord.Status.REVIEW),
                            ledger.countByUndoneFalse(),
                            files.countByStatus(FileRecord.Status.QUEUED) + files.countByStatus(FileRecord.Status.PARSING) > 0),
                    () -> new File(props.getArchive().getDefaultRoot()),
                    home.resolve("logs").toFile(),
                    () -> {
                        ctx.close();
                        System.exit(0);
                    });
        }
        if (props.getDesktop().isOpenBrowser()) {
            DesktopSupport.browse(url);
        }
    }

    /** 从 18080 开始找空闲端口，最多尝试 20 次；都被占用时交给系统分配 */
    static int findFreePort() {
        for (int port = DEFAULT_PORT; port < DEFAULT_PORT + PORT_ATTEMPTS; port++) {
            try (ServerSocket s = new ServerSocket(port, 1, InetAddress.getLoopbackAddress())) {
                s.setReuseAddress(true);
                return port;
            } catch (IOException ignored) {
                // 端口被占用，尝试下一个
            }
        }
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return s.getLocalPort();
        } catch (IOException e) {
            return DEFAULT_PORT;
        }
    }

    /** exe 没有控制台窗口，启动失败时弹窗提示，否则用户只会看到“双击没反应” */
    private static void showStartupError(Exception e, Path home) {
        Throwable root = e;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        String detail = String.valueOf(root.getMessage());
        String msg;
        if (detail.contains("file is locked") || detail.contains("already in use")) {
            msg = "程序已经在运行中（数据文件被占用）。\n请在屏幕右下角托盘区找到“OA”图标打开界面；"
                    + "如果找不到，请在任务管理器中结束 OA-Archive-Agent 后重新打开。";
        } else {
            msg = "程序启动失败：" + detail + "\n\n日志目录：" + home.resolve("logs");
        }
        try {
            JOptionPane.showMessageDialog(null, msg, BrandIcon.FULL_NAME, JOptionPane.ERROR_MESSAGE);
        } catch (Exception ignored) {
            System.err.println(msg);
        }
    }
}
