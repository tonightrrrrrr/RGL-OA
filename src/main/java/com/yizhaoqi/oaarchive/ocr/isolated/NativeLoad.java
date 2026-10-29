package com.yizhaoqi.oaarchive.ocr.isolated;

/**
 * 由 {@link com.yizhaoqi.oaarchive.ocr.IsolatedOcrEngine} 的子类加载器重新定义。
 * System.load 会把 dll 绑定到调用者所在的类加载器，所以必须在子类加载器里的类中调用。
 */
public final class NativeLoad {

    private NativeLoad() {
    }

    public static void load(String path) {
        System.load(path);
    }
}
