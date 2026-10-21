package com.yizhaoqi.oaarchive.launcher;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.assertj.core.api.Assertions.assertThat;

/** 程序图标：每个用到的尺寸都能从资源里取到，并且不是空白图 */
class BrandIconTest {

    @Test
    void loadsEverySizeFromResources() {
        // 16–40：托盘、托盘面板（34）、exe 小图标；48 及以上：exe 大图标；300 测试超出最大尺寸时放大
        for (int size : new int[] {16, 20, 24, 32, 34, 40, 48, 64, 128, 256, 300}) {
            BufferedImage img = BrandIcon.create(size);
            assertThat(img.getWidth()).as("宽度 %d", size).isEqualTo(size);
            assertThat(img.getHeight()).as("高度 %d", size).isEqualTo(size);
            assertThat(img.getRGB(size / 2, size / 2) >>> 24).as("中心不透明 %d", size).isEqualTo(255);
            assertThat(img.getRGB(0, 0) >>> 24).as("圆角处透明 %d", size).isLessThan(128);
        }
    }

    @Test
    void returnsIndependentCopies() {
        BufferedImage a = BrandIcon.create(32);
        a.setRGB(16, 16, 0);
        assertThat(BrandIcon.create(32).getRGB(16, 16)).isNotEqualTo(0);
    }
}
