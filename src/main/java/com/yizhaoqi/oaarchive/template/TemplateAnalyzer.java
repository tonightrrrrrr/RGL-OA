package com.yizhaoqi.oaarchive.template;

import com.yizhaoqi.oaarchive.template.TemplateConfig.DeptDef;
import com.yizhaoqi.oaarchive.template.TemplateConfig.TypeDef;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 模板智能检查与对比：找出模板里容易导致识别错、重名、档号重复的问题并给出建议（部分可一键修复），
 * 以及对比两个模板的差异。全部基于规则，不调用大模型，结果可以复现。
 */
public final class TemplateAnalyzer {

    public static final Set<String> PLACEHOLDERS = Set.of("部门", "部门代码", "类型", "类型代码", "标题", "日期",
            "年", "月", "序号4", "档号", "原文件名");
    private static final Pattern PH = Pattern.compile("\\{([^{}]+)}");

    public enum Level { ERROR, WARNING, INFO }

    /** 一键修复。kind：ADD_DEPT、ADD_TYPE、APPEND_NAMING、APPEND_ARCHIVE_NO、SET_COPY */
    public record Fix(String kind, String value, String label) {
    }

    /** area：部门、类型、关键词、命名、路径、档号、日期、阈值、归档方式、其他 */
    public record Issue(Level level, String area, String message, String suggestion, Fix fix) {
    }

    private TemplateAnalyzer() {
    }
    /** 智能检查：返回按严重程度排序的问题列表 */
    public static List<Issue> lint(TemplateConfig c) {
        List<Issue> out = new ArrayList<>();
        checkDepartments(c, out);
        checkTypes(c, out);
        checkRules(c, out);
        checkPatterns(c, out);
        checkDateFormat(c, out);
        checkSettings(c, out);
        out.sort(Comparator.comparing(Issue::level));
        return out;
    }

    private static void checkDepartments(TemplateConfig c, List<Issue> out) {
        List<DeptDef> depts = nn(c.getDepartments());
        if (depts.isEmpty()) {
            out.add(new Issue(Level.ERROR, "部门", "部门词典是空的", "至少添加一个部门",
                    new Fix("ADD_DEPT", "新部门", "添加一个部门")));
            return;
        }
        Map<String, String> owner = new HashMap<>(); // 名称或别名 → 所属部门
        Map<String, List<String>> byCode = new LinkedHashMap<>();
        Set<String> names = new HashSet<>();
        for (DeptDef d : depts) {
            String name = trim(d.getName());
            if (name.isEmpty()) {
                out.add(new Issue(Level.ERROR, "部门", "有一个部门没有填写名称", "填写名称或删除这一行", null));
                continue;
            }
            if (!names.add(name)) {
                out.add(new Issue(Level.ERROR, "部门", "部门“" + name + "”重复", "删除重复的一行", null));
                continue;
            }
            claim(owner, name, name, out);
            String code = trim(d.getCode());
            if (code.isEmpty()) {
                out.add(new Issue(Level.WARNING, "部门", "部门“" + name + "”没有代码", "档号里会用 WZ 代替，建议填写两位字母代码", null));
            } else {
                byCode.computeIfAbsent(code.toUpperCase(), k -> new ArrayList<>()).add(name);
            }
            for (String a : nn(d.getAliases())) {
                if (!trim(a).isEmpty()) {
                    claim(owner, trim(a), name, out);
                }
            }
        }
        byCode.forEach((code, sharing) -> {
            if (sharing.size() > 1) {
                out.add(new Issue(Level.WARNING, "部门", "代码 " + code + " 被 " + String.join("、", sharing) + " 共用",
                        "档号里无法区分这些部门，建议改成不同的代码", null));
            }
        });
    }

    /** 名称、别名不能指向两个部门，否则文件名里出现这个词时无法判断 */
    private static void claim(Map<String, String> owner, String word, String dept, List<Issue> out) {
        String prev = owner.putIfAbsent(word, dept);
        if (prev != null && !prev.equals(dept)) {
            out.add(new Issue(Level.WARNING, "部门", "“" + word + "”同时是" + prev + "和" + dept + "的名称或别名",
                    "只保留在一个部门下", null));
        }
    }
    private static void checkTypes(TemplateConfig c, List<Issue> out) {
        List<TypeDef> types = nn(c.getDocTypes());
        if (types.isEmpty()) {
            out.add(new Issue(Level.ERROR, "类型", "类型词典是空的", "至少添加一个类型",
                    new Fix("ADD_TYPE", TemplateConfig.OTHER_TYPE, "添加“其他”类型")));
            return;
        }
        Set<String> names = new HashSet<>();
        Map<String, String> suffixOwner = new HashMap<>();
        for (TypeDef t : types) {
            String name = trim(t.getName());
            if (name.isEmpty()) {
                out.add(new Issue(Level.ERROR, "类型", "有一个类型没有填写名称", "填写名称或删除这一行", null));
                continue;
            }
            if (!names.add(name)) {
                out.add(new Issue(Level.ERROR, "类型", "类型“" + name + "”重复", "删除重复的一行", null));
                continue;
            }
            if (trim(t.getCode()).isEmpty()) {
                out.add(new Issue(Level.WARNING, "类型", "类型“" + name + "”没有代码", "档号里会用 QT 代替，建议填写两位字母代码", null));
            }
            if (nn(t.getSuffixes()).isEmpty() && !TemplateConfig.OTHER_TYPE.equals(name)) {
                out.add(new Issue(Level.INFO, "类型", "类型“" + name + "”没有标题后缀",
                        "标题后缀（如“通知”“纪要”）是判断类型最可靠的依据，建议至少填一个", null));
            }
            for (String s : nn(t.getSuffixes())) {
                String prev = suffixOwner.putIfAbsent(trim(s), name);
                if (prev != null && !prev.equals(name)) {
                    out.add(new Issue(Level.WARNING, "类型", "标题后缀“" + s + "”同时属于" + prev + "和" + name,
                            "同一个后缀只放在一个类型下，否则会进入待确认", null));
                }
            }
        }
        if (!names.contains(TemplateConfig.OTHER_TYPE)) {
            out.add(new Issue(Level.INFO, "类型", "没有“其他”类型", "识别不出类型时会归到“其他”，建议保留这个兜底类型",
                    new Fix("ADD_TYPE", TemplateConfig.OTHER_TYPE, "添加“其他”类型")));
        }
    }

    private static void checkRules(TemplateConfig c, List<Issue> out) {
        Set<String> depts = new HashSet<>();
        nn(c.getDepartments()).forEach(d -> depts.add(trim(d.getName())));
        Map<String, Set<String>> keywordOwners = new LinkedHashMap<>();
        Set<String> covered = new HashSet<>();
        Set<String> missingDepts = new LinkedHashSet<>();
        for (TemplateConfig.KeywordRule r : nn(c.getKeywordRules())) {
            String d = trim(r.getDepartment());
            if (d.isEmpty()) {
                out.add(new Issue(Level.WARNING, "关键词", "有一条关键词规则没有指定部门", "选择部门或删除这条规则", null));
                continue;
            }
            if (!depts.contains(d)) {
                missingDepts.add(d);
            } else if (!nn(r.getKeywords()).isEmpty()) {
                covered.add(d);
            }
            for (String k : nn(r.getKeywords())) {
                String kw = trim(k);
                if (kw.length() == 1) {
                    out.add(new Issue(Level.WARNING, "关键词", "关键词“" + kw + "”只有一个字（" + d + "）",
                            "单字很容易在正文里误命中，建议改成两个字以上的词", null));
                }
                keywordOwners.computeIfAbsent(kw, x -> new LinkedHashSet<>()).add(d);
            }
        }
        for (String d : missingDepts) {
            out.add(new Issue(Level.ERROR, "关键词", "关键词规则里的部门“" + d + "”不在部门词典中",
                    "命中这条规则时会识别出词典里没有的部门，待确认页也无法选择", new Fix("ADD_DEPT", d, "把“" + d + "”加入部门词典")));
        }
        keywordOwners.forEach((kw, owners) -> {
            if (owners.size() > 1 && !kw.isEmpty()) {
                out.add(new Issue(Level.WARNING, "关键词", "关键词“" + kw + "”同时指向 " + String.join("、", owners),
                        "命中时这几个部门得分接近，文件会进入待确认；建议只保留在一个部门下", null));
            }
        });
        List<String> uncovered = depts.stream().filter(d -> !d.isEmpty() && !covered.contains(d)).sorted().toList();
        if (!uncovered.isEmpty()) {
            out.add(new Issue(Level.INFO, "关键词", "这些部门没有关键词规则：" + String.join("、", uncovered),
                    "文件名和正文都没写部门名时，这些部门只能靠人工确认；可以补充业务关键词", null));
        }
    }
    private static void checkPatterns(TemplateConfig c, List<Issue> out) {
        String naming = trim(c.getNamingPattern());
        Set<String> np = placeholders(naming);
        if (np.isEmpty()) {
            out.add(new Issue(Level.ERROR, "命名", "命名规则里没有占位符，所有文件会叫同一个名字", "至少包含 {标题}",
                    new Fix("APPEND_NAMING", "_{标题}", "在命名规则末尾加上 _{标题}")));
        } else {
            unknown(np, "命名", out);
            if (Collections.disjoint(np, Set.of("标题", "原文件名", "序号4", "档号"))) {
                out.add(new Issue(Level.WARNING, "命名", "命名规则里没有 {标题}、{原文件名} 或 {序号4}",
                        "同一天同部门同类型的文件会重名，只能靠自动加“(2)”区分",
                        new Fix("APPEND_NAMING", "_{标题}", "在命名规则末尾加上 _{标题}")));
            }
            if (Collections.disjoint(np, Set.of("日期", "年", "月", "档号"))) {
                out.add(new Issue(Level.INFO, "命名", "命名规则里没有日期", "从文件名看不出文件日期，可以考虑加上 {日期}", null));
            }
        }
        if (naming.contains("/") || naming.contains("\\")) {
            out.add(new Issue(Level.WARNING, "命名", "命名规则里有斜杠", "文件名里的斜杠会被替换成全角字符；分目录请写在归档路径里", null));
        }
        String path = trim(c.getPathPattern());
        if (path.isEmpty()) {
            out.add(new Issue(Level.WARNING, "路径", "归档路径为空", "所有文件会放在归档根目录下，不分目录", null));
        } else {
            unknown(placeholders(path), "路径", out);
        }
        Set<String> ap = placeholders(c.getArchiveNoPattern());
        unknown(ap, "档号", out);
        if (!ap.contains("序号4")) {
            out.add(new Issue(Level.WARNING, "档号", "档号规则里没有 {序号4}", "同年同部门同类型的文件档号会重复，只能自动加后缀区分",
                    new Fix("APPEND_ARCHIVE_NO", "-{序号4}", "在档号规则末尾加上 -{序号4}")));
        }
        if (ap.contains("档号")) {
            out.add(new Issue(Level.ERROR, "档号", "档号规则里引用了 {档号} 本身", "删除 {档号}，改用 {年}{部门代码} 等组合", null));
        }
    }

    private static void unknown(Set<String> used, String area, List<Issue> out) {
        for (String p : used) {
            if (!PLACEHOLDERS.contains(p)) {
                out.add(new Issue(Level.ERROR, area, "未知占位符 {" + p + "}", "可用的占位符：" + String.join(" ",
                        PLACEHOLDERS.stream().sorted().map(x -> "{" + x + "}").toList()), null));
            }
        }
    }

    private static void checkDateFormat(TemplateConfig c, List<Issue> out) {
        String f = trim(c.getDateFormat());
        if (f.isEmpty()) {
            out.add(new Issue(Level.INFO, "日期", "日期格式为空", "将使用 yyyy-MM-dd", null));
            return;
        }
        try {
            String s = LocalDate.of(2026, 3, 15).format(DateTimeFormatter.ofPattern(f));
            if (s.contains("/") || s.contains("\\") || s.contains(":")) {
                out.add(new Issue(Level.WARNING, "日期", "日期格式生成的“" + s + "”含有斜杠或冒号",
                        "这些字符不能出现在文件名里，会被替换成全角；建议用 yyyy-MM-dd 或 yyyyMMdd", null));
            }
        } catch (IllegalArgumentException | DateTimeException e) {
            out.add(new Issue(Level.ERROR, "日期", "日期格式“" + f + "”无法使用",
                    "常用写法：yyyy-MM-dd、yyyyMMdd、yyyy年MM月dd日（注意大小写：MM 是月，mm 是分钟）", null));
        }
    }

    private static void checkSettings(TemplateConfig c, List<Issue> out) {
        double t = c.getThreshold();
        if (t <= 0 || t > 1) {
            out.add(new Issue(Level.ERROR, "阈值", "置信度阈值应在 0 到 1 之间", "推荐 0.75", null));
        } else if (t < 0.6) {
            out.add(new Issue(Level.WARNING, "阈值", "置信度阈值偏低（" + t + "）", "线索不足的文件也会直接归档，容易归错目录；推荐 0.75", null));
        } else if (t > 0.9) {
            out.add(new Issue(Level.INFO, "阈值", "置信度阈值偏高（" + t + "）", "大部分文件会进入待确认，人工工作量会增加", null));
        }
        String mode = c.getArchiveMode();
        if (!"COPY".equals(mode) && !"MOVE".equals(mode)) {
            out.add(new Issue(Level.ERROR, "归档方式", "归档方式只能是复制或移动", "推荐复制", new Fix("SET_COPY", "COPY", "改为复制")));
        } else if ("MOVE".equals(mode)) {
            out.add(new Issue(Level.WARNING, "归档方式", "归档方式为“移动”，归档后会删除原文件",
                    "除非确定不再需要原文件夹，否则推荐“复制”", new Fix("SET_COPY", "COPY", "改为复制")));
        }
        if (c.isStripCompanyPrefix() && trim(c.getCompanyName()).isEmpty()) {
            out.add(new Issue(Level.INFO, "其他", "开启了“去掉标题公司名”但没有填写公司名称", "会按“××有限公司”的格式自动识别并去掉", null));
        }
    }
    // ---------------------------------------------------------------- 对比

    /** 一项设置的变化 */
    public record FieldChange(String field, String label, String base, String target) {
    }

    /** 词典里的一项：新增 / 删除 / 修改，detail 说明改了什么 */
    public record ItemChange(String name, String change, String detail) {
    }

    /** 某个部门的关键词变化 */
    public record KeywordChange(String department, List<String> added, List<String> removed) {
    }

    public record Diff(List<FieldChange> settings, List<ItemChange> departments, List<ItemChange> docTypes,
                       List<KeywordChange> keywords, int total) {
    }

    /** 以 base 为基准，列出 target 的变化 */
    public static Diff diff(TemplateConfig base, TemplateConfig target) {
        List<FieldChange> s = new ArrayList<>();
        field(s, "namingPattern", "命名规则", base.getNamingPattern(), target.getNamingPattern());
        field(s, "pathPattern", "归档路径", base.getPathPattern(), target.getPathPattern());
        field(s, "archiveNoPattern", "档号规则", base.getArchiveNoPattern(), target.getArchiveNoPattern());
        field(s, "dateFormat", "日期格式", base.getDateFormat(), target.getDateFormat());
        field(s, "companyName", "公司名称", base.getCompanyName(), target.getCompanyName());
        field(s, "stripCompanyPrefix", "去掉标题公司名", yesNo(base.isStripCompanyPrefix()), yesNo(target.isStripCompanyPrefix()));
        field(s, "threshold", "置信度阈值", String.valueOf(base.getThreshold()), String.valueOf(target.getThreshold()));
        field(s, "archiveMode", "归档方式", mode(base.getArchiveMode()), mode(target.getArchiveMode()));

        List<ItemChange> depts = items(nn(base.getDepartments()), nn(target.getDepartments()), DeptDef::getName,
                (a, b) -> describe(a.getCode(), b.getCode(), a.getAliases(), b.getAliases(), "别名"));
        List<ItemChange> types = items(nn(base.getDocTypes()), nn(target.getDocTypes()), TypeDef::getName,
                (a, b) -> {
                    String d1 = describe(a.getCode(), b.getCode(), a.getSuffixes(), b.getSuffixes(), "后缀");
                    String d2 = listDelta(a.getFeatures(), b.getFeatures(), "内容特征");
                    return join(d1, d2);
                });
        List<KeywordChange> kw = new ArrayList<>();
        Map<String, Set<String>> bk = keywords(base);
        Map<String, Set<String>> tk = keywords(target);
        Set<String> allDepts = new LinkedHashSet<>(bk.keySet());
        allDepts.addAll(tk.keySet());
        for (String d : allDepts) {
            Set<String> before = bk.getOrDefault(d, Set.of());
            Set<String> after = tk.getOrDefault(d, Set.of());
            List<String> added = after.stream().filter(x -> !before.contains(x)).toList();
            List<String> removed = before.stream().filter(x -> !after.contains(x)).toList();
            if (!added.isEmpty() || !removed.isEmpty()) {
                kw.add(new KeywordChange(d, added, removed));
            }
        }
        return new Diff(s, depts, types, kw, s.size() + depts.size() + types.size() + kw.size());
    }

    private static void field(List<FieldChange> out, String key, String label, String a, String b) {
        if (!trim(a).equals(trim(b))) {
            out.add(new FieldChange(key, label, trim(a), trim(b)));
        }
    }

    private static <T> List<ItemChange> items(List<T> base, List<T> target, Function<T, String> name,
                                              BiFunction<T, T, String> describe) {
        Map<String, T> b = index(base, name);
        Map<String, T> t = index(target, name);
        List<ItemChange> out = new ArrayList<>();
        t.forEach((k, v) -> {
            if (!b.containsKey(k)) {
                out.add(new ItemChange(k, "ADDED", null));
            } else {
                String d = describe.apply(b.get(k), v);
                if (!d.isEmpty()) {
                    out.add(new ItemChange(k, "CHANGED", d));
                }
            }
        });
        b.keySet().stream().filter(k -> !t.containsKey(k)).forEach(k -> out.add(new ItemChange(k, "REMOVED", null)));
        return out;
    }

    private static <T> Map<String, T> index(List<T> list, Function<T, String> name) {
        Map<String, T> m = new LinkedHashMap<>();
        for (T x : list) {
            String n = trim(name.apply(x));
            if (!n.isEmpty()) {
                m.putIfAbsent(n, x);
            }
        }
        return m;
    }

    private static String describe(String codeA, String codeB, List<String> a, List<String> b, String listName) {
        String code = trim(codeA).equals(trim(codeB)) ? "" : "代码 " + trim(codeA) + " → " + trim(codeB);
        return join(code, listDelta(a, b, listName));
    }

    private static String listDelta(List<String> a, List<String> b, String listName) {
        Set<String> sa = new LinkedHashSet<>(nn(a));
        Set<String> sb = new LinkedHashSet<>(nn(b));
        List<String> added = sb.stream().filter(x -> !sa.contains(x)).toList();
        List<String> removed = sa.stream().filter(x -> !sb.contains(x)).toList();
        String s = "";
        if (!added.isEmpty()) {
            s = listName + "新增 " + String.join("、", added);
        }
        if (!removed.isEmpty()) {
            s = join(s, listName + "删除 " + String.join("、", removed));
        }
        return s;
    }

    private static Map<String, Set<String>> keywords(TemplateConfig c) {
        Map<String, Set<String>> m = new LinkedHashMap<>();
        for (TemplateConfig.KeywordRule r : nn(c.getKeywordRules())) {
            m.computeIfAbsent(trim(r.getDepartment()), k -> new LinkedHashSet<>()).addAll(nn(r.getKeywords()));
        }
        return m;
    }

    static Set<String> placeholders(String pattern) {
        Set<String> s = new LinkedHashSet<>();
        Matcher m = PH.matcher(pattern == null ? "" : pattern);
        while (m.find()) {
            s.add(m.group(1).strip());
        }
        return s;
    }

    private static String join(String a, String b) {
        return a.isEmpty() ? b : b.isEmpty() ? a : a + "；" + b;
    }

    private static String yesNo(boolean b) {
        return b ? "是" : "否";
    }

    private static String mode(String m) {
        return "MOVE".equals(m) ? "移动" : "复制";
    }

    private static String trim(String s) {
        return s == null ? "" : s.strip();
    }

    private static <T> List<T> nn(Collection<T> c) {
        return c == null ? List.of() : new ArrayList<>(c);
    }
}