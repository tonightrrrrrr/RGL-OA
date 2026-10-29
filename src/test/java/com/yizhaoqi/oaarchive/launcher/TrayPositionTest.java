package com.yizhaoqi.oaarchive.launcher;

import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.awt.Rectangle;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.9 托盘面板位置（技术难点 7.10）：1920×1080、缩放 150% 的笔记本上，Java 的逻辑屏幕是 1280×720，
 * 托盘事件给出的却可能是物理像素坐标，面板下半截跑到屏幕外。面板必须整个落在可用区域内。
 */
class TrayPositionTest {

    static final int W = 300, H = 430;
    static final Rectangle LOGICAL = new Rectangle(0, 0, 1280, 720);

    static void assertInside(Point p, Rectangle work) {
        assertThat(new Rectangle(p.x, p.y, W, H)).satisfies(r -> assertThat(work.contains(r)).as("面板 %s 应在 %s 内", r, work).isTrue());
    }

    @Test
    void physicalPixelsOnScaledScreenAreConverted() {
        // 托盘在右下角：物理坐标 (1800, 1060)，逻辑坐标应为 (1200, 707)
        TrayManager.Screen sc = new TrayManager.Screen(LOGICAL, LOGICAL, 1.5, null);
        Point logical = sc.toLogical(new Point(1800, 1060));
        assertThat(logical).isEqualTo(new Point(1200, 707));
        assertInside(TrayManager.position(logical, W, H, LOGICAL), LOGICAL);
    }

    @Test
    void mouseInfoPointerIsPreferred() {
        TrayManager.Screen sc = new TrayManager.Screen(LOGICAL, LOGICAL, 1.5, new Point(1190, 700));
        assertThat(sc.toLogical(new Point(1800, 1060))).isEqualTo(new Point(1190, 700));
    }

    @Test
    void panelNeverLeavesWorkAreaEvenWithBadCoordinates() {
        Rectangle work = new Rectangle(0, 0, 1280, 672); // 任务栏在下方，高 48
        for (Point bad : new Point[]{new Point(1800, 1060), new Point(-50, -50), new Point(1279, 0), new Point(5, 700)}) {
            assertInside(TrayManager.position(bad, W, H, work), work);
        }
    }

    @Test
    void panelTallerThanScreenStaysAtTop() {
        Rectangle small = new Rectangle(0, 0, 800, 300);
        Point p = TrayManager.position(new Point(790, 290), W, H, small);
        assertThat(p.y).isEqualTo(8);
        assertThat(p.x + W).isLessThanOrEqualTo(800 - 8);
    }

    @Test
    void normalScreenUnchanged() {
        Rectangle work = new Rectangle(0, 0, 1920, 1032);
        // 100% 缩放、鼠标在任务栏上方：面板在鼠标上方偏左，和原来一样
        assertThat(TrayManager.position(new Point(1800, 900), W, H, work)).isEqualTo(new Point(1524, 458));
        // 鼠标在任务栏上（可用区域外）：面板底边贴着可用区域，不压住任务栏
        assertThat(TrayManager.position(new Point(1800, 1050), W, H, work)).isEqualTo(new Point(1524, 1032 - H - 8));
    }
}
