package com.yizhaoqi.oaarchive.recognize;

import com.yizhaoqi.oaarchive.parser.DocModel;
import com.yizhaoqi.oaarchive.template.TemplateConfig;

/**
 * 各识别器共用的输入。
 *
 * @param doc      统一文档模型
 * @param regions  区域切分结果
 * @param anchors  锚点字段
 * @param template 当前批次使用的模板
 * @param fileName 文件名解析结果
 */
record RecognitionContext(DocModel doc, Regions regions, Anchors anchors, TemplateConfig template, FileNameInfo fileName) {

    String fullText() {
        return doc.fullText();
    }

    String bodyText() {
        return Regions.join(regions.header()) + "\n" + Regions.join(regions.body());
    }
}
