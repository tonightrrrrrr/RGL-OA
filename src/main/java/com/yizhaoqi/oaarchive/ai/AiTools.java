package com.yizhaoqi.oaarchive.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yizhaoqi.oaarchive.common.BizException;
import com.yizhaoqi.oaarchive.ledger.LedgerService;
import com.yizhaoqi.oaarchive.model.ArchiveBatch;
import com.yizhaoqi.oaarchive.model.FileRecord;
import com.yizhaoqi.oaarchive.model.LedgerEntry;
import com.yizhaoqi.oaarchive.model.TemplateEntity;
import com.yizhaoqi.oaarchive.pipeline.BatchService;
import com.yizhaoqi.oaarchive.repository.ArchiveBatchRepository;
import com.yizhaoqi.oaarchive.repository.FileRecordRepository;
import com.yizhaoqi.oaarchive.template.TemplateAnalyzer;
import com.yizhaoqi.oaarchive.template.TemplateConfig;
import com.yizhaoqi.oaarchive.template.TemplatePreviewService;
import com.yizhaoqi.oaarchive.template.TemplateService;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * 小R 能用的工具。只读工具直接执行；会修改数据的工具只生成确认卡片（见 {@link AiActions}），由用户确认后执行。
 * 归档、撤销、删除批次或文件、删除模板这类不可逆或影响大的操作不提供给模型，只能在界面上手动做。
 */
@Component
public class AiTools {

    /** 工具执行结果：content 回给模型；summary 显示在界面的工具标签上；action、download 由界面单独展示 */
    public record Outcome(String content, String summary, AiActions.View action, Download download) {
    }

    public record Download(String url, String label) {
    }

    private static final int MAX_RESULT_CHARS = 8000;

    private final ObjectMapper mapper;
    private final AiClient client;
    private final LedgerService ledger;
    private final FileRecordRepository files;
    private final ArchiveBatchRepository batches;
    private final BatchService batchService;
    private final TemplateService templates;
    private final TemplatePreviewService previews;
    private final AiActions actions;

    public AiTools(ObjectMapper mapper, AiClient client, LedgerService ledger, FileRecordRepository files,
                   ArchiveBatchRepository batches, BatchService batchService, TemplateService templates,
                   TemplatePreviewService previews, AiActions actions) {
        this.mapper = mapper;
        this.client = client;
        this.ledger = ledger;
        this.files = files;
        this.batches = batches;
        this.batchService = batchService;
        this.templates = templates;
        this.previews = previews;
        this.actions = actions;
    }

    /** 界面上工具标签的文字 */
    public static String label(String name) {
        return switch (name) {
            case "search_ledger" -> "查询台账";
            case "ledger_stats" -> "统计台账";
            case "list_batches" -> "查看导入批次";
            case "list_review_files" -> "查看待确认文件";
            case "get_file" -> "查看文件详情";
            case "list_templates" -> "查看模板列表";
            case "get_template" -> "读取模板";
            case "propose_template_change" -> "生成模板修改方案";
            case "propose_file_correction" -> "生成识别修正方案";
            case "export_ledger" -> "准备导出台账";
            default -> name;
        };
    }

    /** 工具定义（OpenAI tools 格式） */
    public ArrayNode definitions() {
        ArrayNode t = mapper.createArrayNode();
        t.add(def("search_ledger", "搜索文件目录台账（已归档的文件）。回答“有哪些文件”“某份文件在哪”之类的问题前先调用。",
                """
                {"keyword":{"type":"string","description":"关键词，多个词用空格分隔，匹配标题、文件名、关键词、档号、合同编号、甲乙方等"},
                 "department":{"type":"string","description":"部门全称，如 人力资源部"},
                 "docType":{"type":"string","description":"文件类型，如 通知、合同、会议纪要"},
                 "dateFrom":{"type":"string","description":"文件日期起，yyyy-MM-dd"},
                 "dateTo":{"type":"string","description":"文件日期止，yyyy-MM-dd"},
                 "limit":{"type":"integer","description":"最多返回条数，默认 20，最大 50"}}"""));
        t.add(def("ledger_stats", "按维度统计台账中的文件数量。",
                """
                {"groupBy":{"type":"string","enum":["department","docType","year","month","format"],"description":"统计维度"},
                 "department":{"type":"string"},"docType":{"type":"string"},
                 "dateFrom":{"type":"string","description":"yyyy-MM-dd"},"dateTo":{"type":"string","description":"yyyy-MM-dd"}}"""));
        t.add(def("list_batches", "列出最近的导入批次及各状态文件数。", """
                {"limit":{"type":"integer","description":"默认 10"}}"""));
        t.add(def("list_review_files", "列出所有“待确认”的文件（识别置信度低或有疑点），含识别结果和原因。", """
                {"limit":{"type":"integer","description":"默认 30"}}"""));
        t.add(def("get_file", "查看导入记录里某个文件的详细识别结果。", """
                {"fileId":{"type":"integer","description":"文件记录 id"}}"""));
        t.add(def("list_templates", "列出所有归档模板。", "{}"));
        t.add(def("get_template", "读取一个模板的完整规则：命名、路径、档号、阈值、部门词典、类型词典、关键词规则。", """
                {"templateId":{"type":"integer","description":"模板 id，不填则读默认模板"}}"""));
        t.add(defWithSchema("propose_template_change",
                "生成模板修改方案，交给用户确认，不会直接保存。内置模板会另存为新模板。"
                        + "只填需要修改的字段。占位符：{部门}{部门代码}{类型}{类型代码}{标题}{日期}{年}{月}{序号4}{原文件名}。",
                templateChangeSchema()));
        t.add(def("propose_file_correction", "生成修正某个文件识别结果的方案（部门、类型、日期、标题），交给用户确认。"
                        + "所有字段都不填表示确认当前识别结果无误。已归档的文件不能修正。",
                """
                {"fileId":{"type":"integer"},"department":{"type":"string"},"docType":{"type":"string"},
                 "docDate":{"type":"string","description":"yyyy-MM-dd"},"title":{"type":"string"},
                 "reason":{"type":"string","description":"修正理由，显示给用户"}}"""));
        t.add(def("export_ledger", "按条件准备台账 Excel 导出，界面上会出现下载按钮。", """
                {"keyword":{"type":"string"},"department":{"type":"string"},"docType":{"type":"string"},
                 "dateFrom":{"type":"string","description":"yyyy-MM-dd"},"dateTo":{"type":"string","description":"yyyy-MM-dd"}}"""));
        return t;
    }

    private ObjectNode templateChangeSchema() {
        String strList = "{\"type\":\"array\",\"items\":{\"type\":\"string\"}}";
        String json = """
                {"type":"object","required":["reason"],"properties":{
                 "templateId":{"type":"integer","description":"要修改的模板 id，不填则用默认模板"},
                 "saveAsName":{"type":"string","description":"另存为的新模板名；修改内置模板时必须另存"},
                 "namingPattern":{"type":"string"},"pathPattern":{"type":"string","description":"目录层级用 / 分隔"},
                 "archiveNoPattern":{"type":"string"},"dateFormat":{"type":"string","description":"如 yyyy-MM-dd、yyyyMMdd"},
                 "threshold":{"type":"number","description":"置信度阈值 0.5–0.95"},
                 "companyName":{"type":"string"},
                 "addDepartments":{"type":"array","items":{"type":"object","properties":{
                    "name":{"type":"string"},"code":{"type":"string","description":"部门代码，用于档号"},"aliases":%1$s},"required":["name"]}},
                 "removeDepartments":%1$s,
                 "addDocTypes":{"type":"array","items":{"type":"object","properties":{
                    "name":{"type":"string"},"code":{"type":"string"},"suffixes":%1$s,"features":%1$s},"required":["name"]}},
                 "addKeywords":{"type":"array","description":"给部门加推断关键词","items":{"type":"object","properties":{
                    "department":{"type":"string"},"keywords":%1$s},"required":["department","keywords"]}},
                 "removeKeywords":{"type":"array","items":{"type":"object","properties":{
                    "department":{"type":"string"},"keywords":%1$s},"required":["department","keywords"]}},
                 "reason":{"type":"string","description":"为什么这样改，显示给用户"}}}""".formatted(strList);
        return (ObjectNode) read(json);
    }

    private ObjectNode def(String name, String description, String properties) {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", read(properties));
        return client.toolWithSchema(name, description, schema);
    }

    private ObjectNode defWithSchema(String name, String description, ObjectNode schema) {
        return client.toolWithSchema(name, description, schema);
    }

    private JsonNode read(String json) {
        try {
            return mapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("工具定义 JSON 有误", e);
        }
    }

    /** 执行一次工具调用；出错时把原因回给模型，让它换个方式或告诉用户 */
    public Outcome run(String name, String argsJson, boolean allowContent) {
        JsonNode a;
        try {
            a = argsJson == null || argsJson.isBlank() ? mapper.createObjectNode() : mapper.readTree(argsJson);
        } catch (JsonProcessingException e) {
            return error("参数不是有效的 JSON，请重新调用");
        }
        try {
            return switch (name) {
                case "search_ledger" -> searchLedger(a);
                case "ledger_stats" -> ledgerStats(a);
                case "list_batches" -> listBatches(a);
                case "list_review_files" -> listReview(a);
                case "get_file" -> getFile(a, allowContent);
                case "list_templates" -> listTemplates();
                case "get_template" -> getTemplate(a);
                case "propose_template_change" -> proposeTemplate(a);
                case "propose_file_correction" -> proposeCorrection(a);
                case "export_ledger" -> exportLedger(a);
                default -> error("没有这个工具：" + name);
            };
        } catch (BizException e) {
            return error(e.getMessage());
        } catch (RuntimeException e) {
            return error("执行出错：" + AiClient.clip(String.valueOf(e.getMessage()), 200));
        }
    }

    // ---------------------------------------------------------------- 只读工具

    private Outcome searchLedger(JsonNode a) {
        int limit = clamp(a.path("limit").asInt(20), 1, 50);
        Page<LedgerEntry> p = ledger.search(query(a), 1, limit, "docDate", false);
        List<Map<String, Object>> items = new ArrayList<>();
        for (LedgerEntry e : p.getContent()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.getId());
            m.put("archiveNo", e.getArchiveNo());
            m.put("title", e.getTitle());
            m.put("department", e.getDepartment());
            m.put("docType", e.getDocType());
            m.put("docDate", str(e.getDocDate()));
            m.put("fileName", e.getNewName());
            m.put("path", e.getRelativePath());
            putIf(m, "contractNo", e.getContractNo());
            putIf(m, "partyA", e.getPartyA());
            putIf(m, "partyB", e.getPartyB());
            putIf(m, "amount", e.getAmount());
            items.add(m);
        }
        return ok(Map.of("total", p.getTotalElements(), "returned", items.size(), "items", items),
                "找到 " + p.getTotalElements() + " 条");
    }

    private Outcome ledgerStats(JsonNode a) {
        String by = text(a, "groupBy", "department");
        Function<LedgerEntry, String> key = switch (by) {
            case "docType" -> LedgerEntry::getDocType;
            case "year" -> e -> e.getDocDate() == null ? "无日期" : String.valueOf(e.getDocDate().getYear());
            case "month" -> e -> e.getDocDate() == null ? "无日期" : e.getDocDate().toString().substring(0, 7);
            case "format" -> LedgerEntry::getFormat;
            default -> LedgerEntry::getDepartment;
        };
        List<LedgerEntry> all = ledger.all(query(a));
        Map<String, Integer> counts = new TreeMap<>();
        for (LedgerEntry e : all) {
            counts.merge(Objects.requireNonNullElse(key.apply(e), "未填写"), 1, Integer::sum);
        }
        return ok(Map.of("groupBy", by, "total", all.size(), "counts", counts), "共 " + all.size() + " 条");
    }

    private Outcome listBatches(JsonNode a) {
        int limit = clamp(a.path("limit").asInt(10), 1, 50);
        List<Map<String, Object>> list = new ArrayList<>();
        for (ArchiveBatch b : batches.findTop50ByOrderByIdDesc().stream().limit(limit).toList()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", b.getId());
            m.put("source", b.getSourcePath());
            m.put("template", b.getTemplateName());
            m.put("status", b.getStatus());
            m.put("createdAt", str(b.getCreatedAt()));
            m.put("files", batchService.stats(b.getId()));
            list.add(m);
        }
        return ok(Map.of("batches", list), list.size() + " 个批次");
    }

    private Outcome listReview(JsonNode a) {
        int limit = clamp(a.path("limit").asInt(30), 1, 100);
        List<FileRecord> all = files.findByStatusOrderByIdDesc(FileRecord.Status.REVIEW);
        List<Map<String, Object>> list = all.stream().limit(limit).map(this::fileBrief).toList();
        return ok(Map.of("total", all.size(), "files", list), all.size() + " 个待确认");
    }

    private Outcome getFile(JsonNode a, boolean allowContent) {
        FileRecord f = file(a.path("fileId").asLong(-1));
        Map<String, Object> m = new LinkedHashMap<>(fileBrief(f));
        m.put("keywords", f.getKeywords());
        m.put("newName", f.getNewName());
        m.put("targetPath", f.getTargetPath());
        m.put("corrected", f.isCorrected());
        putIf(m, "error", f.getErrorMessage());
        if (allowContent) {
            m.put("textExcerpt", AiClient.clip(f.getTextExcerpt(), 1500));
        } else {
            m.put("textExcerpt", "（用户未允许发送正文，只能根据文件名和识别结果判断）");
        }
        return ok(m, f.getOriginalName());
    }

    private Outcome listTemplates() {
        List<Map<String, Object>> list = templates.list().stream()
                .map(t -> Map.<String, Object>of("id", t.getId(), "name", t.getName(), "builtin", t.isBuiltin()))
                .toList();
        return ok(Map.of("templates", list), list.size() + " 个模板");
    }

    private Outcome getTemplate(JsonNode a) {
        TemplateEntity t = template(a);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getId());
        m.put("name", t.getName());
        m.put("builtin", t.isBuiltin());
        m.put("config", templates.parse(t.getContentJson()));
        return ok(m, t.getName());
    }

    // ---------------------------------------------------------------- 需要确认的修改

    private Outcome proposeTemplate(JsonNode a) {
        TemplateEntity entity = template(a);
        String snapshot = entity.getContentJson();
        TemplateConfig base = templates.parse(snapshot);
        TemplateConfig cfg = templates.parse(snapshot);
        List<String> notes = new ArrayList<>();
        applyPatch(cfg, a, notes);

        List<TemplateAnalyzer.Issue> issues = TemplateAnalyzer.lint(cfg);
        List<TemplateAnalyzer.Issue> errors = issues.stream().filter(i -> i.level() == TemplateAnalyzer.Level.ERROR).toList();
        if (!errors.isEmpty()) {
            throw new BizException("修改后的模板有错误，不能保存：" + errors.get(0).message()
                    + (errors.get(0).suggestion() == null ? "" : "（" + errors.get(0).suggestion() + "）"));
        }
        TemplateAnalyzer.Diff diff = TemplateAnalyzer.diff(base, cfg);
        if (diff.total() == 0) {
            throw new BizException("按这些参数修改后模板没有任何变化" + (notes.isEmpty() ? "" : "：" + String.join("；", notes)));
        }
        List<AiActions.Change> changes = changes(diff);

        // 用内置的 10 份示例实际跑一遍，告诉用户修改后的效果
        TemplatePreviewService.Result pv = previews.preview(null, false, cfg, base, null);
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("files", pv.rows().size());
        stats.put("autoBefore", pv.base() == null ? null : pv.base().auto());
        stats.put("autoAfter", pv.target().auto());
        stats.put("reviewBefore", pv.base() == null ? null : pv.base().review());
        stats.put("reviewAfter", pv.target().review());
        stats.put("wrongAutoAfter", pv.target().wrongAuto());
        stats.put("collisionsAfter", pv.target().collisions());
        // 示例：优先列出归档位置变了的文件；都没变（比如只改了关键词）时列前两个
        List<AiActions.Example> examples = new ArrayList<>();
        for (TemplatePreviewService.Row r : pv.rows()) {
            String before = r.base() == null ? r.originalName() : r.base().relativePath();
            if (examples.size() < 4 && !Objects.equals(before, r.target().relativePath())) {
                examples.add(new AiActions.Example(before, r.target().relativePath()));
            }
        }
        // 归档位置都没变（比如只加了关键词）就不列示例，免得看起来像改了文件名
        List<String> warnings = new ArrayList<>(notes);
        issues.stream().filter(i -> i.level() == TemplateAnalyzer.Level.WARNING).limit(3).forEach(i -> warnings.add(i.message()));
        if (pv.target().collisions() > 0) {
            warnings.add("示例文件里有 " + pv.target().collisions() + " 组会重名");
        }
        if (pv.base() != null && pv.target().wrongAuto() > pv.base().wrongAuto()) {
            warnings.add("识别错却会被自动归档的文件从 " + pv.base().wrongAuto() + " 个增加到 " + pv.target().wrongAuto() + " 个");
        }

        String saveAs = text(a, "saveAsName", null);
        boolean asNew = entity.isBuiltin() || saveAs != null && !saveAs.equals(entity.getName());
        String targetName = asNew ? (saveAs != null ? saveAs : entity.getName() + "（小R修改）") : entity.getName();
        Long id = entity.getId();
        String title = asNew ? "另存为新模板“" + targetName + "”" : "修改模板“" + entity.getName() + "”";
        AiActions.View view = actions.add("template", title, text(a, "reason", ""), changes, stats, examples, warnings, () -> {
            if (asNew) {
                TemplateEntity saved = templates.create(unique(targetName), cfg);
                return new AiActions.Result("已保存为新模板“" + saved.getName() + "”。导入时在“模板”下拉框里选择它即可生效",
                        "/template");
            }
            if (!templates.get(id).getContentJson().equals(snapshot)) {
                throw new BizException("模板在这期间被修改过，为避免覆盖，请让小R重新生成方案");
            }
            templates.update(id, null, cfg);
            return new AiActions.Result("模板“" + targetName + "”已更新，之后新导入的批次按新规则处理", "/template");
        });
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("pending", true);
        content.put("note", "已在界面上显示确认卡片，用户点“确认”后才会保存。不要说已经改好了，请简要说明改了什么、效果如何，请用户确认");
        content.put("saveMode", asNew ? "另存为新模板：" + targetName : "覆盖原模板：" + targetName);
        content.put("changes", changes);
        content.put("previewOnSamples", stats);
        content.put("warnings", warnings);
        return new Outcome(json(content), "已生成方案，等你确认", view, null);
    }

    private void applyPatch(TemplateConfig cfg, JsonNode a, List<String> notes) {
        if (a.hasNonNull("namingPattern")) cfg.setNamingPattern(a.get("namingPattern").asText().strip());
        if (a.hasNonNull("pathPattern")) cfg.setPathPattern(a.get("pathPattern").asText().strip());
        if (a.hasNonNull("archiveNoPattern")) cfg.setArchiveNoPattern(a.get("archiveNoPattern").asText().strip());
        if (a.hasNonNull("dateFormat")) cfg.setDateFormat(a.get("dateFormat").asText().strip());
        if (a.hasNonNull("companyName")) cfg.setCompanyName(a.get("companyName").asText().strip());
        if (a.hasNonNull("threshold")) {
            double t = a.get("threshold").asDouble();
            double c = Math.min(Math.max(t, 0.5), 0.95);
            if (c != t) {
                notes.add("阈值 " + t + " 超出合理范围，已调整为 " + c);
            }
            cfg.setThreshold(Math.round(c * 100) / 100.0);
        }
        for (JsonNode n : a.path("removeDepartments")) {
            String name = n.asText().strip();
            if (cfg.getDepartments().removeIf(d -> d.getName().equals(name))) {
                cfg.getKeywordRules().removeIf(r -> name.equals(r.getDepartment()));
            } else {
                notes.add("部门“" + name + "”不存在，忽略删除");
            }
        }
        for (JsonNode n : a.path("addDepartments")) {
            String name = n.path("name").asText("").strip();
            if (name.isEmpty()) continue;
            TemplateConfig.DeptDef d = cfg.findDept(name);
            if (d == null) {
                d = new TemplateConfig.DeptDef();
                d.setName(name);
                cfg.getDepartments().add(d);
            }
            if (n.hasNonNull("code")) d.setCode(n.get("code").asText().strip());
            addAll(d.getAliases(), n.path("aliases"));
        }
        for (JsonNode n : a.path("addDocTypes")) {
            String name = n.path("name").asText("").strip();
            if (name.isEmpty()) continue;
            TemplateConfig.TypeDef t = cfg.findType(name);
            if (t == null) {
                t = new TemplateConfig.TypeDef();
                t.setName(name);
                cfg.getDocTypes().add(t);
            }
            if (n.hasNonNull("code")) t.setCode(n.get("code").asText().strip());
            addAll(t.getSuffixes(), n.path("suffixes"));
            addAll(t.getFeatures(), n.path("features"));
        }
        for (JsonNode n : a.path("addKeywords")) {
            String dept = n.path("department").asText("").strip();
            if (cfg.findDept(dept) == null) {
                throw new BizException("部门“" + dept + "”不在部门词典里，请先用 addDepartments 添加，或者检查部门全称");
            }
            TemplateConfig.KeywordRule rule = cfg.getKeywordRules().stream()
                    .filter(r -> dept.equals(r.getDepartment())).findFirst().orElse(null);
            if (rule == null) {
                rule = new TemplateConfig.KeywordRule();
                rule.setDepartment(dept);
                cfg.getKeywordRules().add(rule);
            }
            addAll(rule.getKeywords(), n.path("keywords"));
        }
        for (JsonNode n : a.path("removeKeywords")) {
            String dept = n.path("department").asText("").strip();
            Set<String> drop = new LinkedHashSet<>();
            n.path("keywords").forEach(k -> drop.add(k.asText().strip()));
            cfg.getKeywordRules().stream().filter(r -> dept.equals(r.getDepartment()))
                    .forEach(r -> r.getKeywords().removeIf(drop::contains));
        }
        cfg.getKeywordRules().removeIf(r -> r.getKeywords() == null || r.getKeywords().isEmpty());
    }

    private static List<AiActions.Change> changes(TemplateAnalyzer.Diff d) {
        List<AiActions.Change> out = new ArrayList<>();
        d.settings().forEach(f -> out.add(new AiActions.Change(f.label(), f.base(), f.target())));
        d.departments().forEach(i -> out.add(new AiActions.Change("部门 " + i.name(), item(i, true), item(i, false))));
        d.docTypes().forEach(i -> out.add(new AiActions.Change("类型 " + i.name(), item(i, true), item(i, false))));
        d.keywords().forEach(k -> out.add(new AiActions.Change("关键词 " + k.department(),
                k.removed().isEmpty() ? "" : "删除 " + String.join("、", k.removed()),
                k.added().isEmpty() ? "" : "新增 " + String.join("、", k.added()))));
        return out;
    }

    private static String item(TemplateAnalyzer.ItemChange i, boolean before) {
        return switch (i.change()) {
            case "ADDED" -> before ? "" : "新增";
            case "REMOVED" -> before ? "原有" : "删除";
            default -> before ? "" : i.detail();
        };
    }

    private Outcome proposeCorrection(JsonNode a) {
        FileRecord f = file(a.path("fileId").asLong(-1));
        if (f.getStatus() == FileRecord.Status.ARCHIVED) {
            throw new BizException("文件已归档，不能修正；需要先在“导入识别”页撤销该批次");
        }
        if (f.getStatus() == FileRecord.Status.QUEUED || f.getStatus() == FileRecord.Status.PARSING) {
            throw new BizException("文件还在处理中，请稍后再试");
        }
        ArchiveBatch b = batches.findById(f.getBatchId()).orElseThrow(() -> new BizException("批次不存在"));
        TemplateConfig tpl = templates.parse(b.getTemplateSnapshot());
        String dept = text(a, "department", null);
        String type = text(a, "docType", null);
        String title = text(a, "title", null);
        LocalDate date = null;
        if (dept != null && tpl.findDept(dept) == null) {
            throw new BizException("部门“" + dept + "”不在这个批次所用的模板里，可选：" + names(tpl.getDepartments(), TemplateConfig.DeptDef::getName));
        }
        if (type != null && tpl.findType(type) == null) {
            throw new BizException("类型“" + type + "”不在模板里，可选：" + names(tpl.getDocTypes(), TemplateConfig.TypeDef::getName));
        }
        if (a.hasNonNull("docDate")) {
            try {
                date = LocalDate.parse(a.get("docDate").asText().strip());
            } catch (DateTimeParseException e) {
                throw new BizException("日期格式应为 yyyy-MM-dd");
            }
        }
        List<AiActions.Change> changes = new ArrayList<>();
        diffField(changes, "部门", f.getDepartment(), dept);
        diffField(changes, "类型", f.getDocType(), type);
        diffField(changes, "日期", str(f.getDocDate()), date == null ? null : date.toString());
        diffField(changes, "标题", f.getTitle(), title);
        if (changes.isEmpty() && f.getStatus() != FileRecord.Status.REVIEW) {
            throw new BizException("识别结果和当前一致，不需要修正");
        }
        Long id = f.getId();
        Long batchId = f.getBatchId();
        String name = f.getOriginalName();
        LocalDate d = date;
        String cardTitle = changes.isEmpty() ? "确认“" + name + "”的识别结果无误" : "修正“" + name + "”的识别结果";
        AiActions.View view = actions.add("correction", cardTitle, text(a, "reason", ""), changes,
                Map.of("batchId", batchId, "fileId", id), List.of(), List.of(), () -> {
                    FileRecord r = batchService.correct(id, dept, type, d, title);
                    return new AiActions.Result("已" + (changes.isEmpty() ? "确认" : "修正") + "，文件状态：" + status(r.getStatus())
                            + "。归档要在“导入识别”页点“预览并归档”", "/import");
                });
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("pending", true);
        content.put("note", "已在界面上显示确认卡片，用户点“确认”后才会生效。不要说已经改好了");
        content.put("changes", changes);
        return new Outcome(json(content), "已生成方案，等你确认", view, null);
    }

    private Outcome exportLedger(JsonNode a) {
        LedgerService.Query q = query(a);
        int count = ledger.all(q).size();
        if (count == 0) {
            return ok(Map.of("count", 0, "note", "没有符合条件的记录，没有生成下载"), "没有符合条件的记录");
        }
        StringBuilder url = new StringBuilder("/api/ledger/export?");
        param(url, "keyword", q.keyword());
        param(url, "department", q.department());
        param(url, "docType", q.docType());
        param(url, "dateFrom", q.dateFrom() == null ? null : q.dateFrom().toString());
        param(url, "dateTo", q.dateTo() == null ? null : q.dateTo().toString());
        Download dl = new Download(url.toString(), "下载台账 Excel（" + count + " 条）");
        return new Outcome(json(Map.of("count", count, "note", "界面上已经显示下载按钮，告诉用户点击下载即可，不要输出链接")),
                count + " 条记录可导出", null, dl);
    }

    // ---------------------------------------------------------------- 工具函数

    private LedgerService.Query query(JsonNode a) {
        return new LedgerService.Query(text(a, "keyword", null), text(a, "department", null), text(a, "docType", null),
                date(a, "dateFrom"), date(a, "dateTo"), null, null, false);
    }

    private static LocalDate date(JsonNode a, String field) {
        String s = text(a, field, null);
        if (s == null) {
            return null;
        }
        try {
            return LocalDate.parse(s.length() == 7 ? s + "-01" : s);
        } catch (DateTimeParseException e) {
            throw new BizException(field + " 日期格式应为 yyyy-MM-dd");
        }
    }

    private static String text(JsonNode a, String field, String def) {
        JsonNode n = a.get(field);
        if (n == null || n.isNull()) {
            return def;
        }
        String s = n.asText().strip();
        return s.isEmpty() ? def : s;
    }

    private FileRecord file(long id) {
        return files.findById(id).orElseThrow(() -> new BizException("文件记录 " + id + " 不存在，可以先用 list_review_files 或 list_batches 查 id"));
    }

    private TemplateEntity template(JsonNode a) {
        return a.hasNonNull("templateId") ? templates.get(a.get("templateId").asLong()) : templates.getOrDefault(null);
    }

    private Map<String, Object> fileBrief(FileRecord f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", f.getId());
        m.put("batchId", f.getBatchId());
        m.put("fileName", f.getOriginalName());
        m.put("status", status(f.getStatus()));
        m.put("department", f.getDepartment());
        m.put("docType", f.getDocType());
        m.put("docDate", str(f.getDocDate()));
        m.put("title", f.getTitle());
        m.put("confidence", f.getOverallConf() == null ? null : Math.round(f.getOverallConf() * 100) + "%");
        putIf(m, "reviewReasons", f.getReviewReasons());
        return m;
    }

    private String unique(String name) {
        Set<String> used = new LinkedHashSet<>();
        templates.list().forEach(t -> used.add(t.getName()));
        String n = name;
        for (int i = 2; used.contains(n); i++) {
            n = name + i;
        }
        return n;
    }

    private static String status(FileRecord.Status s) {
        return switch (s) {
            case QUEUED -> "排队中";
            case PARSING -> "识别中";
            case READY -> "待归档";
            case REVIEW -> "待确认";
            case ARCHIVED -> "已归档";
            case UNDONE -> "已撤销";
            case FAILED -> "失败";
            case DUPLICATE -> "重复";
        };
    }

    private static void diffField(List<AiActions.Change> out, String label, String before, String after) {
        if (after != null && !after.equals(before)) {
            out.add(new AiActions.Change(label, before == null ? "" : before, after));
        }
    }

    private static <T> String names(List<T> list, Function<T, String> name) {
        return String.join("、", list.stream().map(name).toList());
    }

    private static void addAll(List<String> target, JsonNode values) {
        for (JsonNode v : values) {
            String s = v.asText().strip();
            if (!s.isEmpty() && !target.contains(s)) {
                target.add(s);
            }
        }
    }

    private static void param(StringBuilder url, String key, String value) {
        if (value != null && !value.isBlank()) {
            url.append(key).append('=').append(URLEncoder.encode(value, StandardCharsets.UTF_8)).append('&');
        }
    }

    private static void putIf(Map<String, Object> m, String key, String value) {
        if (value != null && !value.isBlank()) {
            m.put(key, value);
        }
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.min(Math.max(v, lo), hi);
    }

    private Outcome ok(Object data, String summary) {
        return new Outcome(json(data), summary, null, null);
    }

    private Outcome error(String message) {
        return new Outcome(json(Map.of("error", message)), "失败：" + AiClient.clip(message, 40), null, null);
    }

    /** 工具结果转成 JSON 给模型；太长时截断，避免超出上下文 */
    private String json(Object data) {
        String s;
        try {
            s = mapper.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            s = String.valueOf(data);
        }
        return s.length() <= MAX_RESULT_CHARS ? s : s.substring(0, MAX_RESULT_CHARS) + "…（结果太长已截断，请缩小查询范围）";
    }
}
