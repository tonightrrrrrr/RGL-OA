package com.yizhaoqi.oaarchive.launcher;

import java.awt.AWTException;
import java.awt.Color;
import java.awt.Dialog;
import java.awt.GraphicsConfiguration;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.SystemTray;
import java.awt.Toolkit;
import java.awt.TrayIcon;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.function.Supplier;
import javax.swing.JDialog;
import javax.swing.SwingUtilities;

/**
 * 系统托盘：点击图标弹出自绘面板（{@link TrayPanel}），代替系统原生菜单。
 * 原生菜单在精简运行时里中文会显示成方块，自绘面板可以指定中文字体，样式也更现代。
 */
public final class TrayManager {

    private static JDialog popup;
    private static TrayPanel panel;
    private static long lastHidden;

    private TrayManager() {
    }

    public static void install(String url, Supplier<TrayPanel.Stats> stats, Supplier<File> archiveRoot, File logDir,
                               Runnable onExit) {
        if (!SystemTray.isSupported()) {
            return;
        }
        SwingUtilities.invokeLater(() -> {
            TrayPanel.Actions actions = new TrayPanel.Actions() {
                @Override
                public void openPage(String hashPath) {
                    DesktopSupport.browse(url + hashPath);
                }

                @Override
                public void openArchiveDir() {
                    File dir = archiveRoot.get();
                    dir.mkdirs();
                    DesktopSupport.open(dir);
                }

                @Override
                public void openLogDir() {
                    logDir.mkdirs();
                    DesktopSupport.open(logDir);
                }

                @Override
                public void exit() {
                    onExit.run();
                }
            };
            panel = new TrayPanel(actions, TrayManager::hide);
            popup = createPopup(panel);

            // 按系统托盘的实际尺寸取图（通常 16–24px，是只有脸的版本），避免把带字的大图缩成一团
            int traySize = Math.max(16, SystemTray.getSystemTray().getTrayIconSize().width);
            TrayIcon icon = new TrayIcon(createIcon(traySize), BrandIcon.FULL_NAME);
            icon.setImageAutoSize(true);
            icon.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseReleased(MouseEvent e) {
                    if (e.getClickCount() >= 2 && SwingUtilities.isLeftMouseButton(e)) {
                        hide();
                        DesktopSupport.browse(url);
                        return;
                    }
                    toggle(e.getLocationOnScreen(), stats);
                }
            });
            try {
                SystemTray.getSystemTray().add(icon);
            } catch (AWTException ignored) {
                // 托盘不可用时程序照常运行
            }
        });
    }

    private static JDialog createPopup(TrayPanel content) {
        JDialog d = new JDialog((Window) null, Dialog.ModalityType.MODELESS);
        d.setUndecorated(true);
        d.setAlwaysOnTop(true);
        d.setType(Window.Type.POPUP); // 不在任务栏显示
        try {
            d.setBackground(new Color(0, 0, 0, 0)); // 透明背景，露出面板的圆角
        } catch (UnsupportedOperationException ignored) {
            // 系统不支持透明窗口时退回直角
        }
        d.setContentPane(content.build());
        d.pack();
        d.addWindowFocusListener(new WindowAdapter() {
            @Override
            public void windowLostFocus(WindowEvent e) {
                hide();
            }
        });
        return d;
    }

    private static void toggle(Point click, Supplier<TrayPanel.Stats> stats) {
        if (popup.isVisible()) {
            hide();
            return;
        }
        // 点击托盘图标会先让面板失去焦点而关闭，紧接着又触发这里；300ms 内视为“关闭”操作
        if (System.currentTimeMillis() - lastHidden < 300) {
            return;
        }
        new Thread(() -> {
            TrayPanel.Stats s;
            try {
                s = stats.get();
            } catch (Exception e) {
                s = new TrayPanel.Stats(0, 0, 0, false);
            }
            TrayPanel.Stats data = s;
            SwingUtilities.invokeLater(() -> {
                panel.update(data);
                popup.pack();
                Screen sc = screenAt();
                popup.setLocation(position(sc.toLogical(click), popup.getWidth(), popup.getHeight(), sc.work()));
                popup.setVisible(true);
                popup.toFront();
                popup.requestFocus();
            });
        }, "tray-stats").start();
    }

    /** 一块屏幕：逻辑坐标的边界、去掉任务栏后的可用区域、缩放比例；pointer 是 MouseInfo 取到的鼠标位置（取不到为 null） */
    record Screen(Rectangle bounds, Rectangle work, double scale, Point pointer) {

        /**
         * 托盘事件的坐标在缩放屏幕上可能是物理像素（150% 时比逻辑坐标大 1.5 倍），面板会跑到屏幕下方。
         * 优先用 MouseInfo 的鼠标位置（已验证在缩放屏幕上是逻辑坐标）；取不到时，
         * 落在逻辑边界外的事件坐标按缩放比例换算回来。
         */
        Point toLogical(Point event) {
            if (pointer != null) {
                return pointer;
            }
            if (event == null || bounds.contains(event) || scale <= 1.0) {
                return event;
            }
            Point q = new Point((int) Math.round(event.x / scale), (int) Math.round(event.y / scale));
            return bounds.contains(q) ? q : event;
        }
    }

    /** 鼠标所在的屏幕（多屏时不一定是主屏）；取不到时用主屏 */
    static Screen screenAt() {
        GraphicsConfiguration gc = null;
        Point pointer = null;
        try {
            java.awt.PointerInfo pi = java.awt.MouseInfo.getPointerInfo();
            if (pi != null) {
                gc = pi.getDevice().getDefaultConfiguration();
                pointer = pi.getLocation();
            }
        } catch (RuntimeException ignored) {
            // 取不到鼠标位置时按事件坐标换算
        }
        if (gc == null) {
            gc = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
        }
        Rectangle b = gc.getBounds();
        Insets in = Toolkit.getDefaultToolkit().getScreenInsets(gc);
        Rectangle work = new Rectangle(b.x + in.left, b.y + in.top, b.width - in.left - in.right, b.height - in.top - in.bottom);
        return new Screen(b, work, gc.getDefaultTransform().getScaleX(), pointer);
    }

    /**
     * 面板放在鼠标上方偏左；上方放不下时放在下方。无论鼠标坐标是否可信，整个面板都限制在可用区域内
     * （v1.9：原来只限制了上边，缩放屏幕上面板下半截会跑到屏幕外，见技术难点 7.10）。
     */
    static Point position(Point click, int w, int h, Rectangle work) {
        int margin = 8;
        int minX = work.x + margin, maxX = work.x + work.width - w - margin;
        int minY = work.y + margin, maxY = work.y + work.height - h - margin;
        int x = click.x - w + 24;
        int y = click.y - h - 12;
        if (y < minY) {
            y = click.y + 12;
        }
        x = Math.max(minX, Math.min(x, maxX));
        y = Math.max(minY, Math.min(y, maxY)); // 面板比可用区域还高时贴着上边，至少能看到标题和前几项
        return new Point(x, y);
    }

    private static void hide() {
        if (popup != null && popup.isVisible()) {
            popup.setVisible(false);
            lastHidden = System.currentTimeMillis();
        }
    }

    /** 64px 程序图标 */
    public static BufferedImage createIcon() {
        return createIcon(64);
    }

    /** 程序图标“RGL”，打包时也用它生成 exe 图标（见 {@link BrandIcon}） */
    public static BufferedImage createIcon(int size) {
        return BrandIcon.create(size);
    }
}
