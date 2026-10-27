package com.yizhaoqi.oaarchive.pipeline;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.oaarchive.common.BizException;
import com.yizhaoqi.oaarchive.config.OaProperties;
import com.yizhaoqi.oaarchive.model.ArchiveBatch;
import com.yizhaoqi.oaarchive.repository.ArchiveBatchRepository;
import com.yizhaoqi.oaarchive.repository.TemplateRepository;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;

/**
 * 记住导入时的使用习惯（v1.7.2）：上次用的归档目录、模板、导入方式和导入过的文件夹。
 * 保存在数据目录的 config/prefs.json，和 AI 设置放在一起；程序重启、换端口、升级覆盖都不会丢。
 * 导入识别页、小R导入、托盘“打开归档目录”、系统设置都从这里取默认归档目录，不再每次回到系统默认。
 */
@Service
public class ImportPrefsService {

    private static final Logger log = LoggerFactory.getLogger(ImportPrefsService.class);

    /** 默认归档目录从哪来：上次导入用的、在系统设置里手动设的、系统默认（配置文件里的 oa.archive.default-root） */
    public enum Source { LAST_USED, MANUAL, SYSTEM }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Prefs {
        private String archiveRoot;
        private Source archiveRootSource;
        private Long templateId;
        /** folder 或 upload */
        private String mode;
        /** 上次导入的文件夹，选择窗口从它旁边打开 */
        private String lastSourceDir;
        private LocalDateTime updatedAt;
    }

    /**
     * 给界面用的默认值。archiveRoot 是实际生效的默认归档目录；available 为 false 时 unavailableReason 说明原因
     * （比如移动硬盘没插）。templateId 为空表示用默认模板；sourceChooserStart 是“选择文件夹”窗口打开的位置。
     */
    public record View(String archiveRoot, Source archiveRootSource, String systemDefaultRoot, boolean available,
                       String unavailableReason, Long templateId, String mode, String lastSourceDir,
                       String sourceChooserStart) {
    }

    private final OaProperties props;
    private final ArchiveBatchRepository batches;
    private final TemplateRepository templates;
    private final ObjectMapper mapper;
    private final Path file;
    private Prefs cache;

    public ImportPrefsService(OaProperties props, ArchiveBatchRepository batches, TemplateRepository templates,
                              ObjectMapper mapper) {
        this.props = props;
        this.batches = batches;
        this.templates = templates;
        this.mapper = mapper;
        this.file = props.homePath().resolve("config").resolve("prefs.json");
    }

    /** 实际生效的默认归档目录：记住的目录，没有就用系统默认 */
    public synchronized String archiveRoot() {
        String r = prefs().getArchiveRoot();
        return r == null || r.isBlank() ? systemDefault() : r;
    }

    /** 记住的模板；模板已经被删掉时返回 null，交给调用方用默认模板 */
    public synchronized Long templateId() {
        Long id = prefs().getTemplateId();
        return id != null && templates.existsById(id) ? id : null;
    }

    public synchronized View view() {
        Prefs p = prefs();
        String root = archiveRoot();
        Source source = p.getArchiveRoot() == null || p.getArchiveRoot().isBlank() ? Source.SYSTEM
                : p.getArchiveRootSource() == null ? Source.LAST_USED : p.getArchiveRootSource();
        String reason = unavailableReason(root);
        String last = p.getLastSourceDir();
        String start = null;
        if (last != null && !last.isBlank()) {
            Path parent = Path.of(last).getParent();
            start = parent == null ? last : parent.toString();
        }
        String mode = "upload".equals(p.getMode()) ? "upload" : "folder";
        return new View(root, source, systemDefault(), reason == null, reason, templateId(), mode, last, start);
    }

    /** 一次导入成功开始识别后调用：记下这次用的归档目录、模板、导入方式；保存失败只记日志，不影响导入 */
    public synchronized void recordImport(ArchiveBatch b) {
        Prefs p = copy(prefs());
        p.setArchiveRoot(b.getTargetRoot());
        p.setArchiveRootSource(Source.LAST_USED);
        p.setTemplateId(b.getTemplateId());
        p.setMode(b.getSourceType() == ArchiveBatch.SourceType.UPLOAD ? "upload" : "folder");
        if (b.getSourceType() == ArchiveBatch.SourceType.FOLDER && b.getSourcePath() != null) {
            p.setLastSourceDir(b.getSourcePath());
        }
        try {
            save(p);
        } catch (BizException e) {
            log.warn("导入习惯没有保存：{}", e.getMessage());
        }
    }

    /** 在系统设置里手动指定默认归档目录 */
    public synchronized View setArchiveRoot(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BizException("请选择默认归档目录");
        }
        Path p = Path.of(raw.strip()).toAbsolutePath().normalize();
        if (p.getParent() == null) {
            throw new BizException("归档目录不能是磁盘根目录，请选择一个文件夹");
        }
        String reason = unavailableReason(p.toString());
        if (reason != null) {
            throw new BizException(reason);
        }
        Prefs next = copy(prefs());
        next.setArchiveRoot(p.toString());
        next.setArchiveRootSource(Source.MANUAL);
        save(next);
        return view();
    }

    /** 恢复系统默认：清掉记住的归档目录；之后导入又会记住新的 */
    public synchronized View resetArchiveRoot() {
        Prefs next = copy(prefs());
        next.setArchiveRoot(null);
        next.setArchiveRootSource(null);
        save(next);
        return view();
    }

    /**
     * 归档目录所在的磁盘不存在时返回原因（比如 D: 是移动硬盘，现在没插），否则返回 null。
     * 只检查盘符：文件夹本身不存在没关系，归档时会自动建；网络路径（\\server\share）不检查，免得网络不通时卡住。
     */
    public static String unavailableReason(String root) {
        if (root == null || root.isBlank()) {
            return null;
        }
        Path p = Path.of(root.strip()).toAbsolutePath().normalize();
        Path drive = p.getRoot();
        if (drive == null || drive.toString().startsWith("\\\\")) {
            return null;
        }
        if (!Files.exists(drive)) {
            return "找不到归档目录所在的磁盘 " + drive + "（如果是移动硬盘，请先插上），或者换一个归档目录";
        }
        return null;
    }

    private String systemDefault() {
        String d = props.getArchive().getDefaultRoot();
        return d == null ? "" : Path.of(d).toAbsolutePath().normalize().toString();
    }

    /**
     * 第一次读取：有 prefs.json 就用它。没有（新装，或者从 v1.7.1 及以前升级）时，取最近一个批次用过的归档目录和模板，
     * 这样以前一直选 D 盘的用户升级后不用再选一次；一个批次都没有就用系统默认。
     */
    private Prefs prefs() {
        if (cache != null) {
            return cache;
        }
        Prefs p = null;
        if (Files.exists(file)) {
            try {
                p = mapper.readValue(file.toFile(), Prefs.class);
            } catch (IOException e) {
                log.warn("导入习惯文件无法读取，已重新从最近的批次获取：{}", e.getMessage());
            }
        }
        if (p == null) {
            p = new Prefs();
            ArchiveBatch last = batches.findTop50ByOrderByIdDesc().stream().findFirst().orElse(null);
            if (last != null) {
                p.setArchiveRoot(last.getTargetRoot());
                p.setArchiveRootSource(Source.LAST_USED);
                p.setTemplateId(last.getTemplateId());
                p.setMode(last.getSourceType() == ArchiveBatch.SourceType.UPLOAD ? "upload" : "folder");
                if (last.getSourceType() == ArchiveBatch.SourceType.FOLDER) {
                    p.setLastSourceDir(last.getSourcePath());
                }
            }
        }
        cache = p;
        return p;
    }

    private void save(Prefs p) {
        p.setUpdatedAt(LocalDateTime.now().withNano(0));
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling("prefs.json.tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), p);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new BizException("保存导入习惯失败：" + e.getMessage());
        }
        cache = p;
    }

    private Prefs copy(Prefs p) {
        return mapper.convertValue(p, Prefs.class);
    }
}
