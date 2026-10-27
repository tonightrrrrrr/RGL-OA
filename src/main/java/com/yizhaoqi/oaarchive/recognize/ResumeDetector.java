package com.yizhaoqi.oaarchive.recognize;

import com.yizhaoqi.oaarchive.parser.Block;
import com.yizhaoqi.oaarchive.parser.DocModel;
import com.yizhaoqi.oaarchive.template.TemplateConfig;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 简历识别（v1.8）：判断是不是一份简历，取出应聘岗位、姓名、应聘部门，并按模板的岗位规则推测部门。
 * 简历里一般不写部门；正文里的“招聘”“绩效”“研发部”多半是以前的工作经历，按普通文件的办法识别部门不可靠。
 * 所以简历的部门只看三样：文件名里的部门、明确写的“应聘部门”、应聘岗位。
 */
final class ResumeDetector {

    /** 识别结果。position 为空表示没找到应聘岗位；positionSource 是找到岗位的那一行（显示给用户） */
    record Info(boolean resume, String position, String positionSource, String name, String applyDept) {
        static final Info NONE = new Info(false, null, null, null, null);
    }

    /** 岗位命中某个部门的规则：keyword 是命中的词，at 是它在岗位里的位置 */
    record Match(String department, String keyword, int at) {
    }

    /** 写应聘岗位的字段名；不含单独的“岗位”“职位”，那多半是以前的工作经历 */
    private static final String[] POSITION_LABELS = {"应聘岗位", "应聘职位", "求职意向", "求职岗位", "意向岗位", "意向职位",
            "期望职位", "期望岗位", "目标岗位", "目标职位", "申请岗位", "申请职位", "应聘职务", "拟应聘岗位"};
    private static final String[] APPLY_DEPT_LABELS = {"应聘部门", "意向部门", "期望部门", "申请部门"};
    private static final String[] NAME_LABELS = {"姓名", "名字"};

    private static final Pattern NAME_HINT = Pattern.compile("(?i)简历|履历|求职|应聘|resume|(?<![a-z])cv(?![a-z])");
    private static final Pattern TITLE_HINT = Pattern.compile("(个人简历|求职简历|简历|履历表?|求职信|应聘登记表|应聘申请表)$");
    /** 通知、汇总表、制度这类文件里也会出现“应聘”“工作经历”，不是一份简历 */
    private static final Pattern EXCLUDE = Pattern.compile(
            "通知|启事|公告|方案|计划|报告|纪要|制度|办法|规定|细则|须知|指南|流程|汇总|统计|名单|一览|台账|模板|面试");
    private static final String[] FEATURES = {"求职意向", "应聘岗位", "应聘职位", "期望职位", "期望薪资", "期望薪酬", "工作经历",
            "工作经验", "教育背景", "教育经历", "项目经历", "项目经验", "自我评价", "个人优势", "专业技能", "实习经历", "到岗时间"};
    private static final String[] PERSONAL = {"姓名", "性别", "出生", "年龄", "民族", "籍贯", "政治面貌", "手机", "电话", "邮箱",
            "email", "毕业院校", "学历", "学位", "婚姻", "现居", "户籍"};

    /** 正文里的一句话：“应聘贵公司的销售代表岗位” */
    private static final Pattern INTENT = Pattern.compile(
            "(?:应聘|申请|求职)(?:贵公司|贵单位|贵司)?的?([\\p{IsHan}A-Za-z0-9+#/·]{2,20}?)(?:岗位|职位|一职)");
    /** 值里混着别的字段：“Java开发工程师 | 期望城市：上海”“张三 性别：男” */
    private static final Pattern CUT = Pattern.compile("\\s*(?:[|｜;；,，、]|\\s{2,}|　|[^\\s：:|｜]{2,6}[：:]).*$");
    private static final Pattern BAD_VALUE = Pattern.compile("^(无|暂无|不限|待定|面议|均可|可调剂|服从分配|若干|-+)$");
    private static final Pattern FILE_SPLIT = Pattern.compile("[-_—–\\s　()（）\\[\\]【】·+＋,，]+");
    private static final Pattern RESUME_WORDS = Pattern.compile("(?i)个人简历|求职简历|简历|履历表?|求职信|应聘|求职|resume|(?<![a-z])cv(?![a-z])");
    /** 像岗位名称的文件名片段：以这些词结尾 */
    private static final Pattern ROLE = Pattern.compile(
            ".*(工程师|专员|经理|主管|总监|助理|顾问|师|员|会计|出纳|前台|司机|实习生|管培生|开发|测试|运维|销售|采购|行政|法务|主任|组长|工|客服|专家|编辑|翻译|策划|运营|美工|护士|医生)$");
    private static final Pattern HAN_NAME = Pattern.compile("^[\\p{IsHan}]{2,4}$");
    private static final Pattern LEADING_NAME = Pattern.compile("^[\\p{IsHan}]{2,4}");

    private ResumeDetector() {
    }

    static Info detect(DocModel doc, Regions regions, Anchors anchors, FileNameInfo fn, TemplateConfig tpl) {
        String title = regions.titleText().replaceAll("\\s+", "");
        String base = fn.base();
        if (EXCLUDE.matcher(title).find() || EXCLUDE.matcher(base).find()) {
            return Info.NONE;
        }
        List<Block> blocks = new ArrayList<>(regions.header());
        blocks.addAll(regions.body());
        blocks.addAll(regions.footer());

        String[] position = labeled(anchors, blocks, POSITION_LABELS);
        String text = doc.fullText().toLowerCase(Locale.ROOT);
        int features = count(text, FEATURES);
        int personal = count(text, PERSONAL);
        boolean resume = NAME_HINT.matcher(base).find() || TITLE_HINT.matcher(title).find()
                || position != null && features >= 1 || features >= 2 && personal >= 2;
        if (!resume) {
            return Info.NONE;
        }
        String pos = position == null ? null : position[0];
        String source = position == null ? null : position[1];
        if (pos == null) {
            Matcher m = INTENT.matcher(doc.fullText());
            if (m.find()) {
                pos = clean(m.group(1));
                source = m.group();
            }
        }
        if (pos == null) {
            pos = fromFileName(fn, tpl);
            source = pos == null ? null : "文件名：" + fn.original();
        }
        String[] dept = labeled(anchors, blocks, APPLY_DEPT_LABELS);
        return new Info(true, pos, source, name(anchors, blocks, title, fn, tpl), dept == null ? null : dept[0]);
    }

    /**
     * 岗位命中了哪些部门的规则，最可能的在前：命中的词越长越具体（“安全工程师”比“工程师”具体），
     * 一样长时取靠后的那个（中文岗位名的重点一般在最后，“销售会计”是会计）。
     */
    static List<Match> match(String position, TemplateConfig tpl) {
        if (position == null || position.isBlank()) {
            return List.of();
        }
        String p = position.toLowerCase(Locale.ROOT);
        List<Match> out = new ArrayList<>();
        for (TemplateConfig.KeywordRule r : tpl.getPositionRules() == null ? List.<TemplateConfig.KeywordRule>of() : tpl.getPositionRules()) {
            if (r.getDepartment() == null || tpl.findDept(r.getDepartment()) == null) {
                continue;
            }
            Match best = null;
            for (String kw : r.getKeywords()) {
                int at = find(p, kw == null ? "" : kw.strip().toLowerCase(Locale.ROOT));
                if (at >= 0 && (best == null || kw.strip().length() > best.keyword().length()
                        || kw.strip().length() == best.keyword().length() && at > best.at())) {
                    best = new Match(r.getDepartment(), kw.strip(), at);
                }
            }
            if (best != null && out.stream().noneMatch(m -> m.department().equals(r.getDepartment()))) {
                out.add(best);
            }
        }
        out.sort(Comparator.comparingInt((Match m) -> m.keyword().length()).thenComparingInt(Match::at).reversed());
        return out;
    }

    /** 英文缩写（HR、QC、BD）要整词出现，免得“Chrome”里的 hr 也算命中 */
    private static int find(String text, String kw) {
        if (kw.isEmpty()) {
            return -1;
        }
        boolean ascii = kw.chars().allMatch(c -> c < 128);
        int from = text.length();
        while (true) {
            int at = text.lastIndexOf(kw, from);
            if (at < 0) {
                return -1;
            }
            if (!ascii || !letter(text, at - 1) && !letter(text, at + kw.length())) {
                return at;
            }
            from = at - 1;
            if (from < 0) {
                return -1;
            }
        }
    }

    private static boolean letter(String s, int i) {
        return i >= 0 && i < s.length() && s.charAt(i) < 128 && Character.isLetter(s.charAt(i));
    }

    /**
     * 字段值，返回 {值, 依据}。三种写法：“字段名：值”；表格里“字段名 | 值”；
     * 小标题单独一行（“求职意向”），值写在下一行。
     */
    private static String[] labeled(Anchors anchors, List<Block> blocks, String[] labels) {
        for (Anchors.Anchor a : anchors.withLabel(labels)) {
            String v = clean(a.value());
            if (v != null) {
                return new String[]{v, a.raw()};
            }
        }
        for (int i = 0; i + 1 < blocks.size(); i++) {
            String head = blocks.get(i).text().replaceAll("[：:\\s]+$", "").strip();
            for (String l : labels) {
                if (head.equals(l)) {
                    String next = blocks.get(i + 1).text().split("\\s*\\|\\s*")[0];
                    String v = clean(next);
                    if (v != null) {
                        return new String[]{v, l + "：" + next.strip()};
                    }
                }
            }
        }
        for (Block b : blocks) {
            if (!b.text().contains("|")) {
                continue;
            }
            String[] cells = b.text().split("\\s*\\|\\s*");
            for (int i = 0; i + 1 < cells.length; i++) {
                String label = cells[i].replaceAll("[：:\\s]+$", "").strip();
                for (String l : labels) {
                    if (label.equals(l)) {
                        String v = clean(cells[i + 1]);
                        if (v != null) {
                            return new String[]{v, label + "：" + cells[i + 1].strip()};
                        }
                    }
                }
            }
        }
        return null;
    }

    static String clean(String raw) {
        if (raw == null) {
            return null;
        }
        String v = CUT.matcher(raw.strip()).replaceFirst("").replaceAll("^[\\s：:]+|[\\s。；;，,、.]+$", "").strip();
        if (v.length() < 2 || v.length() > 40 || BAD_VALUE.matcher(v).matches() || !v.matches(".*[\\p{IsHan}A-Za-z].*")) {
            return null;
        }
        return v.length() > 30 ? v.substring(0, 30) : v;
    }

    /** 文件名里的岗位：“张三-Java开发工程师-简历.docx”。优先取命中岗位规则的片段，其次是像岗位名称的片段 */
    private static String fromFileName(FileNameInfo fn, TemplateConfig tpl) {
        String fallback = null;
        for (String seg : segments(fn)) {
            if (!match(seg, tpl).isEmpty()) {
                return seg;
            }
            // 两个字的片段分不清是人名还是岗位（“护士”“李工”），只在三个字以上时当作岗位
            if (fallback == null && ROLE.matcher(seg).matches() && (seg.length() >= 3 || !HAN_NAME.matcher(seg).matches())) {
                fallback = seg;
            }
        }
        return fallback;
    }

    private static List<String> segments(FileNameInfo fn) {
        List<String> out = new ArrayList<>();
        String base = FileNameInfo.DATE_IN_NAME.matcher(fn.base()).replaceAll(" ");
        for (String s : FILE_SPLIT.split(base)) {
            String seg = RESUME_WORDS.matcher(s).replaceAll("").strip();
            if (seg.length() >= 2 && !seg.matches("[\\d.]+|\\d+年(工作)?经验|\\d+年")) {
                out.add(seg);
            }
        }
        return out;
    }

    private static String name(Anchors anchors, List<Block> blocks, String title, FileNameInfo fn, TemplateConfig tpl) {
        String[] v = labeled(anchors, blocks, NAME_LABELS);
        if (v != null) {
            Matcher m = LEADING_NAME.matcher(v[0]);
            if (m.find()) {
                return m.group();
            }
        }
        // 标题就是名字：简历第一行常常只写“张三”
        if (HAN_NAME.matcher(title).matches() && !RESUME_WORDS.matcher(title).find() && tpl.findDept(title) == null) {
            return title;
        }
        for (String seg : segments(fn)) {
            if (HAN_NAME.matcher(seg).matches() && !ROLE.matcher(seg).matches() && tpl.findDept(seg) == null && match(seg, tpl).isEmpty()) {
                return seg;
            }
        }
        return null;
    }

    private static int count(String text, String[] words) {
        int n = 0;
        for (String w : words) {
            if (text.contains(w.toLowerCase(Locale.ROOT))) {
                n++;
            }
        }
        return n;
    }
}
