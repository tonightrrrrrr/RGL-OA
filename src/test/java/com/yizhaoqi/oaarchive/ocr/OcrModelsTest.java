package com.yizhaoqi.oaarchive.ocr;

import com.yizhaoqi.oaarchive.common.BizException;
import com.yizhaoqi.oaarchive.config.OaProperties;
import com.yizhaoqi.oaarchive.parser.Block;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * v1.9.1 三套 OCR 模型（技术难点 3.7）：每套都能加载、识别中文；来回切换后结果不变（原生库的字典不会叠加）；
 * 选择保存在 config/ocr.json，重启后还在；没有文字的图片不会认出零散字母。
 */
@EnabledOnOs(OS.WINDOWS)
class OcrModelsTest {

    @TempDir
    Path home;

    static BufferedImage notice() {
        BufferedImage img = new BufferedImage(1200, 360, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 1200, 360);
        g.setColor(Color.BLACK);
        g.setFont(new Font("Microsoft YaHei", Font.BOLD, 44));
        g.drawString("关于开展安全生产检查的通知", 150, 110);
        g.setFont(new Font("Microsoft YaHei", Font.PLAIN, 30));
        g.drawString("编制部门：安环部　日期：2026年05月18日", 110, 230);
        g.dispose();
        return img;
    }

    RapidOcrEngine engine() {
        OaProperties p = new OaProperties();
        p.setHome(home.toString());
        return new RapidOcrEngine(p);
    }

    @Test
    void everyModelRecognizesChineseAndSwitchingDoesNotCorruptResults() {
        RapidOcrEngine e = engine();
        BufferedImage img = notice();
        String first = null;
        for (String id : List.of("ppocrv4", "ppocrv6-small", "ppocrv6-tiny", "ppocrv4")) {
            RapidOcrEngine.Run r = e.run(id, img, 1);
            assertThat(r).as(id + " 加载失败：" + e.unavailableReason()).isNotNull();
            assertThat(r.modelId()).as("没有退回 PP-OCRv4").isEqualTo(id);
            String text = r.blocks().stream().map(Block::text).collect(Collectors.joining("\n"));
            System.out.printf("[OCR] %s %d ms：%s%n", id, r.millis(), text.replace('\n', ' '));
            assertThat(text).contains("安全生产").contains("安环部").contains("2026");
            if (id.equals("ppocrv4")) {
                if (first == null) {
                    first = text;
                } else {
                    assertThat(text).as("加载过其他模型后，PP-OCRv4 的结果和第一次一样").isEqualTo(first);
                }
            }
        }
        assertThat(e.loadedModels()).containsExactlyInAnyOrder("ppocrv4", "ppocrv6-small", "ppocrv6-tiny");
    }

    @Test
    void selectionIsSavedAndUsedAfterRestart() throws Exception {
        RapidOcrEngine e = engine();
        assertThat(e.currentModel()).as("默认 PP-OCRv6 small").isEqualTo("ppocrv6-small");
        e.switchTo("ppocrv6-tiny");
        assertThat(Files.readString(home.resolve("config/ocr.json"))).contains("ppocrv6-tiny");
        assertThat(engine().currentModel()).as("重启后还是 tiny").isEqualTo("ppocrv6-tiny");
        assertThatThrownBy(() -> e.switchTo("nope")).isInstanceOf(BizException.class).hasMessageContaining("没有这个 OCR 模型");
    }

    @Test
    void scatteredLettersAreNotText() {
        Block a = new Block("a O", 1, 10, null, false, 0, 0, 0.1f, 0.1f, 0.8f, Block.Source.OCR);
        Block b = new Block("2026", 1, 10, null, false, 0, 0, 0.1f, 0.1f, 0.9f, Block.Source.OCR);
        Block c = new Block("章", 1, 10, null, false, 0, 0, 0.1f, 0.1f, 0.9f, Block.Source.OCR);
        assertThat(RapidOcrEngine.dropNoise(List.of(a, b, c))).containsExactly(b, c);
    }
}
