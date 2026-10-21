package com.yizhaoqi.oaarchive.parser;

import java.nio.file.Path;

/**
 * 各格式解析器的统一接口（技术文档 6.2.2）。
 */
public interface DocumentParser {

    /** 按小写扩展名判断是否由本解析器处理 */
    boolean supports(String ext);

    /**
     * @param file         工作区里的文件副本
     * @param originalName 原文件名（用于 DocModel.fileName）
     */
    DocModel parse(Path file, String originalName) throws Exception;
}
