package com.yizhaoqi.oaarchive.recognize;

import com.yizhaoqi.oaarchive.parser.Block;
import com.yizhaoqi.oaarchive.template.TemplateConfig;
import com.yizhaoqi.oaarchive.template.TemplateConfig.DeptDef;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 部门识别（技术文档 6.5.2）。部门经常不在正文里，或者正文里出现很多干扰部门，
 * 所以以文件名和锚点字段为主，关键词推断为辅，正文出现次数权重最低。
 */
final class DepartmentRecognizer {

    /** 强锚点：字段值直接写明部门 */
    private static final String[] STRONG_LABELS = {"编制部门", "编制单位", "发文部门", "发文单位", "主办部门", "承办部门",
            "起草部门", "起草单位", "落款", "制表人", "制表部门", "报送部门", "部门", "所属部门", "填报部门", "申请部门"};
    /** 弱锚点：人员字段里带部门，如“记录人：行政部黄晓”“主持人：研发部负责人赵文轩” */
    private static final String[] WEAK_LABELS = {"主持人", "记录人", "审核人", "经办人", "起草人", "编制人", "联系人"};
    private static final String[] ROLE_SUFFIXES = {"负责人", "经理", "总监", "主任", "主管"};
    private static final Pattern FILING = Pattern.compile("(?:报送至|报送|提交至|送至|交至|报)([^，。；,]{2,10}?)(?:备案|审核|汇总|审批)");

    private DepartmentRecognizer() {
    }

    static FieldResult recognize(RecognitionContext ctx) {
        if (ctx.resume().resume()) {
            return resume(ctx);
        }
        TemplateConfig tpl = ctx.template();
        List<DeptDef> depts = tpl.getDepartments();
        ScoreBoard sb = new ScoreBoard();

        // 1. 文件名
        for (String seg : ctx.fileName().segments()) {
            DeptDef d = exact(depts, seg);
            if (d != null) {
                sb.add(d.getName(), "FILENAME", ctx.fileName().original(), 0.85);
            }
        }
        DeptDef inName = find(depts, ctx.fileName().base(), true);
        if (inName != null) {
            sb.add(inName.getName(), "FILENAME_MENTION", ctx.fileName().original(), 0.60);
        }

        // 2. 锚点字段
        for (Anchors.Anchor a : ctx.anchors().withLabel(STRONG_LABELS)) {
            DeptDef d = find(depts, a.value(), true);
            if (d != null) {
                sb.add(d.getName(), "ANCHOR", a.raw(), 0.85);
            }
        }
        for (Anchors.Anchor a : ctx.anchors().labelEndsWith(ROLE_SUFFIXES)) {
            // “生产总监：吴斌”“人力资源部经理：郑晓”——部门写在字段名里
            DeptDef d = find(depts, a.label(), false);
            if (d == null) {
                d = byStem(depts, a.label());
            }
            if (d != null) {
                sb.add(d.getName(), "ANCHOR", a.raw(), a.zone() == Anchors.Zone.FOOTER ? 0.80 : 0.70);
            }
        }
        for (Anchors.Anchor a : ctx.anchors().withLabel(WEAK_LABELS)) {
            DeptDef d = find(depts, a.value(), false);
            if (d != null) {
                sb.add(d.getName(), "ANCHOR_PERSON", a.raw(), 0.75);
            }
        }

        // 3. 备案、报送对象：“值班表……报送至行政部备案”
        Matcher m = FILING.matcher(ctx.fullText());
        while (m.find()) {
            DeptDef d = find(depts, m.group(1), false);
            if (d != null) {
                sb.add(d.getName(), "FILING", m.group(), 0.80);
            }
        }

        // 4. 标题里直接出现部门名
        String title = ctx.regions().titleText();
        DeptDef inTitle = find(depts, title, false);
        if (inTitle != null) {
            sb.add(inTitle.getName(), "TITLE_MENTION", title, 0.60);
        }

        // 5. 关键词推断规则
        String titleOrName = title.isEmpty() ? String.join("", ctx.fileName().segments()) : title;
        String body = ctx.bodyText();
        for (TemplateConfig.KeywordRule rule : tpl.getKeywordRules()) {
            String hitTitle = null;
            Set<String> hitBody = new HashSet<>();
            for (String kw : rule.getKeywords()) {
                if (titleOrName.contains(kw) && hitTitle == null) {
                    hitTitle = kw;
                }
                if (body.contains(kw)) {
                    hitBody.add(kw);
                }
            }
            if (hitTitle != null) {
                sb.add(rule.getDepartment(), "KEYWORD_TITLE", "标题含“" + hitTitle + "”", 0.70);
            }
            if (!hitBody.isEmpty()) {
                double w = hitBody.size() >= 3 ? 0.65 : hitBody.size() == 2 ? 0.55 : 0.40;
                sb.add(rule.getDepartment(), "KEYWORD_BODY", "正文含“" + String.join("、", hitBody) + "”", w);
            }
        }

        // 6. 正文出现次数最多的部门（容易受干扰，权重最低；并列第一时不加分）
        bodyFrequency(depts, ctx.doc().blocks(), sb);

        return sb.result(TemplateConfig.UNKNOWN_DEPT);
    }

    /**
     * 简历的部门（v1.8）：正文里的部门名、“招聘”“绩效”这些词多半是以前的工作经历，不用。
     * 只看文件名里的部门、明确写的“应聘部门”和应聘岗位。岗位推测的分数低于默认阈值，配合引擎里的强制确认。
     * 几个部门的岗位规则命中同样长的词时分数相同，引擎会判为“两个接近的候选”。
     */
    private static FieldResult resume(RecognitionContext ctx) {
        TemplateConfig tpl = ctx.template();
        List<DeptDef> depts = tpl.getDepartments();
        ScoreBoard sb = new ScoreBoard();
        for (String seg : ctx.fileName().segments()) {
            DeptDef d = exact(depts, seg);
            if (d != null) {
                sb.add(d.getName(), "FILENAME", ctx.fileName().original(), 0.85);
            }
        }
        DeptDef applied = find(depts, ctx.resume().applyDept(), true);
        if (applied != null) {
            sb.add(applied.getName(), "APPLY_DEPT", "应聘部门：" + ctx.resume().applyDept(), 0.85);
        }
        List<ResumeDetector.Match> hits = ResumeDetector.match(ctx.resume().position(), tpl);
        for (int i = 0; i < hits.size(); i++) {
            ResumeDetector.Match m = hits.get(i);
            // 只有同一个词写在两个部门下（位置也相同）才算分不出来；“销售会计”里“会计”在后，取财务部，销售部列为“也可能是”
            boolean tie = i > 0 && m.keyword().length() == hits.get(0).keyword().length() && m.at() == hits.get(0).at();
            sb.add(m.department(), "POSITION", "应聘岗位“" + ctx.resume().position() + "”含“" + m.keyword() + "”",
                    i == 0 || tie ? 0.70 : 0.45);
        }
        return sb.result(TemplateConfig.UNKNOWN_DEPT);
    }

    private static void bodyFrequency(List<DeptDef> depts, List<Block> blocks, ScoreBoard sb) {
        Map<String, Integer> count = new HashMap<>();
        for (Block b : blocks) {
            for (DeptDef d : depts) {
                int idx = 0;
                while ((idx = b.text().indexOf(d.getName(), idx)) >= 0) {
                    count.merge(d.getName(), 1, Integer::sum);
                    idx += d.getName().length();
                }
            }
        }
        var sorted = count.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).toList();
        if (sorted.isEmpty() || sorted.get(0).getValue() < 2) {
            return;
        }
        if (sorted.size() > 1 && sorted.get(1).getValue().equals(sorted.get(0).getValue())) {
            return;
        }
        sb.add(sorted.get(0).getKey(), "BODY_FREQ", "正文出现 " + sorted.get(0).getValue() + " 次", 0.20);
    }

    /** 文件名段完全等于部门名或别名 */
    private static DeptDef exact(List<DeptDef> depts, String seg) {
        for (DeptDef d : depts) {
            if (d.getName().equals(seg) || d.getAliases().contains(seg)) {
                return d;
            }
        }
        return null;
    }

    /** 文本中出现的部门全称（取最长匹配）；allowAlias 为 true 时也匹配别名 */
    static DeptDef find(List<DeptDef> depts, String text, boolean allowAlias) {
        if (text == null || text.isBlank()) {
            return null;
        }
        return depts.stream()
                .filter(d -> text.contains(d.getName()) || allowAlias && d.getAliases().stream().anyMatch(text::contains))
                .max(Comparator.comparingInt(d -> d.getName().length()))
                .orElse(null);
    }

    /** “生产总监”→ 生产部：部门名去掉“部/办/中心”后的词干出现在字段名开头 */
    private static DeptDef byStem(List<DeptDef> depts, String label) {
        for (DeptDef d : depts) {
            String stem = d.getName().replaceAll("(部|办|中心|办公室)$", "");
            if (stem.length() >= 2 && label.startsWith(stem)) {
                return d;
            }
        }
        return null;
    }
}
