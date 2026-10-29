package com.yizhaoqi.oaarchive.controller;

import com.yizhaoqi.oaarchive.common.ApiResponse;
import com.yizhaoqi.oaarchive.common.BizException;
import com.yizhaoqi.oaarchive.ocr.OcrModels;
import com.yizhaoqi.oaarchive.ocr.RapidOcrEngine;
import com.yizhaoqi.oaarchive.parser.Block;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * OCR 模型（v1.9.1，技术难点 3.7）：列出内置模型及各自的优缺点和实测数据、切换模型、同一张图片用三套模型对比识别。
 */
@RestController
@RequestMapping("/api/ocr")
public class OcrController {

    private final RapidOcrEngine ocr;

    public OcrController(RapidOcrEngine ocr) {
        this.ocr = ocr;
    }

    public record SwitchRequest(String model) {
    }

    @GetMapping("/models")
    public ApiResponse<Map<String, Object>> models() {
        Map<String, Object> m = new LinkedHashMap<>(ocr.describe());
        m.put("recommended", OcrModels.DEFAULT);
        m.put("models", OcrModels.ALL);
        return ApiResponse.ok(m);
    }

    /** 切换模型：先加载新模型，成功后才保存，立即生效 */
    @PutMapping("/model")
    public ApiResponse<Map<String, Object>> switchModel(@RequestBody SwitchRequest req) {
        ocr.switchTo(req == null ? null : req.model());
        return models();
    }

    /** 同一张图片依次用三套模型识别，返回各自的耗时、行数、平均置信度和全文，不改变当前选择 */
    @PostMapping("/compare")
    public ApiResponse<Map<String, Object>> compare(@RequestParam("file") MultipartFile file) throws IOException {
        BufferedImage img = ImageIO.read(file.getInputStream());
        if (img == null) {
            throw new BizException("无法读取图片，请上传 jpg、png 或 bmp 格式");
        }
        List<Map<String, Object>> results = new ArrayList<>();
        for (OcrModels.Model model : OcrModels.ALL) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("model", model.id());
            r.put("name", model.name());
            RapidOcrEngine.Run run = ocr.run(model.id(), img, 1);
            if (run == null || !run.modelId().equals(model.id())) {
                r.put("error", "这套模型加载失败，详见日志");
            } else {
                double conf = run.blocks().stream().mapToDouble(Block::confidence).average().orElse(0);
                r.put("millis", run.millis());
                r.put("lineCount", run.blocks().size());
                r.put("charCount", run.blocks().stream().mapToInt(b -> b.text().length()).sum());
                r.put("confidence", Math.round(conf * 1000) / 1000.0);
                r.put("lines", run.blocks().stream().map(b -> Map.of("text", b.text(), "confidence", b.confidence())).toList());
            }
            results.add(r);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("width", img.getWidth());
        m.put("height", img.getHeight());
        m.put("current", ocr.currentModel());
        m.put("results", results);
        return ApiResponse.ok(m);
    }
}
