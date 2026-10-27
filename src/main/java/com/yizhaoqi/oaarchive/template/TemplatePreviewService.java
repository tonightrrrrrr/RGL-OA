package com.yizhaoqi.oaarchive.template;

import com.yizhaoqi.oaarchive.archive.TargetPlanner;
import com.yizhaoqi.oaarchive.common.BizException;
import com.yizhaoqi.oaarchive.config.OaProperties;
import com.yizhaoqi.oaarchive.model.ArchiveBatch;
import com.yizhaoqi.oaarchive.model.FileRecord;
import com.yizhaoqi.oaarchive.parser.DocModel;
import com.yizhaoqi.oaarchive.parser.ParserRegistry;
import com.yizhaoqi.oaarchive.recognize.RecognitionEngine;
import com.yizhaoqi.oaarchive.recognize.RecognitionResult;
import com.yizhaoqi.oaarchive.repository.ArchiveBatchRepository;
import com.yizhaoqi.oaarchive.repository.FileRecordRepository;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 模板实时预览：用真实文件（内置的 10 份赛题示例，或某个已导入批次），按“当前模板”和“基准模板”分别识别、命名，
 * 逐个文件对比结果，并给出重名检测、不同阈值下的效果和推荐阈值、常用写法的实际效果。只是预览，不修改任何数据。
 */
@Service
public class TemplatePreviewService {

    static final int MAX_FILES = 30;
    static final double[] THRESHOLDS = {0.50, 0.55, 0.60, 0.65, 0.70, 0.75, 0.80, 0.85, 0.90, 0.95};
    /** 推荐阈值不低于这个值：示例文件少，阈值太低容易在真实文件上归错 */
    static final double SAFE_FLOOR = 0.60;
    private static final int CACHE_SIZE = 160;
    private static final Set<FileRecord.Status> TRUSTED = EnumSet.of(FileRecord.Status.READY, FileRecord.Status.ARCHIVED,
            FileRecord.Status.UNDONE, FileRecord.Status.DUPLICATE);
    private static final List<String> VAR_KEYS = List.of("部门", "部门代码", "类型", "类型代码", "标题", "日期", "年", "月",
            "序号4", "档号", "原文件名");

    /** 标准答案：内置示例来自赛题；批次来自当前识别结果（含人工修正） */
    public record Truth(String department, String docType, String date) {
    }

    /** 一个文件在某个模板下的结果 */
    public record Side(String department, double deptConf, String docType, double typeConf, String date, double dateConf,
                       String title, double overall, boolean blocked, boolean review, List<String> reasons,
                       String fileName, String dir, String archiveNo, String relativePath, List<String> wrong,
                       Map<String, String> vars) {
    }

    public record Row(String key, String originalName, Truth truth, Side base, Side target, List<String> changed) {
    }

    /** auto：自动归档数；wrongAuto：识别错却会被自动归档的数量；collisions：重名的组数 */
    public record Stat(int auto, int review, int wrong, int wrongAuto, int collisions) {
    }

    public record ThresholdPoint(double threshold, int auto, int review, int wrongAuto) {
    }

    public record Recommendation(Double threshold, String reason) {
    }

    /** 常用写法套用到这批文件后的效果；issue 为该写法的问题（如会重名、档号重复） */
    public record PresetResult(String pattern, List<String> examples, int collisions, int folders, String issue) {
    }

    public record Presets(List<String> naming, List<String> path, List<String> archiveNo, List<String> dateFormat) {
    }

    public record Source(String kind, Long batchId, String label, String truthNote) {
    }

    public record Result(Source source, List<Row> rows, Stat base, Stat target, List<List<String>> collisions,
                         List<ThresholdPoint> thresholds, Recommendation recommendation,
                         Map<String, List<PresetResult>> presets, boolean truncated, List<String> skipped) {
    }

    /** 内部用：一份待预览的文件 */
    private record Doc(String key, String name, Path path, String ext, String cacheKey, Truth truth) {
    }
    private final FileRecordRepository files;
    private final ArchiveBatchRepository batches;
    private final ParserRegistry parsers;
    private final RecognitionEngine engine;
    private final SampleLibrary samples;
    private final OaProperties props;
    /** 解析结果缓存：反复预览时不用重新解析（扫描件 OCR 每页约 2 秒） */
    private final Map<String, DocModel> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, DocModel> eldest) {
            return size() > CACHE_SIZE;
        }
    };

    private final com.yizhaoqi.oaarchive.pipeline.ImportPrefsService importPrefs;

    public TemplatePreviewService(FileRecordRepository files, ArchiveBatchRepository batches, ParserRegistry parsers,
                                  RecognitionEngine engine, SampleLibrary samples, OaProperties props,
                                  com.yizhaoqi.oaarchive.pipeline.ImportPrefsService importPrefs) {
        this.importPrefs = importPrefs;
        this.files = files;
        this.batches = batches;
        this.parsers = parsers;
        this.engine = engine;
        this.samples = samples;
        this.props = props;
    }

    /**
     * @param batchId 为空时用内置示例文件
     * @param base    对比基准，可以为空
     * @param presets 常用写法，前端提供
     */
    public synchronized Result preview(Long batchId, TemplateConfig target, TemplateConfig base, Presets presets) {
        return preview(batchId, false, target, base, presets);
    }

    /** 内置示例的三种用法：原文件名、文件名改成“文档N”、反面案例 */
    public enum SampleSet { ORIGINAL, RENAMED, NEGATIVE }

    /** @param renamedSamples 使用内置示例时，是否把文件名改成“文档N” */
    public synchronized Result preview(Long batchId, boolean renamedSamples, TemplateConfig target, TemplateConfig base,
                                       Presets presets) {
        return preview(batchId, renamedSamples ? SampleSet.RENAMED : SampleSet.ORIGINAL, target, base, presets);
    }

    /** @param set batchId 为空时用哪一套内置示例 */
    public synchronized Result preview(Long batchId, SampleSet set, TemplateConfig target, TemplateConfig base,
                                       Presets presets) {
        List<String> skipped = new ArrayList<>();
        List<Doc> docs = new ArrayList<>();
        Source source = batchId == null && set == SampleSet.NEGATIVE ? collectNegative(docs, skipped)
                : collect(batchId, set == SampleSet.RENAMED, docs, skipped);
        boolean truncated = docs.size() > MAX_FILES;
        if (truncated) {
            docs = docs.subList(0, MAX_FILES);
        }
        String archiveRoot = importPrefs.archiveRoot();
        Path root = Path.of(archiveRoot == null || archiveRoot.isBlank() ? "D:/归档" : archiveRoot);

        List<Doc> ok = new ArrayList<>();
        List<DocModel> models = new ArrayList<>();
        for (Doc d : docs) {
            try {
                models.add(parsed(d));
                ok.add(d);
            } catch (Exception e) {
                skipped.add(d.name() + "（无法解析）");
            }
        }
        List<RecognitionResult> tr = models.stream().map(m -> engine.recognize(m, target)).toList();
        List<RecognitionResult> br = base == null ? tr : models.stream().map(m -> engine.recognize(m, base)).toList();
        List<FileRecord> tRecs = records(ok, tr);
        List<FileRecord> bRecs = base == null ? tRecs : records(ok, br);
        List<Planned> tPlans = plan(tRecs, target, root);
        List<Planned> bPlans = base == null ? tPlans : plan(bRecs, base, root);

        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < ok.size(); i++) {
            Doc d = ok.get(i);
            Side t = side(tr.get(i), tPlans.get(i), d.truth(), true);
            Side b = base == null ? t : side(br.get(i), bPlans.get(i), d.truth(), false);
            rows.add(new Row(d.key(), d.name(), d.truth(), b, t, changes(b, t)));
        }
        List<List<String>> collisions = collisions(rows.stream().map(r -> r.target().relativePath()).toList(),
                rows.stream().map(Row::originalName).toList());
        Stat tStat = stat(rows, true, collisions.size());
        Stat bStat = base == null ? tStat : stat(rows, false, collisions(rows.stream().map(r -> r.base().relativePath()).toList(),
                rows.stream().map(Row::originalName).toList()).size());
        List<ThresholdPoint> sweep = sweep(rows);
        Recommendation rec = recommend(sweep, rows, target.getThreshold());
        Map<String, List<PresetResult>> presetResults = presets(presets, target, tRecs, root);
        return new Result(source, rows, bStat, tStat, collisions, sweep, rec, presetResults, truncated, skipped);
    }
    /** 命名结果和渲染用的占位符取值；plan 为 null 表示规则有误无法生成 */
    private record Planned(TargetPlanner.Plan plan, Map<String, String> vars) {
    }

    private Source collect(Long batchId, boolean renamed, List<Doc> docs, List<String> skipped) {
        if (batchId == null) {
            int n = 0;
            for (SampleLibrary.Sample s : samples.samples()) {
                String ext = SampleLibrary.ext(s.file());
                // “乱命名”示例：文件名换成“文档N”，只能靠正文识别，置信度高低不一，更能看出阈值和关键词规则的作用
                String name = renamed ? "文档" + (++n) + "." + ext : s.file();
                docs.add(new Doc("s:" + name, name, s.path(), ext, "s:" + (renamed ? "r:" : "") + s.file(),
                        new Truth(s.department(), s.docType(), s.date())));
            }
            return renamed
                    ? new Source("SAMPLES_RENAMED", null, "内置示例 · 文件名改成“文档N”（模拟乱命名）", "标准答案来自赛题示例")
                    : new Source("SAMPLES", null, "内置示例 · 原文件名（赛题 10 份文件）", "标准答案来自赛题示例");
        }
        ArchiveBatch b = batches.findById(batchId).orElseThrow(() -> new BizException("批次不存在"));
        for (FileRecord f : files.findByBatchIdOrderByIdAsc(batchId)) {
            if (f.getWorkPath() == null || !Files.exists(Path.of(f.getWorkPath()))) {
                if (f.getStatus() != FileRecord.Status.FAILED) {
                    skipped.add(f.getOriginalName() + "（工作区副本不存在）");
                }
                continue;
            }
            Truth truth = TRUSTED.contains(f.getStatus()) && f.getDepartment() != null
                    ? new Truth(f.getDepartment(), f.getDocType(), f.getDocDate() == null ? null : f.getDocDate().toString())
                    : null;
            docs.add(new Doc("f:" + f.getId(), f.getOriginalName(), Path.of(f.getWorkPath()), f.getExt(),
                    "f:" + f.getId() + ":" + f.getSha256(), truth));
        }
        return new Source("BATCH", batchId, "批次 #" + b.getId() + (b.getSourcePath() == null ? "（上传）" : " · " + b.getSourcePath()),
                "“正确答案”取本批次当前的识别结果（含人工修正）；还在待确认、未修正的文件不计入对错");
    }

    /** 反面案例：导入时会被跳过的文件（临时文件、空文件）不参与预览；预期解析失败的文件解析时会被记入 skipped */
    private Source collectNegative(List<Doc> docs, List<String> skipped) {
        for (SampleLibrary.NegativeCase c : samples.negative()) {
            if ("SKIPPED".equals(c.expect())) {
                skipped.add(c.file() + "（导入时会被跳过）");
                continue;
            }
            Truth truth = c.department() == null && c.docType() == null && c.date() == null ? null
                    : new Truth(c.department(), c.docType(), c.date());
            docs.add(new Doc("n:" + c.file(), c.file(), c.path(), SampleLibrary.ext(c.file()), "n:" + c.file(), truth));
        }
        return new Source("SAMPLES_NEGATIVE", null, "内置示例 · 反面案例（待确认、失败、重复、重名等）",
                "“正确答案”是人工判断的结果；完全看不出部门、类型的文件没有答案，不计入对错");
    }

    private DocModel parsed(Doc d) throws Exception {
        synchronized (cache) {
            DocModel hit = cache.get(d.cacheKey());
            if (hit != null) {
                return hit;
            }
        }
        DocModel m = parsers.parse(d.path(), d.name());
        synchronized (cache) {
            cache.put(d.cacheKey(), m);
        }
        return m;
    }

    private static List<FileRecord> records(List<Doc> docs, List<RecognitionResult> rs) {
        List<FileRecord> out = new ArrayList<>();
        for (int i = 0; i < docs.size(); i++) {
            RecognitionResult r = rs.get(i);
            FileRecord f = new FileRecord();
            f.setOriginalName(docs.get(i).name());
            f.setExt(docs.get(i).ext());
            f.setDepartment(r.department().value());
            f.setDocType(r.docType().value());
            f.setDocDate(r.date().value() == null ? null : LocalDate.parse(r.date().value()));
            f.setTitle(r.title().value());
            out.add(f);
        }
        return out;
    }

    /** 按顺序模拟分配流水号（同年、同部门、同类型依次 0001、0002…），这样能看出档号是否重复 */
    private static List<Planned> plan(List<FileRecord> recs, TemplateConfig tpl, Path root) {
        Map<String, Integer> seq = new HashMap<>();
        List<Planned> out = new ArrayList<>();
        for (FileRecord f : recs) {
            try {
                Map<String, String> v0 = NamingRenderer.variables(fields(f), tpl, 0);
                int n = seq.merge(v0.get("年") + "-" + v0.get("部门代码") + "-" + v0.get("类型代码"), 1, Integer::sum);
                out.add(new Planned(TargetPlanner.plan(f, tpl, root, n), NamingRenderer.variables(fields(f), tpl, n)));
            } catch (RuntimeException e) {
                out.add(new Planned(null, Map.of()));
            }
        }
        return out;
    }

    private static NamingRenderer.Fields fields(FileRecord f) {
        return new NamingRenderer.Fields(f.getDepartment(), f.getDocType(), f.getDocDate(), f.getTitle(), f.getOriginalName());
    }
    private static Side side(RecognitionResult r, Planned p, Truth truth, boolean withVars) {
        String fileName, dir, no, rel;
        if (p.plan() == null) {
            fileName = dir = no = rel = "（规则有误，无法生成）";
        } else {
            rel = p.plan().relative().toString().replace('\\', '/');
            fileName = p.plan().fileName();
            dir = rel.contains("/") ? rel.substring(0, rel.lastIndexOf('/')) : "";
            no = p.plan().archiveNo();
        }
        List<String> wrong = new ArrayList<>();
        if (truth != null) {
            if (!Objects.equals(truth.department(), r.department().value())) {
                wrong.add("部门");
            }
            if (!Objects.equals(truth.docType(), r.docType().value())) {
                wrong.add("类型");
            }
            if (!Objects.equals(truth.date(), r.date().value())) {
                wrong.add("日期");
            }
        }
        Map<String, String> vars = null;
        if (withVars) {
            vars = new LinkedHashMap<>();
            for (String k : VAR_KEYS) {
                vars.put(k, p.vars().getOrDefault(k, ""));
            }
        }
        return new Side(r.department().value(), r.department().confidence(), r.docType().value(), r.docType().confidence(),
                r.date().value(), r.date().confidence(), r.title().value(), r.overall(), r.blocked(), r.needsReview(),
                r.reviewReasons(), fileName, dir, no, rel, wrong, vars);
    }

    private static List<String> changes(Side a, Side b) {
        List<String> out = new ArrayList<>();
        if (!Objects.equals(a.department(), b.department())) {
            out.add("部门");
        }
        if (!Objects.equals(a.docType(), b.docType())) {
            out.add("类型");
        }
        if (!Objects.equals(a.date(), b.date())) {
            out.add("日期");
        }
        if (!Objects.equals(a.title(), b.title())) {
            out.add("标题");
        }
        if (a.review() != b.review()) {
            out.add("待确认");
        }
        if (!Objects.equals(a.fileName(), b.fileName())) {
            out.add("文件名");
        }
        if (!Objects.equals(a.dir(), b.dir())) {
            out.add("目录");
        }
        if (!Objects.equals(a.archiveNo(), b.archiveNo())) {
            out.add("档号");
        }
        return out;
    }

    /** 归档位置相同（不区分大小写）的文件分组；每组至少 2 个文件，返回原文件名 */
    static List<List<String>> collisions(List<String> paths, List<String> names) {
        Map<String, List<String>> groups = new LinkedHashMap<>();
        for (int i = 0; i < paths.size(); i++) {
            groups.computeIfAbsent(paths.get(i).toLowerCase(), k -> new ArrayList<>()).add(names.get(i));
        }
        return groups.values().stream().filter(g -> g.size() > 1).toList();
    }

    private static Stat stat(List<Row> rows, boolean target, int collisions) {
        int auto = 0, review = 0, wrong = 0, wrongAuto = 0;
        for (Row r : rows) {
            Side s = target ? r.target() : r.base();
            boolean isWrong = r.truth() != null && !s.wrong().isEmpty();
            if (s.review()) {
                review++;
            } else {
                auto++;
                wrongAuto += isWrong ? 1 : 0;
            }
            wrong += isWrong ? 1 : 0;
        }
        return new Stat(auto, review, wrong, wrongAuto, collisions);
    }

    /** 不同阈值下当前模板的效果：待确认 = blocked 或 综合置信度 < 阈值 */
    private static List<ThresholdPoint> sweep(List<Row> rows) {
        List<ThresholdPoint> out = new ArrayList<>();
        for (double t : THRESHOLDS) {
            out.add(at(rows, t));
        }
        return out;
    }
    /**
     * 推荐阈值：先找“识别错却被自动归档”最少、其次自动归档最多的阈值区间；
     * 当前阈值已在区间内就建议保持，否则建议调到最近的一端。不低于 SAFE_FLOOR，避免对少量示例过拟合。
     */
    private static Recommendation recommend(List<ThresholdPoint> sweep, List<Row> rows, double current) {
        long known = rows.stream().filter(r -> r.truth() != null).count();
        if (rows.isEmpty()) {
            return new Recommendation(null, "没有可以预览的文件");
        }
        if (known == 0) {
            return new Recommendation(null, "这批文件还没有确认过的结果，无法判断对错。可以切换到“内置示例”，或者先在“待确认”页确认几份文件。");
        }
        List<ThresholdPoint> safe = sweep.stream().filter(p -> p.threshold() >= SAFE_FLOOR - 1e-9).toList();
        int minWrong = safe.stream().mapToInt(ThresholdPoint::wrongAuto).min().orElse(0);
        int maxAuto = safe.stream().filter(p -> p.wrongAuto() == minWrong).mapToInt(ThresholdPoint::auto).max().orElse(0);
        List<ThresholdPoint> best = safe.stream().filter(p -> p.wrongAuto() == minWrong && p.auto() == maxAuto).toList();
        double lo = best.get(0).threshold(), hi = best.get(best.size() - 1).threshold();
        ThresholdPoint cur = at(rows, current);
        String note = known < 20 ? "（只有 " + known + " 份有答案的文件，结论仅供参考）" : "";
        String range = lo == hi ? fmt(lo) : fmt(lo) + "–" + fmt(hi);
        if (current >= lo - 1e-9 && current <= hi + 1e-9) {
            String tail = minWrong > 0
                    ? "但仍有 " + minWrong + " 个识别错的文件会被自动归档，单靠调阈值解决不了，建议补充关键词规则或部门别名（见下方标红的文件）。"
                    : "此时 " + cur.auto() + " 个文件自动归档、" + cur.review() + " 个待确认，没有识别错却被自动归档的文件。";
            return new Recommendation(current, "当前阈值 " + fmt(current) + " 已在最佳区间 " + range + " 内，建议保持。" + tail + note);
        }
        double rec = current < lo ? lo : hi;
        ThresholdPoint r = at(rows, rec);
        String why;
        if (current < lo && cur.wrongAuto() > 0) {
            why = "当前阈值 " + fmt(current) + " 偏低：有 " + cur.wrongAuto() + " 个识别错的文件会被直接自动归档。";
        } else if (current < lo) {
            why = "当前阈值 " + fmt(current) + " 低于安全下限 " + fmt(SAFE_FLOOR)
                    + "：这批文件里暂时没出错，但线索不足的文件也会被直接归档，换一批文件容易归错目录。";
        } else {
            why = "当前阈值 " + fmt(current) + " 偏高：" + cur.review() + " 个文件要人工确认，其中 "
                    + (cur.review() - r.review()) + " 个其实识别正确。";
        }
        return new Recommendation(rec, why + "调到 " + fmt(rec) + " 后：" + r.auto() + " 个自动归档、" + r.review()
                + " 个待确认，识别错却被自动归档 " + r.wrongAuto() + " 个。最佳区间 " + range + "。" + note);
    }

    private static ThresholdPoint at(List<Row> rows, double t) {
        int auto = 0, review = 0, wrongAuto = 0;
        for (Row r : rows) {
            Side s = r.target();
            if (s.blocked() || s.overall() < t) {
                review++;
            } else {
                auto++;
                wrongAuto += r.truth() != null && !s.wrong().isEmpty() ? 1 : 0;
            }
        }
        return new ThresholdPoint(t, auto, review, wrongAuto);
    }

    private static String fmt(double t) {
        return String.format("%.2f", t);
    }
    /** 把每个常用写法套用到这批文件（识别结果不变，只换规则），算出实际效果 */
    private static Map<String, List<PresetResult>> presets(Presets p, TemplateConfig target, List<FileRecord> recs, Path root) {
        Map<String, List<PresetResult>> out = new LinkedHashMap<>();
        if (p == null) {
            return out;
        }
        out.put("naming", evalAll(p.naming(), target, recs, root, "naming"));
        out.put("path", evalAll(p.path(), target, recs, root, "path"));
        out.put("archiveNo", evalAll(p.archiveNo(), target, recs, root, "archiveNo"));
        out.put("dateFormat", evalAll(p.dateFormat(), target, recs, root, "dateFormat"));
        return out;
    }

    private static List<PresetResult> evalAll(List<String> patterns, TemplateConfig target, List<FileRecord> recs,
                                              Path root, String kind) {
        List<PresetResult> out = new ArrayList<>();
        if (patterns == null) {
            return out;
        }
        for (String pattern : patterns.stream().limit(8).toList()) {
            TemplateConfig c = copy(target);
            switch (kind) {
                case "naming" -> c.setNamingPattern(pattern);
                case "path" -> c.setPathPattern(pattern);
                case "archiveNo" -> c.setArchiveNoPattern(pattern);
                default -> c.setDateFormat(pattern);
            }
            List<Planned> plans = plan(recs, c, root);
            if (plans.stream().anyMatch(x -> x.plan() == null)) {
                out.add(new PresetResult(pattern, List.of(), 0, 0, "这个写法无法使用"));
                continue;
            }
            List<String> rels = plans.stream().map(x -> x.plan().relative().toString().replace('\\', '/')).toList();
            List<String> names = recs.stream().map(FileRecord::getOriginalName).toList();
            int coll = collisions(rels, names).size();
            Set<String> folders = new HashSet<>();
            rels.forEach(r -> folders.add(r.contains("/") ? r.substring(0, r.lastIndexOf('/')) : ""));
            List<String> examples;
            String issue = null;
            switch (kind) {
                case "naming" -> {
                    examples = plans.stream().limit(3).map(x -> x.plan().fileName()).toList();
                    issue = coll > 0 ? coll + " 组文件会重名" : null;
                }
                case "path" -> {
                    examples = folders.stream().sorted().limit(3).map(f -> f.isEmpty() ? "（归档根目录）" : f + "/").toList();
                    issue = folders.size() <= 1 && recs.size() > 1 ? "所有文件放在同一个目录" : null;
                }
                case "archiveNo" -> {
                    examples = plans.stream().limit(3).map(x -> x.plan().archiveNo()).toList();
                    int dup = collisions(plans.stream().map(x -> x.plan().archiveNo()).toList(), names).size();
                    coll = dup;
                    issue = dup > 0 ? dup + " 组档号重复" : null;
                }
                default -> examples = plans.stream().limit(2).map(x -> x.vars().getOrDefault("日期", "")).toList();
            }
            out.add(new PresetResult(pattern, examples, coll, folders.size(), issue));
        }
        return out;
    }

    /** 浅拷贝，只用于替换一项规则后试算 */
    private static TemplateConfig copy(TemplateConfig s) {
        TemplateConfig c = new TemplateConfig();
        c.setCompanyName(s.getCompanyName());
        c.setStripCompanyPrefix(s.isStripCompanyPrefix());
        c.setDepartments(s.getDepartments());
        c.setDocTypes(s.getDocTypes());
        c.setKeywordRules(s.getKeywordRules());
        c.setNamingPattern(s.getNamingPattern());
        c.setPathPattern(s.getPathPattern());
        c.setArchiveNoPattern(s.getArchiveNoPattern());
        c.setDateFormat(s.getDateFormat());
        c.setThreshold(s.getThreshold());
        c.setArchiveMode(s.getArchiveMode());
        return c;
    }
}