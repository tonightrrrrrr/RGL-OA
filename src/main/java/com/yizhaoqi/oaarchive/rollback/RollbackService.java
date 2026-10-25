package com.yizhaoqi.oaarchive.rollback;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.oaarchive.common.BizException;
import com.yizhaoqi.oaarchive.common.Hashes;
import com.yizhaoqi.oaarchive.config.OaProperties;
import com.yizhaoqi.oaarchive.ledger.LedgerExporter;
import com.yizhaoqi.oaarchive.pipeline.BatchService;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * 回档：把最近一次操作撤回到操作之前的状态。只保留一次——有新的操作就覆盖。
 * <p>
 * 每次可回档的操作执行前：
 * <ul>
 *   <li>把涉及的批次、模板在数据库里的行复制到快照表（rb_*）；</li>
 *   <li>记录磁盘上要发生的变化（新建、移动、删除），被删除或会被删除的文件先复制一份放到 data/rollback/held。</li>
 * </ul>
 * 回档时先把磁盘恢复，再把这些批次、模板的数据库行换回快照里的样子，最后重新生成文件目录台账。
 * 快照先写到 rbn_* 和 next 目录，操作成功后才替换上一次的记录，操作失败不会弄丢上一次的回档记录。
 */
@Service
public class RollbackService {

    private static final Logger log = LoggerFactory.getLogger(RollbackService.class);
    /** 删除时复制备份的总大小上限，超过时这次删除只能从回收站找回 */
    public static final long HOLD_LIMIT = 2L * 1024 * 1024 * 1024;

    /** 按批次快照的表，和批次的关联字段 */
    private static final List<String[]> BATCH_TABLES = List.of(
            new String[] {"archive_batch", "id"},
            new String[] {"file_record", "batch_id"},
            new String[] {"operation_log", "batch_id"},
            new String[] {"ledger_entry", "batch_id"});

    /** 界面上显示的回档记录 */
    public record Entry(String id, String kind, String title, String source, LocalDateTime at, List<String> done,
                        List<String> undo, List<String> warnings, boolean canRollback, String reason) {
    }

    public record Result(String message, List<String> notes) {
    }

    /** 磁盘上的一步变化，回档时倒序撤回 */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Step {
        /** MKDIR、MOVE（含改名）、DELETE、HOLD_WORK、HOLD_UNDO、ARCHIVE_AFTER、IMPORT_WORK */
        private String type;
        private String from;
        private String to;
        private String held;
        private String sha;
        private boolean move;
        private Long batchId;
        private Long opId;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Journal {
        private String id;
        private String kind;
        private String title;
        private String source;
        private LocalDateTime at;
        private List<String> done = new ArrayList<>();
        private List<String> undo = new ArrayList<>();
        private List<String> warnings = new ArrayList<>();
        private boolean canRollback = true;
        private String reason;
        private List<Long> batchIds = new ArrayList<>();
        private List<Long> templateIds = new ArrayList<>();
        private boolean dbSnapshot;
        private List<Step> steps = new ArrayList<>();
    }

    /** 操作执行时用来登记磁盘变化 */
    public final class Recorder {
        private final Journal j;
        private final Path held;
        private boolean skipped;

        private Recorder(Journal j, Path held) {
            this.j = j;
            this.held = held;
        }

        public void addBatch(Long id) {
            if (id != null && !j.getBatchIds().contains(id)) {
                j.getBatchIds().add(id);
            }
        }

        public void addTemplate(Long id) {
            if (id != null && !j.getTemplateIds().contains(id)) {
                j.getTemplateIds().add(id);
            }
        }

        public void done(String text) {
            j.getDone().add(text);
        }

        /** 这次操作实际上什么都没改（比如没有可归档的文件）：不记录，保留上一次的回档记录 */
        public void skip() {
            skipped = true;
        }

        public void warn(String text) {
            j.getWarnings().add(text);
        }

        /** 回档时这一项不能自动恢复 */
        public void cannotRollback(String reason) {
            j.setCanRollback(false);
            j.setReason(reason);
        }

        public void mkdir(Path p) {
            Step s = new Step();
            s.setType("MKDIR");
            s.setTo(p.toString());
            j.getSteps().add(s);
        }

        public void moved(Path from, Path to) {
            Step s = new Step();
            s.setType("MOVE");
            s.setFrom(from.toString());
            s.setTo(to.toString());
            j.getSteps().add(s);
        }

        /** 删除前复制一份，回档时从这里还原；返回 false 表示这次没有备份（太大） */
        public boolean holdForDelete(Path p, boolean keepCopy) throws IOException {
            Step s = new Step();
            s.setType("DELETE");
            s.setFrom(p.toString());
            if (keepCopy) {
                Path h = held.resolve(UUID.randomUUID().toString());
                copyTree(p, h);
                s.setHeld(h.toString());
            }
            j.getSteps().add(s);
            return keepCopy;
        }

        /** 刚登记的删除没有成功：去掉这一步和它的备份 */
        public void dropLastDelete() {
            List<Step> steps = j.getSteps();
            if (!steps.isEmpty() && "DELETE".equals(steps.get(steps.size() - 1).getType())) {
                Step s = steps.remove(steps.size() - 1);
                if (s.getHeld() != null) {
                    deleteTree(Path.of(s.getHeld()));
                }
            }
        }

        /** 删除批次记录、移除文件前，把工作区副本挪到备份目录（删除记录本身会删掉工作区） */
        public void holdWork(Path work) throws IOException {
            if (work == null || !Files.exists(work)) {
                return;
            }
            Path h = held.resolve(UUID.randomUUID().toString());
            Files.createDirectories(h.getParent());
            Files.move(work, h);
            Step s = new Step();
            s.setType("HOLD_WORK");
            s.setFrom(work.toString());
            s.setHeld(h.toString());
            j.getSteps().add(s);
        }

        /** 撤销归档前，把归档目录里会被删除的文件复制一份；move=true 表示撤销时会移回原位置 */
        public void holdUndo(Path target, Path source, String sha, boolean move) throws IOException {
            Step s = new Step();
            s.setType("HOLD_UNDO");
            s.setTo(target.toString());
            s.setFrom(source == null ? null : source.toString());
            s.setSha(sha);
            s.setMove(move);
            if (!move) {
                Path h = held.resolve(UUID.randomUUID().toString());
                Files.createDirectories(h.getParent());
                Files.copy(target, h, StandardCopyOption.COPY_ATTRIBUTES);
                s.setHeld(h.toString());
            }
            j.getSteps().add(s);
        }

        /** 归档：记下操作前最大的操作日志 id，回档时撤回这之后新增的归档文件 */
        public void archiveAfter(Long batchId, Long maxOpId) {
            Step s = new Step();
            s.setType("ARCHIVE_AFTER");
            s.setBatchId(batchId);
            s.setOpId(maxOpId == null ? 0L : maxOpId);
            j.getSteps().add(s);
        }

        /** 导入：回档时删除这个批次的工作区 */
        public void importWork(Long batchId) {
            Step s = new Step();
            s.setType("IMPORT_WORK");
            s.setBatchId(batchId);
            j.getSteps().add(s);
            addBatch(batchId);
        }
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;
    private final OaProperties props;
    private final LedgerExporter exporter;
    private final BatchService batchService;
    private final ReentrantLock lock = new ReentrantLock();
    private final Path dir;

    public RollbackService(JdbcTemplate jdbc, TransactionTemplate tx, ObjectMapper mapper, OaProperties props,
                           LedgerExporter exporter, BatchService batchService) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.mapper = mapper;
        this.props = props;
        this.exporter = exporter;
        this.batchService = batchService;
        this.dir = props.homePath().resolve("rollback");
    }

    /** 一次可回档操作的描述 */
    public record Op(String kind, String title, String source, List<String> undo, List<Long> batchIds,
                     List<Long> templateIds) {
    }

    /** 执行一个可回档的操作：先拍快照，执行成功后替换上一次的回档记录；执行失败时上一次的记录保持不变 */
    public <T> T record(Op op, Function<Recorder, T> action) {
        lock.lock();
        try {
            Journal j = new Journal();
            j.setId(UUID.randomUUID().toString());
            j.setKind(op.kind());
            j.setTitle(op.title());
            j.setSource(op.source());
            j.setAt(LocalDateTime.now());
            j.setUndo(new ArrayList<>(op.undo()));
            op.batchIds().forEach(id -> { if (id != null && !j.getBatchIds().contains(id)) j.getBatchIds().add(id); });
            op.templateIds().forEach(id -> { if (id != null && !j.getTemplateIds().contains(id)) j.getTemplateIds().add(id); });
            Path next = dir.resolve("next");
            deleteTree(next);
            Path held = next.resolve("held");
            Files.createDirectories(held);
            dropTables("rbn_");
            // 导入、新建模板执行前还不知道 id，也要建（空的）快照表，回档时据此删除新建的记录
            boolean db = !j.getBatchIds().isEmpty() || !j.getTemplateIds().isEmpty()
                    || op.kind().equals("IMPORT") || op.kind().equals("CREATE_TEMPLATE");
            if (db) {
                snapshot("rbn_", j.getBatchIds(), j.getTemplateIds());
            }
            j.setDbSnapshot(db);
            T result;
            Recorder rec = new Recorder(j, held);
            try {
                result = action.apply(rec);
            } catch (RuntimeException e) {
                putBackHeldWork(j);
                dropTables("rbn_");
                deleteTree(next);
                throw e;
            }
            if (rec.skipped) {
                putBackHeldWork(j);
                dropTables("rbn_");
                deleteTree(next);
                return result;
            }
            commit(j, next);
            return result;
        } catch (IOException e) {
            throw new BizException("记录回档信息失败：" + e.getMessage());
        } finally {
            lock.unlock();
        }
    }

    public Entry latest() {
        Journal j = readJournal();
        return j == null ? null : entry(j, check(j));
    }

    /** 回档最近一次操作；成功后清空回档记录（只能回档一次） */
    public Result rollback(String id) {
        lock.lock();
        try {
            Journal j = readJournal();
            if (j == null) {
                throw new BizException("没有可以回档的操作");
            }
            if (id != null && !id.isBlank() && !id.equals(j.getId())) {
                throw new BizException("回档记录已经变了（期间又有新的操作），请刷新后再看");
            }
            String blocked = check(j);
            if (blocked != null) {
                throw new BizException(blocked);
            }
            List<String> notes = new ArrayList<>();
            // 1. 磁盘：倒序撤回每一步
            List<Step> steps = new ArrayList<>(j.getSteps());
            java.util.Collections.reverse(steps);
            Set<Path> roots = new LinkedHashSet<>(targetRoots(j.getBatchIds()));
            for (Step s : steps) {
                try {
                    undoStep(s, notes);
                } catch (IOException | RuntimeException e) {
                    notes.add("有一步没能恢复：" + e.getMessage());
                }
            }
            // 2. 数据库：把涉及的批次、模板换回快照
            if (j.isDbSnapshot()) {
                restore(j.getBatchIds(), j.getTemplateIds(), notes);
            }
            // 3. 撤销归档后又回档：把被删掉的归档文件放回去（要在数据库恢复后，确认它们重新算作已归档）
            for (Step s : steps) {
                if ("HOLD_UNDO".equals(s.getType())) {
                    try {
                        restoreUndone(s, notes);
                    } catch (IOException e) {
                        notes.add("没能放回 " + Path.of(s.getTo()).getFileName() + "：" + e.getMessage());
                    }
                }
            }
            roots.addAll(targetRoots(j.getBatchIds()));
            for (Path root : roots) {
                exporter.refreshMasterLedger(root);
            }
            clear();
            log.info("已回档：{}（{}）", j.getTitle(), j.getKind());
            return new Result("已回档：" + j.getTitle(), notes);
        } finally {
            lock.unlock();
        }
    }

    /** 回档前的检查；可以回档时返回 null */
    String check(Journal j) {
        if (!j.isCanRollback()) {
            return j.getReason() == null ? "这次操作不能回档" : j.getReason();
        }
        for (Long b : j.getBatchIds()) {
            if (batchService.isRunning(b)) {
                return "批次 #" + b + " 正在识别中，请等处理完成后再回档";
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- 磁盘

    /** 操作失败或没有记录时，把已经挪到备份目录的工作区副本放回去，避免丢失 */
    private void putBackHeldWork(Journal j) {
        for (Step s : j.getSteps()) {
            if ("HOLD_WORK".equals(s.getType())) {
                Path from = Path.of(s.getFrom());
                Path h = Path.of(s.getHeld());
                try {
                    if (!Files.exists(from) && Files.exists(h)) {
                        Files.createDirectories(from.getParent());
                        Files.move(h, from);
                    }
                } catch (IOException e) {
                    log.warn("工作区副本放回失败：{} → {}", h, from);
                }
            }
        }
    }

    private void undoStep(Step s, List<String> notes) throws IOException {
        switch (s.getType()) {
            case "MKDIR" -> {
                Path p = Path.of(s.getTo());
                if (Files.isDirectory(p)) {
                    try (Stream<Path> in = Files.list(p)) {
                        if (in.findAny().isPresent()) {
                            notes.add("新建的文件夹“" + p.getFileName() + "”里已经放了东西，没有删除");
                            return;
                        }
                    }
                    Files.delete(p);
                }
            }
            case "MOVE" -> {
                Path from = Path.of(s.getFrom());
                Path to = Path.of(s.getTo());
                if (!Files.exists(to)) {
                    notes.add("“" + to.getFileName() + "”已经不在了，没法移回原位置");
                } else if (Files.exists(from)) {
                    notes.add("原位置“" + from + "”已经有同名的文件，“" + to.getFileName() + "”没有移回");
                } else {
                    Files.createDirectories(from.getParent());
                    Files.move(to, from);
                }
            }
            case "DELETE" -> {
                Path from = Path.of(s.getFrom());
                if (s.getHeld() == null) {
                    notes.add("“" + from.getFileName() + "”删除时太大没有备份，请到系统回收站里还原");
                } else if (Files.exists(from)) {
                    notes.add("原位置已经有“" + from.getFileName() + "”，没有覆盖；删除的那份还在回收站里");
                } else {
                    Files.createDirectories(from.getParent());
                    copyTree(held(s), from);
                }
            }
            case "HOLD_WORK" -> {
                Path from = Path.of(s.getFrom());
                if (!Files.exists(from) && Files.exists(held(s))) {
                    Files.createDirectories(from.getParent());
                    Files.move(held(s), from);
                }
            }
            case "ARCHIVE_AFTER" -> {
                List<Map<String, Object>> rows = jdbc.queryForList(
                        "select action, source_path, target_path, sha256 from operation_log where batch_id = ? and id > ? and undone_at is null",
                        s.getBatchId(), s.getOpId());
                for (Map<String, Object> r : rows) {
                    Path target = Path.of((String) r.get("target_path"));
                    Path source = r.get("source_path") == null ? null : Path.of((String) r.get("source_path"));
                    if (!Files.exists(target)) {
                        continue;
                    }
                    if (!String.valueOf(r.get("sha256")).equals(Hashes.sha256(target))) {
                        notes.add("“" + target.getFileName() + "”归档后被修改过，没有删除，请手动处理");
                        continue;
                    }
                    if ("MOVE".equals(r.get("action")) && source != null && !Files.exists(source)) {
                        Files.createDirectories(source.getParent());
                        Files.move(target, source);
                    } else {
                        Files.delete(target);
                    }
                }
            }
            case "IMPORT_WORK" -> {
                Path work = props.workRoot().resolve(String.valueOf(s.getBatchId())).normalize();
                if (work.startsWith(props.workRoot())) {
                    deleteTree(work);
                }
            }
            default -> {
                // HOLD_UNDO 在数据库恢复之后处理
            }
        }
    }

    private void restoreUndone(Step s, List<String> notes) throws IOException {
        Path target = Path.of(s.getTo());
        if (Files.exists(target)) {
            if (s.getSha() != null && !s.getSha().equals(Hashes.sha256(target))) {
                notes.add("归档位置已经有另一个“" + target.getFileName() + "”，没有覆盖");
            }
            return;
        }
        Files.createDirectories(target.getParent());
        if (s.isMove()) {
            Path source = s.getFrom() == null ? null : Path.of(s.getFrom());
            if (source != null && Files.exists(source) && s.getSha().equals(Hashes.sha256(source))) {
                Files.move(source, target);
            } else {
                notes.add("“" + target.getFileName() + "”的原文件已经不在原位置，没能放回归档目录");
            }
        } else if (Files.exists(held(s))) {
            Files.copy(held(s), target, StandardCopyOption.COPY_ATTRIBUTES);
        }
    }

    /** 备份文件放在 current/held 下，日志里只记文件名，提交时目录从 next 改名为 current 也不影响 */
    private Path held(Step s) {
        return dir.resolve("current").resolve("held").resolve(Path.of(s.getHeld()).getFileName().toString());
    }

    // ---------------------------------------------------------------- 数据库快照

    private void snapshot(String prefix, List<Long> batchIds, List<Long> templateIds) {
        String b = ids(batchIds);
        for (String[] t : BATCH_TABLES) {
            jdbc.execute("create table " + prefix + t[0] + " as select * from " + t[0] + " where " + t[1] + " in (" + b + ")");
        }
        jdbc.execute("create table " + prefix + "process_step as select * from process_step where file_record_id in "
                + "(select id from file_record where batch_id in (" + b + "))");
        jdbc.execute("create table " + prefix + "archive_template as select * from archive_template where id in ("
                + ids(templateIds) + ")");
    }

    private void restore(List<Long> batchIds, List<Long> templateIds, List<String> notes) {
        String b = ids(batchIds);
        String t = ids(templateIds);
        tx.executeWithoutResult(status -> {
            // 处理步骤按文件关联：现在属于这些批次的文件，加上快照里的文件
            String files = "select id from file_record where batch_id in (" + b + ") union select id from rb_file_record";
            jdbc.update("delete from process_step where file_record_id in (" + files + ")");
            for (String[] tb : BATCH_TABLES) {
                jdbc.update("delete from " + tb[0] + " where " + tb[1] + " in (" + b + ")");
            }
            jdbc.update("delete from archive_template where id in (" + t + ")");
            // 模板名不能重复：快照里的模板名已被别的模板占用时，恢复的那个加上“（回档）”
            List<Map<String, Object>> clash = jdbc.queryForList(
                    "select r.id, r.name from rb_archive_template r join archive_template a on a.name = r.name and a.id <> r.id");
            for (Map<String, Object> c : clash) {
                String name = c.get("name") + "（回档）";
                for (int i = 2; exists("select count(*) from archive_template where name = ?", name); i++) {
                    name = c.get("name") + "（回档" + i + "）";
                }
                jdbc.update("update rb_archive_template set name = ? where id = ?", name, c.get("id"));
                notes.add("模板“" + c.get("name") + "”已有同名的新模板，恢复的模板改名为“" + name + "”");
            }
            for (String[] tb : BATCH_TABLES) {
                insertFrom(tb[0]);
            }
            insertFrom("process_step");
            insertFrom("archive_template");
        });
    }

    private void insertFrom(String table) {
        Set<String> live = columns(table);
        List<String> cols = columns("rb_" + table).stream().filter(live::contains).toList();
        String list = String.join(", ", cols);
        jdbc.update("insert into " + table + " (" + list + ") select " + list + " from rb_" + table);
    }

    private Set<String> columns(String table) {
        return jdbc.query("select * from " + table + " where 1 = 0", rs -> {
            Set<String> out = new LinkedHashSet<>();
            var md = rs.getMetaData();
            for (int i = 1; i <= md.getColumnCount(); i++) {
                out.add(md.getColumnLabel(i).toLowerCase());
            }
            return out;
        });
    }

    private boolean exists(String sql, Object arg) {
        Integer n = jdbc.queryForObject(sql, Integer.class, arg);
        return n != null && n > 0;
    }

    private void dropTables(String prefix) {
        for (String t : List.of("archive_batch", "file_record", "operation_log", "ledger_entry", "process_step", "archive_template")) {
            jdbc.execute("drop table if exists " + prefix + t);
        }
    }

    private static String ids(List<Long> ids) {
        // 只拼接 Long，不会有注入；空列表用 -1 占位，快照表照样建出来（结构一致，0 行）
        return ids == null || ids.isEmpty() ? "-1" : String.join(",", ids.stream().map(String::valueOf).toList());
    }

    private List<Path> targetRoots(List<Long> batchIds) {
        if (batchIds.isEmpty()) {
            return List.of();
        }
        return jdbc.queryForList("select distinct target_root from archive_batch where id in (" + ids(batchIds) + ")", String.class)
                .stream().filter(r -> r != null && !r.isBlank()).map(Path::of).toList();
    }

    // ---------------------------------------------------------------- 回档记录的提交、读取、清空

    private void commit(Journal j, Path next) throws IOException {
        dropTables("rb_");
        if (j.isDbSnapshot()) {
            for (String t : List.of("archive_batch", "file_record", "operation_log", "ledger_entry", "process_step", "archive_template")) {
                jdbc.execute("alter table rbn_" + t + " rename to rb_" + t);
            }
        }
        Path current = dir.resolve("current");
        deleteTree(current); // 覆盖上一次的回档记录，它的备份文件一起删除
        mapper.writerWithDefaultPrettyPrinter().writeValue(next.resolve("journal.json").toFile(), j);
        Files.move(next, current);
    }

    private void clear() {
        dropTables("rb_");
        deleteTree(dir.resolve("current"));
    }

    private Journal readJournal() {
        Path f = dir.resolve("current").resolve("journal.json");
        if (!Files.exists(f)) {
            return null;
        }
        try {
            return mapper.readValue(f.toFile(), Journal.class);
        } catch (IOException e) {
            log.warn("回档记录无法读取：{}", e.getMessage());
            return null;
        }
    }

    private static Entry entry(Journal j, String blocked) {
        return new Entry(j.getId(), j.getKind(), j.getTitle(), j.getSource(), j.getAt(), j.getDone(), j.getUndo(),
                j.getWarnings(), blocked == null, blocked);
    }

    static void copyTree(Path src, Path dst) throws IOException {
        if (Files.isDirectory(src)) {
            try (Stream<Path> walk = Files.walk(src)) {
                for (Path p : walk.toList()) {
                    Path t = dst.resolve(src.relativize(p).toString());
                    if (Files.isDirectory(p)) {
                        Files.createDirectories(t);
                    } else {
                        Files.createDirectories(t.getParent());
                        Files.copy(p, t, StandardCopyOption.COPY_ATTRIBUTES);
                    }
                }
            }
        } else {
            Files.createDirectories(dst.getParent());
            Files.copy(src, dst, StandardCopyOption.COPY_ATTRIBUTES);
        }
    }

    static void deleteTree(Path p) {
        if (p == null || !Files.exists(p)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(p)) {
            for (Path x : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(x);
            }
        } catch (IOException e) {
            log.warn("删除回档备份失败：{}", e.getMessage());
        }
    }

    /** 计算要删除的文件总大小和文件数 */
    public static long[] sizeOf(Path p) throws IOException {
        if (!Files.isDirectory(p)) {
            return new long[] {Files.size(p), 1};
        }
        long[] r = {0, 0};
        try (Stream<Path> walk = Files.walk(p)) {
            for (Path x : walk.filter(Files::isRegularFile).toList()) {
                r[0] += Files.size(x);
                r[1]++;
            }
        }
        return r;
    }
}
