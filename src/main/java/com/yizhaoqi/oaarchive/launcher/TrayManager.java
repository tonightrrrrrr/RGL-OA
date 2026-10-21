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
                popup.setLocation(position(click, popup.getWidth(), popup.getHeight()));
                popup.setVisible(true);
                popup.toFront();
                popup.requestFocus();
            });
        }, "tray-stats").start();
    }

    /** 面板放在鼠标上方偏左，不超出屏幕可用区域（避开任务栏） */
    static Point position(Point click, int w, int h) {
        GraphicsConfiguration gc = popup.getGraphicsConfiguration();
        Rectangle screen = gc.getBounds();
        Insets in = Toolkit.getDefaultToolkit().getScreenInsets(gc);
        int left = screen.x + in.left, right = screen.x + screen.width - in.right;
        int top = screen.y + in.top, bottom = screen.y + screen.height - in.bottom;
        int x = Math.min(Math.max(click.x - w + 24, left + 8), right - w - 8);
        int y = click.y - h - 12;
        if (y < top + 8) {
            y = Math.min(click.y + 12, bottom - h - 8);
        }
        return new Point(x, Math.max(y, top + 8));
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
