package com.yizhaoqi.oaarchive.recognize;

import java.util.List;
import java.util.Map;

/**
 * 一份文件的识别结果。
 *
 * @param department    部门
 * @param docType       文件类型
 * @param date          文件日期（值为 yyyy-MM-dd，无法识别时为 null）
 * @param title         标题
 * @param extras        扩展字段：合同编号、甲乙方、金额等
 * @param keywords      关键词
 * @param overall       综合置信度：部门、类型、日期三项的最小值
 * @param needsReview   是否需要人工确认（按模板阈值判断）
 * @param blocked       与阈值无关、一定要人工确认：字段识别不出、两个候选太接近、没读到文字。
 *                      任意阈值 t 下是否待确认 = blocked || overall &lt; t，阈值分析用它
 * @param reviewReasons 需要确认的原因
 * @param regionLines   区域切分结果（处理记录里展示）
 */
public record RecognitionResult(FieldResult department, FieldResult docType, FieldResult date, FieldResult title,
                                Map<String, String> extras, List<String> keywords, double overall,
                                boolean needsReview, boolean blocked, List<String> reviewReasons, List<String> regionLines) {
}
