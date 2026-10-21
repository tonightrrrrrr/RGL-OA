package com.yizhaoqi.oaarchive.ocr;

import com.yizhaoqi.oaarchive.parser.Block;

import java.awt.image.BufferedImage;
import java.util.List;

/**
 * OCR 引擎接口（技术文档 6.3.5）。业务代码只依赖这个接口，以后可以换成 ONNX Runtime 或视觉大模型实现。
 */
public interface OcrEngine {

    /** 引擎是否可用；原生库加载失败时返回 false */
    boolean available();

    /** 不可用时的原因，供界面展示 */
    String unavailableReason();

    /**
     * 识别一张图片，返回按阅读顺序排列、已按行合并的文本块（来源为 OCR）。
     *
     * @param page 图片对应的页码，图片文件传 1
     */
    List<Block> recognize(BufferedImage image, int page);
}
