package com.yizhaoqi.oaarchive.ocr;

import com.benjaminwan.ocrlibrary.OcrResult;
import com.benjaminwan.ocrlibrary.Point;
import com.benjaminwan.ocrlibrary.TextBlock;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.oaarchive.common.BizException;
import com.yizhaoqi.oaarchive.config.OaProperties;
import com.yizhaoqi.oaarchive.parser.Block;
import io.github.mymonstercat.Model;
import io.github.mymonstercat.ocr.InferenceEngine;
import io.github.mymonstercat.ocr.config.ParamConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;

/**
 * 离线 OCR（RapidOCR + ONNX Runtime）。v1.9.1 起内置三套模型（{@link OcrModels}），可以随时切换，不用重启：
 * 每套模型第一次使用时加载成一个独立的原生引擎（{@link IsolatedOcrEngine}），之后缓存复用。
 * 独立引擎加载失败时退回 RapidOCR-Java 自带的 PP-OCRv4 加载方式；原生库完全加载不了时降级为不可用，
 * 不影响其他功能（技术文档 6.3.7，技术难点 3.7）。
 */
@Slf4j
@Component
public class RapidOcrEngine implements OcrEngine {

    /** 一次识别的结果（自检、对比用） */
    public record Run(String modelId, String modelName, long millis, List<Block> blocks) {
    }

    private final OaProperties props;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, IsolatedOcrEngine> engines = new ConcurrentHashMap<>();
    /** 加载失败过的模型和原因，不再反复尝试；手动切换时重试 */
    private final Map<String, String> failed = new ConcurrentHashMap<>();
    /** 原生引擎是否线程安全不确定，所有识别串行执行（和 v1.9 以前一样） */
    private final Semaphore running = new Semaphore(1, true);
    /** 整个进程共用：同一个文件被两个类加载器加载会报 already loaded in another classloader */
    private static final AtomicInteger DLL_SEQ = new AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicBoolean CLEANED = new java.util.concurrent.atomic.AtomicBoolean();
    private volatile String current;
    private volatile String unavailableReason;
    /** 独立引擎方式不可用时退回的 RapidOCR-Java 原有方式（只有 PP-OCRv4） */
    private volatile InferenceEngine legacy;
    private volatile boolean legacyTried;
    private volatile String fallbackNote;

    public RapidOcrEngine(OaProperties props) {
        this.props = props;
        this.current = readSetting();
    }

    @Override
    public boolean available() {
        return props.getOcr().isEnabled() && (engine(current) != null || legacy() != null);
    }

    @Override
    public String unavailableReason() {
        if (!props.getOcr().isEnabled()) {
            return "OCR 已在配置中关闭";
        }
        available();
        return unavailableReason;
    }

    /** 当前使用的模型 id；独立引擎不可用、退回 PP-OCRv4 时返回 ppocrv4 */
    public String currentModel() {
        return engine(current) != null ? current : "ppocrv4";
    }

    /** 选择的模型和实际使用的不一致时的说明（例如加载失败退回 PP-OCRv4），没有问题时为 null */
    public String fallbackNote() {
        return fallbackNote;
    }

    @Override
    public List<Block> recognize(BufferedImage image, int page) {
        if (image == null || !props.getOcr().isEnabled()) {
            return List.of();
        }
        Run r = run(current, image, page);
        return r == null ? List.of() : r.blocks();
    }

    /** 用指定模型识别一张图片；模型加载失败时返回 null */
    public Run run(String modelId, BufferedImage image, int page) {
        OcrModels.Model m = OcrModels.find(modelId);
        IsolatedOcrEngine e = m == null ? null : engine(modelId);
        InferenceEngine fallback = e == null ? legacy() : null;
        if (e == null && fallback == null) {
            return null;
        }
        BufferedImage prepared = OcrPreprocessor.prepare(image);
        Path tmp = null;
        try {
            // RapidOCR 只接受文件路径，先写成临时 PNG
            tmp = Files.createTempFile("oa-ocr-", ".png");
            ImageIO.write(prepared, "png", tmp.toFile());
            running.acquireUninterruptibly();
            long t0 = System.currentTimeMillis();
            List<Block> blocks;
            try {
                if (e != null) {
                    blocks = toBlocks(e.detect(tmp), prepared.getWidth(), prepared.getHeight(), page);
                } else {
                    ParamConfig cfg = ParamConfig.getDefaultConfig();
                    cfg.setDoAngle(true);
                    cfg.setMostAngle(true);
                    OcrResult result = fallback.runOcr(tmp.toString(), cfg);
                    blocks = toLegacyBlocks(result.getTextBlocks(), prepared.getWidth(), prepared.getHeight(), page);
                }
            } finally {
                running.release();
            }
            String id = e != null ? modelId : "ppocrv4";
            return new Run(id, OcrModels.find(id).name(), System.currentTimeMillis() - t0, blocks);
        } catch (Exception ex) {
            log.warn("OCR 识别失败（{}）", modelId, ex);
            return null;
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // 临时文件删不掉不影响结果
                }
            }
        }
    }

    /** 切换模型并保存；会先加载新模型，加载失败时不切换并提示原因 */
    public synchronized void switchTo(String modelId) {
        OcrModels.Model m = OcrModels.find(modelId);
        if (m == null) {
            throw new BizException("没有这个 OCR 模型：" + modelId);
        }
        failed.remove(modelId);
        if (engine(modelId) == null) {
            throw new BizException("模型 " + m.name() + " 加载失败：" + failed.getOrDefault(modelId, "原因见日志"));
        }
        current = modelId;
        fallbackNote = null;
        try {
            Path f = settingFile();
            Files.createDirectories(f.getParent());
            mapper.writerWithDefaultPrettyPrinter().writeValue(f.toFile(), Map.of("model", modelId));
        } catch (IOException e) {
            throw new BizException("保存 OCR 设置失败：" + e.getMessage());
        }
        log.info("OCR 模型切换为 {}", m.name());
    }

    /** 已经加载到内存里的模型 */
    public List<String> loadedModels() {
        return List.copyOf(engines.keySet());
    }

    /** 取（必要时加载）某套模型的独立引擎；失败时返回 null 并记下原因 */
    private IsolatedOcrEngine engine(String modelId) {
        IsolatedOcrEngine e = engines.get(modelId);
        if (e != null) {
            return e;
        }
        OcrModels.Model m = OcrModels.find(modelId);
        if (m == null || failed.containsKey(modelId)) {
            return null;
        }
        synchronized (engines) {
            e = engines.get(modelId);
            if (e != null) {
                return e;
            }
            long t0 = System.currentTimeMillis();
            try {
                Path base = Path.of(System.getProperty("java.io.tmpdir"), "rgl-ocr");
                Path dir = base.resolve(m.id());
                IsolatedOcrEngine.extract(OcrModels.CLS_RESOURCE, dir.resolve("ch_ppocr_mobile_v2.0_cls_infer.onnx"));
                IsolatedOcrEngine.extract(m.resourceDir() + m.det(), dir.resolve(m.det()));
                IsolatedOcrEngine.extract(m.resourceDir() + m.rec(), dir.resolve(m.rec()));
                IsolatedOcrEngine.extract(m.resourceDir() + m.keys(), dir.resolve(m.keys()));
                Path dll = nativeCopy(base);
                int threads = Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors()));
                e = new IsolatedOcrEngine(dll, dir, m, threads);
                engines.put(modelId, e);
                log.info("OCR 模型加载成功：{}，用时 {} ms，目录 {}", m.name(), System.currentTimeMillis() - t0, dir);
                return e;
            } catch (Throwable t) {
                Throwable c = t.getCause() != null ? t.getCause() : t;
                unavailableReason = "OCR 模型 " + m.name() + " 加载失败：" + c;
                failed.put(modelId, String.valueOf(c));
                log.error(unavailableReason, t);
                if (modelId.equals(current)) {
                    fallbackNote = "选择的 " + m.name() + " 加载失败，暂时使用 PP-OCRv4";
                }
                return null;
            }
        }
    }

    /** 每个独立引擎用一份单独的原生库文件；进程结束后留下的旧副本在下次启动时清理 */
    private Path nativeCopy(Path base) throws IOException {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        String resource = windows ? "/lib/RapidOcr.dll" : "/lib/libRapidOcr.so";
        Path dir = Files.createDirectories(base.resolve("native"));
        if (CLEANED.compareAndSet(false, true)) {
            try (var s = Files.list(dir)) {
                s.forEach(p -> {
                    try {
                        Files.deleteIfExists(p); // 正在被其他进程使用的会删不掉，忽略
                    } catch (IOException ignored) {
                        // 忽略
                    }
                });
            }
        }
        Path target = dir.resolve("RapidOcr-" + ProcessHandle.current().pid() + "-" + DLL_SEQ.incrementAndGet()
                + (windows ? ".dll" : ".so"));
        IsolatedOcrEngine.extract(resource, target);
        return target;
    }

    /** RapidOCR-Java 原有的加载方式（只能加载一次、只有 PP-OCRv4），独立引擎方式不可用时使用 */
    private InferenceEngine legacy() {
        if (legacyTried) {
            return legacy;
        }
        synchronized (this) {
            if (!legacyTried) {
                try {
                    legacy = InferenceEngine.getInstance(Model.ONNX_PPOCR_V4);
                    log.info("OCR 使用 RapidOCR-Java 默认方式加载 PP-OCRv4");
                } catch (Throwable t) {
                    // UnsatisfiedLinkError 等错误也要接住，保证程序不崩溃
                    unavailableReason = "OCR 原生库加载失败：" + t;
                    log.error(unavailableReason, t);
                } finally {
                    legacyTried = true;
                }
            }
        }
        return legacy;
    }

    private Path settingFile() {
        return props.homePath().resolve("config").resolve("ocr.json");
    }

    /** 读取保存的模型；没有设置、或者模型已经不存在时用配置里的默认值 */
    private String readSetting() {
        try {
            Path f = settingFile();
            if (Files.exists(f)) {
                String id = mapper.readTree(f.toFile()).path("model").asText("");
                if (OcrModels.find(id) != null) {
                    return id;
                }
            }
        } catch (IOException | RuntimeException e) {
            log.warn("OCR 设置读取失败，使用默认模型", e);
        }
        String def = props.getOcr().getModel();
        return OcrModels.find(def) != null ? def : OcrModels.DEFAULT;
    }

    /** 自检、对比时展示的状态 */
    public Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("current", currentModel());
        m.put("selected", current);
        m.put("fallbackNote", fallbackNote);
        m.put("loaded", loadedModels());
        m.put("failed", Map.copyOf(failed));
        return m;
    }

    private static List<Block> toBlocks(List<IsolatedOcrEngine.RawBlock> raw, int width, int height, int page) {
        List<Block> blocks = new ArrayList<>();
        for (IsolatedOcrEngine.RawBlock tb : raw) {
            String text = tb.text() == null ? "" : tb.text().trim();
            if (text.isEmpty() || tb.xs().length == 0) {
                continue;
            }
            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = 0, maxY = 0;
            for (int i = 0; i < tb.xs().length; i++) {
                minX = Math.min(minX, tb.xs()[i]);
                minY = Math.min(minY, tb.ys()[i]);
                maxX = Math.max(maxX, tb.xs()[i]);
                maxY = Math.max(maxY, tb.ys()[i]);
            }
            blocks.add(block(text, page, minX, minY, maxX, maxY, width, height, tb.score()));
        }
        return dropNoise(OcrLineMerger.merge(blocks));
    }

    private static List<Block> toLegacyBlocks(List<TextBlock> raw, int width, int height, int page) {
        List<Block> blocks = new ArrayList<>();
        if (raw == null) {
            return blocks;
        }
        for (TextBlock tb : raw) {
            String text = tb.getText() == null ? "" : tb.getText().trim();
            if (text.isEmpty() || tb.getBoxPoint() == null || tb.getBoxPoint().isEmpty()) {
                continue;
            }
            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = 0, maxY = 0;
            for (Point p : tb.getBoxPoint()) {
                minX = Math.min(minX, p.getX());
                minY = Math.min(minY, p.getY());
                maxX = Math.max(maxX, p.getX());
                maxY = Math.max(maxY, p.getY());
            }
            blocks.add(block(text, page, minX, minY, maxX, maxY, width, height, averageScore(tb)));
        }
        return dropNoise(OcrLineMerger.merge(blocks));
    }

    /** 一行里至少有一个汉字，或者有两个连在一起的字母、数字，才算读到了文字 */
    private static final java.util.regex.Pattern MEANINGFUL = java.util.regex.Pattern.compile("\\p{IsHan}|[\\p{L}\\p{N}]{2,}");

    /**
     * 合并成行以后去掉没有意义的行（v1.9.1）：PP-OCRv6 对没有文字的模糊照片会认出“a O”这类零散字母
     * （来自图里的圆形、线条），原来 PP-OCRv4 什么都认不出。保留下来会让程序以为读到了文字，不再提示“没有读到文字内容”。
     */
    static List<Block> dropNoise(List<Block> lines) {
        return lines.stream().filter(b -> MEANINGFUL.matcher(b.text()).find()).toList();
    }

    private static Block block(String text, int page, int minX, int minY, int maxX, int maxY, int width, int height, float conf) {
        return new Block(text, page, maxY - minY, null, false,
                (float) minX / width, (float) minY / height,
                (float) (maxX - minX) / width, (float) (maxY - minY) / height,
                conf, Block.Source.OCR);
    }

    private static float averageScore(TextBlock tb) {
        float[] scores = tb.getCharScores();
        if (scores == null || scores.length == 0) {
            return tb.getBoxScore();
        }
        float sum = 0;
        for (float s : scores) {
            sum += s;
        }
        return sum / scores.length;
    }
}
