package com.yizhaoqi.oaarchive.ocr;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * 一套模型一个独立的原生 OCR 引擎（v1.9.1，技术难点 3.7）。
 * <p>
 * RapidOcr.dll 的 initModels 不会清空上一次的字典：同一个进程里第二次加载模型后字典变成两份（6625 → 13250 个字），
 * 字的编号全部错位，识别出来是乱码。所以不能在同一个引擎上换模型。
 * 这里给每套模型复制一份 dll（文件名不同，Windows 当作不同的模块，全局状态互不影响），
 * 再用一个只定义 OcrEngine 相关类的类加载器加载，原生方法就绑定到这份 dll 上。
 */
final class IsolatedOcrEngine {

    /** 一个文字框：文字、四个角点、平均字符置信度 */
    record RawBlock(String text, int[] xs, int[] ys, float score) {
    }

    private static final String NATIVE_STUB = "com.yizhaoqi.oaarchive.ocr.isolated.NativeLoad";

    private final Object engine;
    private final Method detect;
    private Method textBlocks, text, boxPoint, boxScore, charScores, pointX, pointY;

    IsolatedOcrEngine(Path nativeLib, Path modelDir, OcrModels.Model m, int threads) throws Exception {
        ClassLoader cl = new Child(IsolatedOcrEngine.class.getClassLoader());
        cl.loadClass(NATIVE_STUB).getMethod("load", String.class).invoke(null, nativeLib.toString());
        Class<?> engineClass = cl.loadClass("com.benjaminwan.ocrlibrary.OcrEngine");
        engine = engineClass.getConstructor().newInstance();
        engineClass.getMethod("initLogger", boolean.class, boolean.class, boolean.class).invoke(engine, false, false, false);
        engineClass.getMethod("setNumThread", int.class).invoke(engine, threads);
        boolean ok = (boolean) engineClass.getMethod("initModels", String.class, String.class, String.class, String.class, String.class)
                .invoke(engine, modelDir.toString(), m.det(), "ch_ppocr_mobile_v2.0_cls_infer.onnx", m.rec(), m.keys());
        if (!ok) {
            throw new IllegalStateException("模型文件加载失败：" + m.name());
        }
        detect = engineClass.getMethod("detect", String.class, int.class, int.class, float.class, float.class, float.class,
                boolean.class, boolean.class);
    }

    /** 参数和 RapidOCR-Java 的默认配置一致：padding 50、不缩放、框阈值 0.5/0.3、扩张 1.6、做方向判断 */
    synchronized List<RawBlock> detect(Path image) throws Exception {
        Object result = detect.invoke(engine, image.toString(), 50, 0, 0.5f, 0.3f, 1.6f, true, true);
        if (textBlocks == null) {
            bind(result);
        }
        List<RawBlock> out = new ArrayList<>();
        for (Object b : (List<?>) textBlocks.invoke(result)) {
            List<?> pts = (List<?>) boxPoint.invoke(b);
            int[] xs = new int[pts == null ? 0 : pts.size()], ys = new int[xs.length];
            for (int i = 0; i < xs.length; i++) {
                xs[i] = (int) pointX.invoke(pts.get(i));
                ys[i] = (int) pointY.invoke(pts.get(i));
            }
            float[] cs = (float[]) charScores.invoke(b);
            float score = (float) boxScore.invoke(b);
            if (cs != null && cs.length > 0) {
                float sum = 0;
                for (float c : cs) {
                    sum += c;
                }
                score = sum / cs.length;
            }
            out.add(new RawBlock((String) text.invoke(b), xs, ys, score));
        }
        return out;
    }

    /** 结果对象是子类加载器里的类，用反射读取；方法只查一次 */
    private void bind(Object result) throws Exception {
        textBlocks = result.getClass().getMethod("getTextBlocks");
        ClassLoader cl = result.getClass().getClassLoader();
        Class<?> tb = cl.loadClass("com.benjaminwan.ocrlibrary.TextBlock");
        text = tb.getMethod("getText");
        boxPoint = tb.getMethod("getBoxPoint");
        boxScore = tb.getMethod("getBoxScore");
        charScores = tb.getMethod("getCharScores");
        Class<?> pt = cl.loadClass("com.benjaminwan.ocrlibrary.Point");
        pointX = pt.getMethod("getX");
        pointY = pt.getMethod("getY");
    }

    /** 只有 com.benjaminwan.ocrlibrary 下的类和 NativeLoad 由自己定义，其余交给父加载器 */
    static final class Child extends ClassLoader {

        Child(ClassLoader parent) {
            super(parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.startsWith("com.benjaminwan.ocrlibrary.") && !name.equals(NATIVE_STUB)) {
                return super.loadClass(name, resolve);
            }
            synchronized (getClassLoadingLock(name)) {
                Class<?> c = findLoadedClass(name);
                if (c == null) {
                    try (InputStream in = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                        if (in == null) {
                            throw new ClassNotFoundException(name);
                        }
                        byte[] b = in.readAllBytes();
                        c = defineClass(name, b, 0, b.length);
                    } catch (IOException e) {
                        throw new ClassNotFoundException(name, e);
                    }
                }
                if (resolve) {
                    resolveClass(c);
                }
                return c;
            }
        }
    }

    /** 从 classpath 复制资源到文件；已存在且大小相同时跳过 */
    static void extract(String resource, Path target) throws IOException {
        try (InputStream in = IsolatedOcrEngine.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("找不到资源 " + resource);
            }
            byte[] b = in.readAllBytes();
            if (Files.exists(target) && Files.size(target) == b.length) {
                return;
            }
            Files.createDirectories(target.getParent());
            Path tmp = target.resolveSibling(target.getFileName() + ".part");
            Files.write(tmp, b);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
