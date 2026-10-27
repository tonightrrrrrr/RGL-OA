package com.yizhaoqi.oaarchive.recognize;

import java.util.List;

/**
 * 简历的应聘岗位没有匹配的岗位规则时，帮忙推测属于哪个部门（v1.8）。
 * 只传岗位名称和部门名称，不传简历内容。识别引擎只依赖这个接口，自己仍然离线、不调用模型；
 * 导入时由 AI 助手实现（见 ai.AiPositionAdvisor），模板预览和示例测试不传，结果可以复现。
 */
@FunctionalInterface
public interface PositionAdvisor {

    record Suggestion(String department, String reason) {
    }

    /** 推测不出来、AI 没配置或关掉了这个功能时返回 null */
    Suggestion suggest(String position, List<String> departments);
}
