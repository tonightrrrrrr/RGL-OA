package com.yizhaoqi.oaarchive.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;
import javax.swing.JComponent;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 把托盘面板离屏渲染成图片（target/tray-panel.png），检查中文字体可用、布局不溢出。
 */
@EnabledOnOs(OS.WINDOWS)
class TrayPanelRenderTest {

    @Test
    void rendersChineseWithCjkFont() throws Exception {
        TrayPanel p = new TrayPanel(new TrayPanel.Actions() {
            public void openPage(String hashPath) { }

            public void openArchiveDir() { }

            public void openLogDir() { }

            public void exit() { }
        }, () -> { });
        JComponent c = p.build();
        p.update(new TrayPanel.Stats(8, 2, 136, false));
        Dimension d = c.getPreferredSize();
        c.setSize(d);
        layout(c);
        BufferedImage img = new BufferedImage(d.width, d.height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        c.paint(g);
        g.dispose();
        ImageIO.write(img, "png", new File("target/tray-panel.png"));
        // 系统里必须有能显示中文的字体，否则会退回到 Dialog 字体
        assertThat(TrayPanel.font(13, false).canDisplayUpTo("打开主界面待确认台账")).isEqualTo(-1);
        assertThat(d.width).isEqualTo(TrayPanel.WIDTH);
    }

    private static void layout(java.awt.Component c) {
        c.doLayout();
        if (c instanceof java.awt.Container ct) {
            for (java.awt.Component child : ct.getComponents()) {
                layout(child);
            }
        }
    }
}
