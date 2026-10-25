package com.yizhaoqi.oaarchive.fileops;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.oaarchive.common.BizException;
import com.yizhaoqi.oaarchive.config.OaProperties;
import com.yizhaoqi.oaarchive.ledger.LedgerExporter;
import com.yizhaoqi.oaarchive.launcher.AppHome;
import com.yizhaoqi.oaarchive.model.ArchiveBatch;
import com.yizhaoqi.oaarchive.model.LedgerEntry;
import com.yizhaoqi.oaarchive.repository.ArchiveBatchRepository;
import com.yizhaoqi.oaarchive.repository.LedgerEntryRepository;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 小R 能操作的文件夹范围。默认是归档目录和导入过的源文件夹，用户可以关掉其中一类，也可以手动添加文件夹。
 * 系统目录、程序目录、程序的数据目录、整个磁盘、用户主目录本身永远不能操作。
 * 所有文件操作（新建、改名、移动、删除）在执行前都要经过 {@link #check}。
 */
@Service
public class FileScopeService {

    private static final Logger log = LoggerFactory.getLogger(FileScopeService.class);
    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Settings {
        /** 允许操作归档目录（默认归档目录和每个批次的归档目录） */
        private boolean archiveRoots = true;
        /** 允许操作导入过的源文件夹 */
        private boolean importFolders = true;
        /** 用户手动添加的文件夹 */
        private List<String> extraRoots = new ArrayList<>();
    }

    /** 一个允许的根目录及其来源：ARCHIVE 归档目录、IMPORT 导入过的文件夹、EXTRA 手动添加 */
    public record Root(String path, String source) {
    }

    public record View(boolean archiveRoots, boolean importFolders, List<String> extraRoots, List<Root> effective) {
    }

    public record Update(Boolean archiveRoots, Boolean importFolders, List<String> extraRoots) {
    }

    /** 检查用途：只读、在里面新建、改名/移动/删除它本身 */
    public enum Use { READ, CREATE_IN, MODIFY }

    private final OaProperties props;
    private final ArchiveBatchRepository batches;
    private final LedgerEntryRepository ledger;
    private final ObjectMapper mapper;
    private final Path file;
    private volatile Settings settings;

    public FileScopeService(OaProperties props, ArchiveBatchRepository batches, LedgerEntryRepository ledger,
                            ObjectMapper mapper) {
        this.props = props;
        this.batches = batches;
        this.ledger = ledger;
        this.mapper = mapper;
        this.file = props.homePath().resolve("config").resolve("fileops.json");
        this.settings = read();
    }

    public View view() {
        Settings s = settings;
        return new View(s.isArchiveRoots(), s.isImportFolders(), List.copyOf(s.getExtraRoots()), roots());
    }

    public synchronized View update(Update u) {
        Settings s = mapper.convertValue(settings, Settings.class);
        if (u.archiveRoots() != null) {
            s.setArchiveRoots(u.archiveRoots());
        }
        if (u.importFolders() != null) {
            s.setImportFolders(u.importFolders());
        }
        if (u.extraRoots() != null) {
            List<String> list = new ArrayList<>();
            for (String raw : u.extraRoots()) {
                if (raw == null || raw.isBlank()) {
                    continue;
                }
                Path p = real(Path.of(raw.strip()));
                validateExtraRoot(p);
                if (list.stream().noneMatch(x -> Path.of(x).equals(p))) {
                    list.add(p.toString());
                }
            }
            s.setExtraRoots(list);
        }
        try {
            Files.createDirectories(file.getParent());
            mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), s);
        } catch (IOException e) {
            throw new BizException("保存操作范围失败：" + e.getMessage());
        }
        settings = s;
        return view();
    }

    /** 当前生效的根目录（去重，子目录被父目录包含时只保留父目录） */
    public List<Root> roots() {
        Settings s = settings;
        Map<Path, String> out = new LinkedHashMap<>();
        if (s.isArchiveRoots()) {
            addRoot(out, props.getArchive().getDefaultRoot(), "ARCHIVE");
            for (ArchiveBatch b : batches.findAll()) {
                addRoot(out, b.getTargetRoot(), "ARCHIVE");
            }
        }
        if (s.isImportFolders()) {
            for (ArchiveBatch b : batches.findAll()) {
                if (b.getSourceType() == ArchiveBatch.SourceType.FOLDER) {
                    addRoot(out, b.getSourcePath(), "IMPORT");
                }
            }
        }
        for (String x : s.getExtraRoots()) {
            addRoot(out, x, "EXTRA");
        }
        // 去掉被其他根目录包含的子目录
        List<Root> list = new ArrayList<>();
        for (Map.Entry<Path, String> e : out.entrySet()) {
            boolean nested = out.keySet().stream().anyMatch(o -> !o.equals(e.getKey()) && e.getKey().startsWith(o));
            if (!nested) {
                list.add(new Root(e.getKey().toString(), e.getValue()));
            }
        }
        return list;
    }

    private void addRoot(Map<Path, String> out, String raw, String source) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        try {
            Path p = real(Path.of(raw));
            if (forbiddenReason(p) == null && !isDriveRoot(p)) {
                out.putIfAbsent(p, source);
            }
        } catch (RuntimeException e) {
            log.debug("忽略无效的根目录 {}", raw);
        }
    }

    /**
     * 检查一个路径能不能用于某种操作，返回规范化后的路径；不能时抛出 {@link BizException}，说明原因。
     * MODIFY（改名、移动、删除）还要求：不是根目录本身、不是台账文件、不含已归档的文件。
     */
    public Path check(String raw, Use use) {
        if (raw == null || raw.isBlank()) {
            throw new BizException("请提供路径");
        }
        Path p = real(Path.of(raw.strip()));
        String forbidden = forbiddenReason(p);
        if (forbidden != null) {
            throw new BizException(forbidden);
        }
        List<Root> roots = roots();
        Root root = roots.stream().filter(r -> p.startsWith(Path.of(r.path()))).findFirst().orElse(null);
        if (root == null) {
            throw new BizException("不在允许操作的文件夹范围内：" + p + "。允许的范围：" + describe(roots)
                    + "。可以在 AI 助手的“连接设置 → 操作范围”里添加文件夹");
        }
        if (use == Use.MODIFY) {
            if (p.equals(Path.of(root.path()))) {
                throw new BizException("不能改名、移动或删除允许范围的根目录本身：" + p);
            }
            String name = p.getFileName() == null ? "" : p.getFileName().toString();
            if (name.equals(LedgerExporter.MASTER_FILE) || name.equals(LedgerExporter.MASTER_FILE + ".tmp")) {
                throw new BizException("“" + name + "”是程序维护的文件目录台账，不能改名、移动或删除");
            }
            String archived = archivedInside(p);
            if (archived != null) {
                throw new BizException("“" + p.getFileName() + "”" + (Files.isDirectory(p) ? "里有" : "是")
                        + "已经归档、记在台账里的文件（如 " + archived + "）。直接改动会让台账对不上，请先在“导入识别”页撤销对应批次的归档");
            }
        }
        return p;
    }

    /** 导入只读取、复制源文件，不改动它们，所以范围外的文件夹也可以导入；但系统目录、数据目录、整个磁盘不行 */
    public Path checkImportSource(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BizException("请提供要导入的文件夹");
        }
        Path p = real(Path.of(raw.strip()));
        if (!Files.isDirectory(p)) {
            throw new BizException("文件夹不存在：" + p);
        }
        if (isDriveRoot(p)) {
            throw new BizException("不能导入整个磁盘（" + p + "），请选择具体的文件夹");
        }
        String forbidden = forbiddenReason(p);
        if (forbidden != null) {
            throw new BizException(forbidden);
        }
        return p;
    }

    /** 手动添加的文件夹：必须存在，不能是磁盘根目录、系统目录、用户主目录本身 */
    void validateExtraRoot(Path p) {
        if (!Files.isDirectory(p)) {
            throw new BizException("文件夹不存在：" + p);
        }
        if (isDriveRoot(p)) {
            throw new BizException("不能把整个磁盘（" + p + "）加入操作范围，请选择具体的文件夹");
        }
        Path home = real(Path.of(System.getProperty("user.home")));
        for (String n : List.of("", "Desktop", "Documents", "Downloads")) {
            if (p.equals(n.isEmpty() ? home : home.resolve(n))) {
                throw new BizException("范围太大：不能把“" + p + "”整个加入，请选择它下面具体的文件夹");
            }
        }
        String forbidden = forbiddenReason(p);
        if (forbidden != null) {
            throw new BizException(forbidden);
        }
    }

    /** 永远不能操作的位置；可以操作时返回 null */
    String forbiddenReason(Path p) {
        Path data = real(props.homePath());
        if (p.startsWith(data) || data.startsWith(p)) {
            return "不能操作程序的数据目录（数据库、模板、设置都在里面）：" + data;
        }
        // 打包运行时，程序所在目录也不能动；开发时 user.dir 是源码目录，不做限制
        if (System.getProperty("jpackage.app-path") != null) {
            Path app = real(AppHome.appDirectory());
            if (p.startsWith(app) || app.startsWith(p)) {
                return "不能操作程序所在的目录：" + app;
            }
        }
        if (WINDOWS) {
            for (String env : List.of("SystemRoot", "ProgramFiles", "ProgramFiles(x86)", "ProgramData")) {
                String v = System.getenv(env);
                if (v != null && !v.isBlank() && p.startsWith(real(Path.of(v)))) {
                    return "不能操作系统或软件安装目录：" + v;
                }
            }
        } else if (p.equals(Path.of("/")) || p.startsWith("/etc") || p.startsWith("/usr") || p.startsWith("/bin")) {
            return "不能操作系统目录：" + p;
        }
        Path home = real(Path.of(System.getProperty("user.home")));
        if (home.startsWith(p)) {
            return "不能操作用户主目录或它的上级目录：" + p;
        }
        return null;
    }

    /** 路径本身或它里面有没有记在台账里、还没撤销的文件；有时返回其中一个文件名 */
    String archivedInside(Path p) {
        for (LedgerEntry e : ledger.findByUndoneFalseOrderByIdAsc()) {
            if (e.getArchivePath() == null) {
                continue;
            }
            Path a = Path.of(e.getArchivePath()).toAbsolutePath().normalize();
            if (a.startsWith(p)) {
                return a.getFileName().toString();
            }
        }
        return null;
    }

    private static boolean isDriveRoot(Path p) {
        return p.getParent() == null;
    }

    private static String describe(List<Root> roots) {
        if (roots.isEmpty()) {
            return "（空）";
        }
        StringBuilder sb = new StringBuilder();
        for (Root r : roots) {
            sb.append(sb.isEmpty() ? "" : "、").append(r.path());
        }
        return sb.toString();
    }

    /** 规范化路径：解析 .. 和符号链接、目录联接；路径不存在时解析最近存在的上级目录，再拼上剩余部分 */
    static Path real(Path raw) {
        Path abs = raw.toAbsolutePath().normalize();
        Path existing = abs;
        while (existing != null && !Files.exists(existing)) {
            existing = existing.getParent();
        }
        if (existing == null) {
            return abs;
        }
        try {
            Path realBase = existing.toRealPath();
            return realBase.resolve(existing.relativize(abs)).normalize();
        } catch (IOException e) {
            return abs;
        }
    }

    private Settings read() {
        if (!Files.exists(file)) {
            return new Settings();
        }
        try {
            return mapper.readValue(file.toFile(), Settings.class);
        } catch (IOException e) {
            log.warn("操作范围设置无法读取，已使用默认设置：{}", e.getMessage());
            return new Settings();
        }
    }
}
