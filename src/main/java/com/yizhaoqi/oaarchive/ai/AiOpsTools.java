package com.yizhaoqi.oaarchive.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yizhaoqi.oaarchive.archive.ArchiveService;
import com.yizhaoqi.oaarchive.common.BizException;
import com.yizhaoqi.oaarchive.config.OaProperties;
import com.yizhaoqi.oaarchive.fileops.FileOpsService;
import com.yizhaoqi.oaarchive.fileops.FileScopeService;
import com.yizhaoqi.oaarchive.model.ArchiveBatch;
import com.yizhaoqi.oaarchive.model.FileRecord;
import com.yizhaoqi.oaarchive.model.TemplateEntity;
import com.yizhaoqi.oaarchive.pipeline.BatchService;
import com.yizhaoqi.oaarchive.repository.ArchiveBatchRepository;
import com.yizhaoqi.oaarchive.repository.FileRecordRepository;
import com.yizhaoqi.oaarchive.repository.LedgerEntryRepository;
import com.yizhaoqi.oaarchive.repository.OperationLogRepository;
import com.yizhaoqi.oaarchive.rollback.ProjectOps;
import com.yizhaoqi.oaarchive.rollback.RollbackService;
import com.yizhaoqi.oaarchive.template.TemplateService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 小R 的操作类工具：文件管理（查看、新建、改名、移动、删除）和项目内操作（导入、归档、撤销、删除记录、删除模板、重试、回档）。
 * 只读工具直接返回结果；其余工具只生成确认卡片，用户点“确认”后才通过 {@link ProjectOps} / {@link FileOpsService} 执行，
 * 执行时登记回档记录，误操作可以在顶栏“回档”里撤回。
 */
@Component
public class AiOpsTools {

    private static final String SOURCE = "AI";
    private static final String ROLLBACK_HINT = "如果是误操作，可以点顶栏的“回档”撤回这一步（只保留最近一次操作）";

    private final ObjectMapper mapper;
    private final AiClient client;
    private final AiActions actions;
    private final FileOpsService fileOps;
    private final FileScopeService scope;
    private final ProjectOps ops;
    private final RollbackService rollback;
    private final ArchiveService archive;
    private final ArchiveBatchRepository batches;
    private final FileRecordRepository files;
    private final LedgerEntryRepository ledger;
    private final OperationLogRepository opLogs;
    private final BatchService batchService;
    private final TemplateService templates;
    private final OaProperties props;

    public AiOpsTools(ObjectMapper mapper, AiClient client, AiActions actions, FileOpsService fileOps, FileScopeService scope,
                      ProjectOps ops, RollbackService rollback, ArchiveService archive, ArchiveBatchRepository batches,
                      FileRecordRepository files, LedgerEntryRepository ledger, OperationLogRepository opLogs,
                      BatchService batchService, TemplateService templates, OaProperties props) {
        this.mapper = mapper;
        this.client = client;
        this.actions = actions;
        this.fileOps = fileOps;
        this.scope = scope;
        this.ops = ops;
        this.rollback = rollback;
        this.archive = archive;
        this.batches = batches;
        this.files = files;
        this.ledger = ledger;
        this.opLogs = opLogs;
        this.batchService = batchService;
        this.templates = templates;
        this.props = props;
    }

    /** 界面上工具标签的文字；不是这里的工具时返回 null */
    static String label(String name) {
        return switch (name) {
            case "list_folder" -> "查看文件夹";
            case "list_batch_files" -> "查看批次文件";
            case "get_file_scope" -> "查看操作范围";
            case "get_last_rollback" -> "查看回档记录";
            case "propose_grant_folder" -> "申请文件夹权限";
            case "propose_revoke_folder" -> "准备撤销文件夹权限";
            case "propose_create_folder" -> "准备新建文件夹";
            case "propose_rename" -> "准备改名";
            case "propose_move" -> "准备移动";
            case "propose_delete" -> "准备删除";
            case "propose_import_folder" -> "准备导入";
            case "propose_archive" -> "准备归档";
            case "propose_undo_archive" -> "准备撤销归档";
            case "propose_delete_batch" -> "准备删除批次记录";
            case "propose_remove_file" -> "准备移除文件";
            case "propose_delete_template" -> "准备删除模板";
            case "propose_retry_file" -> "准备重新识别";
            case "propose_rollback" -> "准备回档";
            default -> null;
        };
    }

    void addDefinitions(ArrayNode t) {
        String reason = "\"reason\":{\"type\":\"string\",\"description\":\"为什么这样做，显示给用户\"}";
        t.add(def("list_folder", "查看文件夹里的文件和子文件夹（最多 200 项）。只能查看允许操作范围内的文件夹，"
                + "不知道范围时先调用 get_file_scope。", """
                {"path":{"type":"string","description":"文件夹的完整路径"}}"""));
        t.add(def("list_batch_files", "列出一个导入批次里的全部文件：id、文件名、状态、识别出的部门/类型/日期/标题，"
                + "以及归档后的位置（还没归档的文件按当前模板预计的位置）。要按部门、类型挑文件归档或撤销时先调用它，"
                + "不要逐个调用 get_file。", """
                {"batchId":{"type":"integer"},
                 "status":{"type":"string","enum":["READY","REVIEW","ARCHIVED","UNDONE","FAILED","DUPLICATE"],"description":"只看这个状态，不填看全部"}}"""));
        t.add(def("get_file_scope", "查看允许小R操作的文件夹范围（归档目录、导入过的文件夹、用户手动添加的文件夹）。", "{}"));
        t.add(def("get_last_rollback", "查看最近一次可以回档的操作（只保留最近一次）。", "{}"));
        t.add(def("propose_grant_folder", "申请把一个文件夹加入允许操作的范围，生成确认卡片，用户确认后才生效，之后一直有效，"
                + "用户可以在设置里移除。只在用户明确要求操作某个范围外的文件夹时使用；系统目录、程序数据目录、整个磁盘、"
                + "用户主目录本身会被拒绝，不要尝试。尽量申请具体的子文件夹，不要申请大的上级目录。", """
                {"path":{"type":"string","description":"要授权的文件夹完整路径"},%s}""".formatted(reason)));
        t.add(def("propose_revoke_folder", "生成撤销对话授权的确认卡片，把之前在对话里授权的文件夹移出范围。", """
                {"path":{"type":"string","description":"要撤销授权的文件夹完整路径"},%s}""".formatted(reason)));
        t.add(def("propose_create_folder", "生成新建文件夹的确认卡片。", """
                {"parent":{"type":"string","description":"在哪个文件夹里新建（完整路径）"},
                 "name":{"type":"string","description":"新文件夹名称，可以用 / 一次建多级"},%s}""".formatted(reason)));
        t.add(def("propose_rename", "生成给文件或文件夹改名的确认卡片。已归档、记在台账里的文件不能改名。", """
                {"path":{"type":"string","description":"要改名的文件或文件夹的完整路径"},
                 "newName":{"type":"string","description":"新名字（只是名字，不含路径；文件要带扩展名）"},%s}""".formatted(reason)));
        t.add(def("propose_move", "生成把文件或文件夹移动到另一个文件夹的确认卡片。目标里有同名的会拒绝，不会覆盖。", """
                {"paths":{"type":"array","items":{"type":"string"},"description":"要移动的完整路径，最多 200 个"},
                 "targetDir":{"type":"string","description":"目标文件夹（完整路径，必须已存在）"},%s}""".formatted(reason)));
        t.add(def("propose_delete", "生成删除文件或文件夹的确认卡片。删除的内容放进系统回收站，也可以回档恢复。"
                + "已归档、记在台账里的文件不能删除，要先撤销归档。", """
                {"paths":{"type":"array","items":{"type":"string"},"description":"要删除的完整路径，最多 200 个"},%s}""".formatted(reason)));
        t.add(def("propose_import_folder", "生成导入文件夹的确认卡片：确认后程序会复制里面的文件并识别，原文件不变。", """
                {"folder":{"type":"string","description":"要导入的文件夹完整路径"},
                 "targetRoot":{"type":"string","description":"归档目录，不填用上次导入用的归档目录"},
                 "templateId":{"type":"integer","description":"模板 id，不填用上次导入用的模板"},%s}""".formatted(reason)));
        t.add(def("propose_archive", "生成归档一个批次的确认卡片：按模板改名，放到归档目录，写进台账。默认只归档“待归档”的文件。", """
                {"batchId":{"type":"integer"},
                 "fileIds":{"type":"array","items":{"type":"integer"},"description":"只归档这些文件，不填则归档整个批次"},
                 "includeReview":{"type":"boolean","description":"是否连“待确认”的文件一起归档，默认 false，只有用户明确要求时才设为 true"},%s}"""
                .formatted(reason)));
        t.add(def("propose_undo_archive", "生成撤销一个批次归档的确认卡片：删除归档目录里这个批次的文件（被修改过的保留），台账里去掉记录。", """
                {"batchId":{"type":"integer"},%s}""".formatted(reason)));
        t.add(def("propose_delete_batch", "生成删除批次记录的确认卡片：删除导入记录和识别结果，不删除原文件和已归档的文件。", """
                {"batchId":{"type":"integer"},%s}""".formatted(reason)));
        t.add(def("propose_remove_file", "生成从批次中移除一个还没归档的文件记录的确认卡片（不删除原文件）。", """
                {"fileId":{"type":"integer"},%s}""".formatted(reason)));
        t.add(def("propose_delete_template", "生成删除模板的确认卡片。内置模板不能删除。", """
                {"templateId":{"type":"integer"},%s}""".formatted(reason)));
        t.add(def("propose_retry_file", "生成重新识别一个处理失败的文件的确认卡片。", """
                {"fileId":{"type":"integer"},%s}""".formatted(reason)));
        t.add(def("propose_rollback", "生成回档的确认卡片：把最近一次操作撤回到操作之前。只能回档最近一次，回档后记录清空。", """
                {%s}""".formatted(reason)));
    }

    /** 执行工具；不是这里的工具时返回 null */
    AiTools.Outcome run(String name, JsonNode a) {
        return switch (name) {
            case "list_folder" -> listFolder(a);
            case "list_batch_files" -> listBatchFiles(a);
            case "get_file_scope" -> fileScope();
            case "get_last_rollback" -> lastRollback();
            case "propose_grant_folder" -> proposeGrant(a);
            case "propose_revoke_folder" -> proposeRevoke(a);
            case "propose_create_folder" -> proposeMkdir(a);
            case "propose_rename" -> proposeRename(a);
            case "propose_move" -> proposeMove(a);
            case "propose_delete" -> proposeDelete(a);
            case "propose_import_folder" -> proposeImport(a);
            case "propose_archive" -> proposeArchive(a);
            case "propose_undo_archive" -> proposeUndo(a);
            case "propose_delete_batch" -> proposeDeleteBatch(a);
            case "propose_remove_file" -> proposeRemoveFile(a);
            case "propose_delete_template" -> proposeDeleteTemplate(a);
            case "propose_retry_file" -> proposeRetry(a);
            case "propose_rollback" -> proposeRollback(a);
            default -> null;
        };
    }

    // ---------------------------------------------------------------- 只读

    private AiTools.Outcome listFolder(JsonNode a) {
        FileOpsService.Listing l = fileOps.list(text(a, "path"));
        return ok(l, l.total() + " 项");
    }

    private AiTools.Outcome listBatchFiles(JsonNode a) {
        ArchiveBatch b = batch(a);
        String status = optText(a, "status");
        // 预计的归档位置：包括待确认、重复的文件，这样挑文件时也能看到它们会放到哪里
        Map<Long, String> planned = new LinkedHashMap<>();
        for (ArchiveService.PreviewItem p : archive.preview(b.getId(), true)) {
            planned.put(p.fileId(), p.relativePath());
        }
        List<Map<String, Object>> list = new ArrayList<>();
        for (FileRecord f : files.findByBatchIdOrderByIdAsc(b.getId())) {
            if (status != null && !f.getStatus().name().equals(status)) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", f.getId());
            m.put("fileName", f.getOriginalName());
            m.put("status", f.getStatus().name());
            m.put("department", f.getDepartment());
            m.put("docType", f.getDocType());
            m.put("docDate", f.getDocDate() == null ? null : f.getDocDate().toString());
            m.put("title", f.getTitle());
            String where = f.getStatus() == FileRecord.Status.ARCHIVED ? f.getTargetPath() : planned.get(f.getId());
            if (where != null) {
                m.put(f.getStatus() == FileRecord.Status.ARCHIVED ? "archivedAt" : "plannedPath", where);
            }
            list.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("batchId", b.getId());
        out.put("archiveRoot", b.getTargetRoot());
        out.put("template", b.getTemplateName());
        out.put("total", list.size());
        out.put("files", list);
        return ok(out, list.size() + " 个文件");
    }

    private AiTools.Outcome fileScope() {
        FileScopeService.View v = scope.view();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("roots", v.effective());
        m.put("includeArchiveRoots", v.archiveRoots());
        m.put("includeImportFolders", v.importFolders());
        m.put("extraRoots", v.extraRoots());
        m.put("grantedInChat", v.aiRoots());
        m.put("note", "只能在 roots 列出的文件夹里新建、改名、移动、删除。范围外的文件夹可以用 propose_grant_folder 申请授权"
                + "（用户确认后生效）；系统目录、程序数据目录、整个磁盘、用户主目录本身不能授权。用户也可以在“连接设置 → 文件操作范围”里调整");
        return ok(m, v.effective().size() + " 个文件夹");
    }

    private AiTools.Outcome lastRollback() {
        RollbackService.Entry e = rollback.latest();
        if (e == null) {
            return ok(Map.of("none", true, "note", "目前没有可以回档的操作"), "没有回档记录");
        }
        return ok(e, e.title());
    }

    // ---------------------------------------------------------------- 文件夹权限（v1.7）

    private AiTools.Outcome proposeGrant(JsonNode a) {
        String raw = text(a, "path");
        FileScopeService.GrantCheck c = scope.checkGrant(raw);
        if (c.coveredBy() != null) {
            return ok(Map.of("alreadyAllowed", true, "path", c.path(), "coveredBy", c.coveredBy(),
                    "note", "这个文件夹已经在允许的范围里，不用再授权，可以直接操作"), "已在范围内");
        }
        List<String> lines = List.of("文件夹：" + c.path(),
                "授权后小R可以在这个文件夹里新建、改名、移动、删除（每次操作仍然要你确认，删除的放进回收站）",
                "一直有效，可以在“连接设置 → 文件操作范围”里随时移除，或者让小R撤销");
        List<String> warnings = List.of("已归档、记在台账里的文件仍然不能直接改动");
        FileOpsService.Preview p = new FileOpsService.Preview("授权小R操作“" + last(c.path()) + "”", lines, warnings, true, 0, 0);
        return card("grant", p, a, () -> {
            FileScopeService.GrantCheck g = scope.grant(raw);
            String msg = g.coveredBy() != null ? "这个文件夹已经在允许的范围里" : "已授权：" + g.path() + "。可以在“连接设置 → 文件操作范围”里随时移除";
            return new AiActions.Result(msg, null, false);
        });
    }

    private AiTools.Outcome proposeRevoke(JsonNode a) {
        String raw = text(a, "path");
        String path = FileScopeService.real(java.nio.file.Path.of(raw.strip())).toString();
        if (scope.view().aiRoots().stream().noneMatch(x -> java.nio.file.Path.of(x).equals(java.nio.file.Path.of(path)))) {
            scope.revoke(raw); // 不是对话授权的文件夹时，这里会抛出带原因的错误
        }
        FileOpsService.Preview p = new FileOpsService.Preview("撤销对“" + last(path) + "”的授权",
                List.of("文件夹：" + path, "撤销后小R不能再操作这个文件夹，里面的文件不受影响"), List.of(), false, 0, 0);
        return card("revoke", p, a, () -> new AiActions.Result("已撤销授权：" + scope.revoke(raw), null, false));
    }

    // ---------------------------------------------------------------- 文件操作

    private AiTools.Outcome proposeMkdir(JsonNode a) {
        String parent = text(a, "parent");
        String name = text(a, "name");
        FileOpsService.Preview p = fileOps.previewMkdir(parent, name);
        return card("fileop", p, a, () -> {
            String path = fileOps.applyMkdir(parent, name, SOURCE);
            return done("已新建文件夹 " + path, null);
        });
    }

    private AiTools.Outcome proposeRename(JsonNode a) {
        String path = text(a, "path");
        String newName = text(a, "newName");
        FileOpsService.Preview p = fileOps.previewRename(path, newName);
        return card("fileop", p, a, () -> {
            String to = fileOps.applyRename(path, newName, SOURCE);
            return done("已改名为 " + to, null);
        });
    }

    private AiTools.Outcome proposeMove(JsonNode a) {
        List<String> paths = list(a, "paths");
        String target = text(a, "targetDir");
        FileOpsService.Preview p = fileOps.previewMove(paths, target);
        return card("fileop", p, a, () -> done(fileOps.applyMove(paths, target, SOURCE), null));
    }

    private AiTools.Outcome proposeDelete(JsonNode a) {
        List<String> paths = list(a, "paths");
        FileOpsService.Preview p = fileOps.previewDelete(paths);
        return card("delete", p, a, () -> done(fileOps.applyDelete(paths, SOURCE), null));
    }

    // ---------------------------------------------------------------- 项目内操作

    private AiTools.Outcome proposeImport(JsonNode a) {
        String folder = scope.checkImportSource(text(a, "folder")).toString();
        String targetRoot = optText(a, "targetRoot");
        Long templateId = a.hasNonNull("templateId") ? a.get("templateId").asLong() : batchService.prefs().templateId();
        TemplateEntity tpl = templates.getOrDefault(templateId);
        String root = targetRoot != null ? targetRoot : batchService.prefs().archiveRoot();
        String missing = com.yizhaoqi.oaarchive.pipeline.ImportPrefsService.unavailableReason(root);
        if (missing != null) {
            throw new BizException(missing);
        }
        List<String> lines = List.of("文件夹：" + folder, "模板：" + tpl.getName(), "归档目录：" + root,
                "程序会复制里面的文件再识别，原文件不变；识别完成后还要再归档");
        FileOpsService.Preview p = new FileOpsService.Preview("导入文件夹“" + last(folder) + "”", lines, List.of(), false, 0, 0);
        return card("import", p, a, () -> {
            ArchiveBatch b = ops.importFolder(folder, targetRoot, tpl.getId(), SOURCE);
            return done("已导入，批次 #" + b.getId() + "，正在识别", "/import");
        });
    }

    private AiTools.Outcome proposeArchive(JsonNode a) {
        ArchiveBatch b = batch(a);
        if (batchService.isRunning(b.getId())) {
            throw new BizException("批次 #" + b.getId() + " 还在识别中，请等处理完成后再归档");
        }
        boolean includeReview = a.path("includeReview").asBoolean(false);
        Set<Long> only = new LinkedHashSet<>();
        a.path("fileIds").forEach(n -> only.add(n.asLong()));
        List<ArchiveService.PreviewItem> items = archive.preview(b.getId(), includeReview).stream()
                .filter(i -> only.isEmpty() || only.contains(i.fileId())).toList();
        if (items.isEmpty()) {
            throw new BizException("批次 #" + b.getId() + " 没有可以归档的文件" + (includeReview ? "" : "（“待确认”的文件要先确认，或者用户明确要求时设 includeReview=true）"));
        }
        List<String> lines = new ArrayList<>();
        lines.add("归档目录：" + b.getTargetRoot());
        lines.add("共 " + items.size() + " 个文件，按模板“" + b.getTemplateName() + "”改名后放进归档目录，并写进台账");
        items.stream().limit(5).forEach(i -> lines.add(i.originalName() + " → " + i.relativePath()));
        if (items.size() > 5) {
            lines.add("……");
        }
        List<String> warnings = new ArrayList<>();
        long conflicts = items.stream().filter(i -> i.conflict() != null).count();
        if (conflicts > 0) {
            warnings.add(conflicts + " 个文件有重名或重复，归档时会自动加序号或跳过");
        }
        long review = items.stream().filter(i -> "REVIEW".equals(i.status())).count();
        if (review > 0) {
            warnings.add("包含 " + review + " 个“待确认”的文件，它们的识别结果没有人工核对过");
        }
        FileOpsService.Preview p = new FileOpsService.Preview("归档批次 #" + b.getId(), lines, warnings,
                items.size() > FileOpsService.DANGER_FILES, items.size(), 0);
        List<Long> fileIds = only.isEmpty() ? null : new ArrayList<>(only);
        return card("archive", p, a, () -> {
            ArchiveService.ArchiveSummary s = ops.archive(b.getId(), fileIds, includeReview, SOURCE);
            return done("归档了 " + s.archived() + " 个文件" + (s.skipped() > 0 ? "，跳过 " + s.skipped() + " 个" : "")
                    + (s.failed() > 0 ? "，失败 " + s.failed() + " 个" : ""), "/ledger");
        });
    }

    private AiTools.Outcome proposeUndo(JsonNode a) {
        ArchiveBatch b = batch(a);
        int n = opLogs.findByBatchIdAndUndoneAtIsNullOrderByIdDesc(b.getId()).size();
        if (n == 0) {
            throw new BizException("批次 #" + b.getId() + " 没有可以撤销的归档");
        }
        List<String> lines = List.of("删除归档目录里这个批次的 " + n + " 个文件（移动归档的放回原位置；归档后被修改过的保留）",
                "台账里去掉这些记录，文件恢复为“已撤销”，可以修正后重新归档");
        FileOpsService.Preview p = new FileOpsService.Preview("撤销批次 #" + b.getId() + " 的归档", lines, List.of(), true, n, 0);
        return card("undo", p, a, () -> {
            ArchiveService.UndoSummary s = ops.undo(b.getId(), SOURCE);
            return done("撤销了 " + s.undone() + " 个文件的归档" + (s.kept() > 0 ? "，" + s.kept() + " 个保留" : ""), "/import");
        });
    }

    private AiTools.Outcome proposeDeleteBatch(JsonNode a) {
        ArchiveBatch b = batch(a);
        if (batchService.isRunning(b.getId())) {
            throw new BizException("批次 #" + b.getId() + " 还在识别中，请等处理完成后再删除");
        }
        int n = files.findByBatchIdOrderByIdAsc(b.getId()).size();
        long kept = ledger.countByBatchIdAndUndoneFalse(b.getId());
        List<String> lines = List.of("来源：" + b.getSourcePath(), "删除 " + n + " 个文件的导入记录和识别结果",
                "原文件和已归档的文件不会删除");
        List<String> warnings = kept > 0
                ? List.of("这个批次有 " + kept + " 个已归档的文件，台账记录保留，但删除后不能再撤销这次归档")
                : List.of();
        FileOpsService.Preview p = new FileOpsService.Preview("删除批次 #" + b.getId() + " 的记录", lines, warnings, true, n, 0);
        return card("deleteBatch", p, a, () -> done(ops.deleteBatch(b.getId(), SOURCE).message(), "/import"));
    }

    private AiTools.Outcome proposeRemoveFile(JsonNode a) {
        FileRecord f = file(a);
        if (f.getStatus() == FileRecord.Status.ARCHIVED) {
            throw new BizException("文件已归档，不能移除；需要先撤销该批次的归档");
        }
        FileOpsService.Preview p = new FileOpsService.Preview("从批次 #" + f.getBatchId() + " 移除“" + f.getOriginalName() + "”",
                List.of("删除这个文件的导入记录和识别结果，原文件不会删除"), List.of(), false, 0, 0);
        return card("removeFile", p, a, () -> {
            ops.deleteFile(f.getId(), SOURCE);
            return done("已移除“" + f.getOriginalName() + "”", "/import");
        });
    }

    private AiTools.Outcome proposeDeleteTemplate(JsonNode a) {
        if (!a.hasNonNull("templateId")) {
            throw new BizException("请提供 templateId，可以先用 list_templates 查");
        }
        TemplateEntity t = templates.get(a.get("templateId").asLong());
        if (t.isBuiltin()) {
            throw new BizException("内置模板不能删除");
        }
        FileOpsService.Preview p = new FileOpsService.Preview("删除模板“" + t.getName() + "”",
                List.of("已经导入的批次用的是导入时的模板副本，不受影响"), List.of(), true, 0, 0);
        return card("deleteTemplate", p, a, () -> {
            ops.deleteTemplate(t.getId(), SOURCE);
            return done("已删除模板“" + t.getName() + "”", "/template");
        });
    }

    private AiTools.Outcome proposeRetry(JsonNode a) {
        FileRecord f = file(a);
        if (f.getStatus() != FileRecord.Status.FAILED) {
            throw new BizException("只有处理失败的文件可以重新识别");
        }
        FileOpsService.Preview p = new FileOpsService.Preview("重新识别“" + f.getOriginalName() + "”",
                List.of("失败原因：" + (f.getErrorMessage() == null ? "未知" : f.getErrorMessage())), List.of(), false, 0, 0);
        return card("retry", p, a, () -> {
            ops.retry(f.getId(), SOURCE);
            return done("已重新提交识别", "/import");
        });
    }

    private AiTools.Outcome proposeRollback(JsonNode a) {
        RollbackService.Entry e = rollback.latest();
        if (e == null) {
            throw new BizException("目前没有可以回档的操作");
        }
        if (!e.canRollback()) {
            throw new BizException("最近一次操作“" + e.title() + "”不能回档：" + e.reason());
        }
        List<String> lines = new ArrayList<>();
        lines.add("操作：" + e.title() + "（" + e.at() + "）");
        e.undo().forEach(u -> lines.add("回档会" + u));
        FileOpsService.Preview p = new FileOpsService.Preview("回档：" + e.title(), lines, e.warnings(), false, 0, 0);
        String id = e.id();
        AiTools.Outcome o = card("rollback", p, a, () -> {
            RollbackService.Result r = rollback.rollback(id);
            String notes = r.notes().isEmpty() ? "" : "。" + String.join("；", r.notes());
            return new AiActions.Result(r.message() + notes, null, false);
        });
        return o;
    }

    // ---------------------------------------------------------------- 工具函数

    private AiTools.Outcome card(String kind, FileOpsService.Preview p, JsonNode a, Supplier<AiActions.Result> apply) {
        List<AiActions.Change> changes = p.lines().stream().map(l -> new AiActions.Change("", "", l)).toList();
        AiActions.View view = actions.add(kind, p.title(), optText(a, "reason") == null ? "" : optText(a, "reason"), changes,
                FileOpsService.stats(p), List.of(), p.warnings(), p.danger(), apply);
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("pending", true);
        content.put("note", "已在界面上显示确认卡片，用户点“确认”后才会执行。不要说已经做完了，简要说明会做什么，请用户确认"
                + (p.danger() ? "；这是高风险操作，界面上需要用户再确认一次" : ""));
        content.put("title", p.title());
        content.put("details", p.lines());
        if (!p.warnings().isEmpty()) {
            content.put("warnings", p.warnings());
        }
        return new AiTools.Outcome(json(content), "已生成方案，等你确认", view, null);
    }

    private static AiActions.Result done(String message, String link) {
        return new AiActions.Result(message + "。" + ROLLBACK_HINT, link, true);
    }

    private ArchiveBatch batch(JsonNode a) {
        long id = a.path("batchId").asLong(-1);
        return batches.findById(id).orElseThrow(() -> new BizException("批次 " + id + " 不存在，可以先用 list_batches 查 id"));
    }

    private FileRecord file(JsonNode a) {
        long id = a.path("fileId").asLong(-1);
        return files.findById(id).orElseThrow(() -> new BizException("文件记录 " + id + " 不存在，可以先用 list_batches 或 list_review_files 查 id"));
    }

    private static String text(JsonNode a, String field) {
        String s = optText(a, field);
        if (s == null) {
            throw new BizException("缺少参数 " + field);
        }
        return s;
    }

    private static String optText(JsonNode a, String field) {
        JsonNode n = a.get(field);
        if (n == null || n.isNull()) {
            return null;
        }
        String s = n.asText().strip();
        return s.isEmpty() ? null : s;
    }

    /** 数组参数；模型偶尔会传单个字符串，也接受 */
    private static List<String> list(JsonNode a, String field) {
        JsonNode n = a.get(field);
        List<String> out = new ArrayList<>();
        if (n != null && n.isArray()) {
            n.forEach(x -> {
                if (!x.asText().isBlank()) {
                    out.add(x.asText().strip());
                }
            });
        } else if (n != null && n.isTextual() && !n.asText().isBlank()) {
            out.add(n.asText().strip());
        }
        if (out.isEmpty()) {
            throw new BizException("缺少参数 " + field);
        }
        return out;
    }

    private static String last(String path) {
        String s = path.replace('\\', '/');
        return s.endsWith("/") ? s : s.substring(s.lastIndexOf('/') + 1);
    }

    private ObjectNode def(String name, String description, String properties) {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        try {
            schema.set("properties", mapper.readTree(properties));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("工具定义 JSON 有误：" + name, e);
        }
        return client.toolWithSchema(name, description, schema);
    }

    private AiTools.Outcome ok(Object data, String summary) {
        return new AiTools.Outcome(json(data), summary, null, null);
    }

    private String json(Object data) {
        try {
            String s = mapper.writeValueAsString(data);
            return s.length() <= 8000 ? s : s.substring(0, 8000) + "…（结果太长已截断）";
        } catch (JsonProcessingException e) {
            return String.valueOf(data);
        }
    }
}
