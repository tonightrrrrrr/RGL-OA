package com.yizhaoqi.oaarchive.ocr;

import java.util.List;

/**
 * 内置的 OCR 模型（v1.9.1，技术难点 3.7）。三套模型可以在“系统设置 → OCR 模型”里随时切换，不用重启。
 * 实测数字来自 v1.9.1 的对比测试：4 份示例 PDF 渲染的页面，以及用手写体、书法体排版后加旋转、阴影、噪点、模糊的“困难页”，
 * 本机 8 线程 CPU。准确率是和 PDF 文字层对比的字符 F1。
 */
public final class OcrModels {

    /**
     * @param resourceDir 模型文件在 classpath 里的目录（以 / 结尾）
     * @param hardF1      困难页（手写体、书法体、模糊拍照）的字符 F1
     * @param hardMs      困难页平均耗时（毫秒/页）
     * @param pageMs      清晰 A4 页（300dpi）平均耗时（毫秒/页）
     */
    public record Model(String id, String name, String tag, String summary, List<String> pros, List<String> cons,
                        String resourceDir, String det, String rec, String keys, double sizeMb,
                        double hardF1, int hardMs, int pageMs) {
    }

    /** 方向分类模型三套共用，来自依赖 rapidocr-onnx-models */
    static final String CLS_RESOURCE = "/models/ch_ppocr_mobile_v2.0_cls_infer.onnx";

    public static final String DEFAULT = "ppocrv6-small";

    public static final List<Model> ALL = List.of(
            new Model("ppocrv6-small", "PP-OCRv6 small", "推荐 · 最准",
                    "2026 年的新一代模型，识别准确率最高，扫描件、手机拍照、手写体都更稳。",
                    List.of("困难页准确率 69.2%，比 PP-OCRv4 高 11 个百分点", "字典 18708 个字，生僻字、繁体、日文都能认",
                            "手写体、书法体、模糊照片明显更准"),
                    List.of("清晰 A4 页比 PP-OCRv4 慢约 20%", "模型 30 MB，三套里最大"),
                    "/ocr-models/ppocrv6-small/", "PP-OCRv6_det_small.onnx", "PP-OCRv6_rec_small.onnx", "ppocrv6_dict.txt",
                    29.7, 0.692, 1174, 5829),
            new Model("ppocrv6-tiny", "PP-OCRv6 tiny", "极速",
                    "体积最小、速度最快，适合大批量扫描件或配置较低的电脑。",
                    List.of("清晰 A4 页约 1.9 秒，是 PP-OCRv4 的 2.4 倍快", "模型只有 6 MB，内存占用最小",
                            "印刷体准确率和 PP-OCRv4 相当（困难页 58.9%）"),
                    List.of("字典只有 6904 个常用字，生僻字认不出", "手写体、书法体不如 small"),
                    "/ocr-models/ppocrv6-tiny/", "PP-OCRv6_det_tiny.onnx", "PP-OCRv6_rec_tiny.onnx", "ppocrv6_tiny_dict.txt",
                    6.0, 0.589, 459, 1930),
            new Model("ppocrv4", "PP-OCRv4 mobile", "经典",
                    "v1.0 到 v1.9 一直使用的模型，结果最稳定，和以前导入的批次识别结果一致。",
                    List.of("v1.9 及以前的版本都用它，经过 10 份示例和反面案例的全部测试", "需要和旧批次保持一致时选它"),
                    List.of("困难页准确率最低（58.2%）", "字典 6623 个字"),
                    "/models/", "ch_PP-OCRv4_det_infer.onnx", "ch_PP-OCRv4_rec_infer.onnx", "ppocr_keys_v1.txt",
                    14.9, 0.582, 1698, 4723));

    private OcrModels() {
    }

    public static Model find(String id) {
        return ALL.stream().filter(m -> m.id().equals(id)).findFirst().orElse(null);
    }
}
