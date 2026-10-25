package com.yizhaoqi.oaarchive.fileops;

import com.yizhaoqi.oaarchive.common.BizException;
import com.yizhaoqi.oaarchive.rollback.RollbackService;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 小R 的文件操作：查看、新建文件夹、改名、移动、删除（放回收站）。
 * 每个操作分两步：preview 只检查并说明会发生什么（用于确认卡片），apply 在用户确认后重新检查一遍再执行，
 * 执行时登记到回档记录。所有路径都经过 {@link FileScopeService#check} 限制在允许的范围内。
 */
@Service
public class FileOpsService {

    /** 一次最多删除的文件数，避免误删整个大目录 */
    static final int MAX_DELETE_FILES = 5000;
    /** 超过这个数量的删除算高风险，需要二次确认 */
    public static final int DANGER_FILES = 20;
    private static final int MAX_LIST = 200;
    private static final Pattern BAD_NAME = Pattern.compile("[\\\\/:*?\"<>|\\p{Cntrl}]");
    private static final Set<String> RESERVED = Set.of("CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");

    /** 确认卡片要显示的内容 */
    public record Preview(String title, List<String> lines, List<String> warnings, boolean danger, long files, long bytes) {
    }

    public record Item(String name, boolean dir, Long size, String modified) {
    }

    public record Listing(String path, int total, List<Item> items, boolean truncated) {
    }

    private final FileScopeService scope;
    private final RollbackService rollback;
    private final Trash trash;

    public FileOpsService(FileScopeService scope, RollbackService rollback, Trash trash) {
        this.scope = scope;
        this.rollback = rollback;
        this.trash = trash;
    }

    // ---------------------------------------------------------------- 查看

    public Listing list(String raw) {
        Path dir = scope.check(raw, FileScopeService.Use.READ);
        if (!Files.isDirectory(dir)) {
            throw new BizException("不是文件夹或不存在：" + dir);
        }
        List<Item> items = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : s.toList()) {
                try {
                    BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class);
                    items.add(new Item(p.getFileName().toString(), a.isDirectory(), a.isDirectory() ? null : a.size(),
                            LocalDateTime.ofInstant(Instant.ofEpochMilli(a.lastModifiedTime().toMillis()), ZoneId.systemDefault())
                                    .withNano(0).toString()));
                } catch (IOException ignored) {
                    // 读不到属性的文件跳过
                }
            }
        } catch (IOException e) {
            throw new BizException("无法读取文件夹：" + e.getMessage());
        }
        items.sort(Comparator.comparing((Item i) -> !i.dir()).thenComparing(Item::name, String.CASE_INSENSITIVE_ORDER));
        int total = items.size();
        return new Listing(dir.toString(), total, total > MAX_LIST ? items.subList(0, MAX_LIST) : items, total > MAX_LIST);
    }

    // ---------------------------------------------------------------- 新建文件夹

    public Preview previewMkdir(String parent, String name) {
        Path target = mkdirTarget(parent, name);
        return new Preview("新建文件夹“" + target.getFileName() + "”", List.of("位置：" + target), List.of(), false, 0, 0);
    }

    public String applyMkdir(String parent, String name, String source) {
        Path target = mkdirTarget(parent, name);
        var op = new RollbackService.Op("MKDIR", "新建文件夹“" + target.getFileName() + "”", source,
                List.of("删除这个新建的文件夹（里面已经放了东西时不删）"), List.of(), List.of());
        return rollback.record(op, r -> {
            // 逐级创建，每一级都登记，回档时从里往外删
            List<Path> created = new ArrayList<>();
            for (Path p = target; p != null && !Files.exists(p); p = p.getParent()) {
                created.add(0, p);
            }
            try {
                for (Path p : created) {
                    Files.createDirectory(p);
                    r.mkdir(p);
                }
            } catch (IOException e) {
                throw new BizException("新建文件夹失败：" + e.getMessage());
            }
            r.done("新建了文件夹 " + target);
            return target.toString();
        });
    }

    private Path mkdirTarget(String parent, String name) {
        if (name == null || name.isBlank()) {
            throw new BizException("请提供文件夹名称");
        }
        Path base = scope.check(parent, FileScopeService.Use.CREATE_IN);
        if (!Files.isDirectory(base)) {
            throw new BizException("上级文件夹不存在：" + base);
        }
        Path target = base;
        for (String seg : name.strip().split("[/\\\\]+")) {
            if (!seg.isEmpty()) {
                target = target.resolve(validName(seg));
            }
        }
        target = scope.check(target.toString(), FileScopeService.Use.CREATE_IN);
        if (Files.exists(target)) {
            throw new BizException("已经存在：" + target);
        }
        return target;
    }

    // ---------------------------------------------------------------- 改名、移动

    public Preview previewRename(String path, String newName) {
        Path[] p = renamePaths(path, newName);
        return new Preview("把“" + p[0].getFileName() + "”改名为“" + p[1].getFileName() + "”",
                List.of("位置：" + p[0].getParent()), List.of(), false, 0, 0);
    }

    public String applyRename(String path, String newName, String source) {
        Path[] p = renamePaths(path, newName);
        return move(p[0], p[1], "RENAME", "把“" + p[0].getFileName() + "”改名为“" + p[1].getFileName() + "”", source,
                "名字改回“" + p[0].getFileName() + "”");
    }

    private Path[] renamePaths(String path, String newName) {
        Path from = existing(scope.check(path, FileScopeService.Use.MODIFY));
        Path to = scope.check(from.resolveSibling(validName(newName == null ? "" : newName.strip())).toString(),
                FileScopeService.Use.CREATE_IN);
        if (to.equals(from)) {
            throw new BizException("新名字和原来一样");
        }
        if (Files.exists(to) && !sameFileDifferentCase(from, to)) {
            throw new BizException("同一位置已经有“" + to.getFileName() + "”");
        }
        return new Path[]{from, to};
    }

    public Preview previewMove(List<String> paths, String targetDir) {
        List<Path[]> moves = movePaths(paths, targetDir);
        List<String> lines = new ArrayList<>();
        lines.add("移动到：" + moves.get(0)[1].getParent());
        moves.stream().limit(10).forEach(m -> lines.add(m[0].toString()));
        if (moves.size() > 10) {
            lines.add("……共 " + moves.size() + " 项");
        }
        return new Preview("移动 " + moves.size() + " 项到“" + moves.get(0)[1].getParent().getFileName() + "”", lines,
                List.of(), moves.size() > DANGER_FILES, moves.size(), 0);
    }

    public String applyMove(List<String> paths, String targetDir, String source) {
        List<Path[]> moves = movePaths(paths, targetDir);
        String dest = moves.get(0)[1].getParent().toString();
        var op = new RollbackService.Op("MOVE", "移动 " + moves.size() + " 项到 " + dest, source,
                List.of("把这些文件移回原来的位置"), List.of(), List.of());
        return rollback.record(op, r -> {
            int n = 0;
            for (Path[] m : moves) {
                try {
                    Files.move(m[0], m[1]);
                    r.moved(m[0], m[1]);
                    n++;
                } catch (IOException e) {
                    if (n == 0) {
                        throw new BizException("移动失败：" + m[0].getFileName() + "：" + e.getMessage());
                    }
                    r.warn("“" + m[0].getFileName() + "”没有移动：" + e.getMessage());
                }
            }
            r.done("移动了 " + n + " 项到 " + dest);
            return "已移动 " + n + " 项到 " + dest;
        });
    }

    private List<Path[]> movePaths(List<String> paths, String targetDir) {
        if (paths == null || paths.isEmpty()) {
            throw new BizException("请提供要移动的文件或文件夹");
        }
        if (paths.size() > 200) {
            throw new BizException("一次最多移动 200 项");
        }
        Path dir = scope.check(targetDir, FileScopeService.Use.CREATE_IN);
        if (!Files.isDirectory(dir)) {
            throw new BizException("目标文件夹不存在：" + dir + "（可以先新建）");
        }
        List<Path[]> out = new ArrayList<>();
        for (String raw : paths) {
            Path from = existing(scope.check(raw, FileScopeService.Use.MODIFY));
            if (dir.startsWith(from)) {
                throw new BizException("不能把“" + from.getFileName() + "”移动到它自己里面");
            }
            if (dir.equals(from.getParent())) {
                throw new BizException("“" + from.getFileName() + "”已经在这个文件夹里了");
            }
            Path to = dir.resolve(from.getFileName().toString());
            if (Files.exists(to)) {
                throw new BizException("目标文件夹里已经有“" + to.getFileName() + "”，为避免覆盖没有移动");
            }
            if (Files.isDirectory(from) && !sameDisk(from, dir)) {
                throw new BizException("不能把文件夹“" + from.getFileName() + "”移动到另一个磁盘，请移动里面的文件，或者手动复制");
            }
            for (Path[] o : out) {
                if (o[1].equals(to)) {
                    throw new BizException("有两项同名（" + to.getFileName() + "），不能移动到同一个文件夹");
                }
            }
            out.add(new Path[]{from, to});
        }
        return out;
    }

    private String move(Path from, Path to, String kind, String title, String source, String undo) {
        var op = new RollbackService.Op(kind, title, source, List.of(undo), List.of(), List.of());
        return rollback.record(op, r -> {
            try {
                Files.move(from, to);
            } catch (IOException e) {
                throw new BizException("操作失败：" + e.getMessage());
            }
            r.moved(from, to);
            r.done(title.replaceFirst("^把", "已把"));
            return to.toString();
        });
    }

    // ---------------------------------------------------------------- 删除

    public Preview previewDelete(List<String> paths) {
        List<Path> list = deletePaths(paths);
        long[] size = size(list);
        List<String> lines = new ArrayList<>();
        list.stream().limit(10).forEach(p -> lines.add((Files.isDirectory(p) ? "文件夹 " : "文件 ") + p));
        if (list.size() > 10) {
            lines.add("……共 " + list.size() + " 项");
        }
        lines.add("合计 " + size[1] + " 个文件，" + human(size[0]) + "，删除后放进系统回收站");
        List<String> warnings = new ArrayList<>();
        if (size[0] > RollbackService.HOLD_LIMIT) {
            warnings.add("超过 2 GB，回档时没有备份可用，只能到系统回收站里还原");
        }
        boolean danger = size[1] > DANGER_FILES || list.stream().anyMatch(Files::isDirectory);
        return new Preview("删除 " + list.size() + " 项", lines, warnings, danger, size[1], size[0]);
    }

    public String applyDelete(List<String> paths, String source) {
        List<Path> list = deletePaths(paths);
        long[] size = size(list);
        boolean keep = size[0] <= RollbackService.HOLD_LIMIT;
        var op = new RollbackService.Op("DELETE", "删除 " + list.size() + " 项（" + size[1] + " 个文件）", source,
                List.of(keep ? "把删除的文件放回原位置" : "文件太大没有备份，回档时请到系统回收站里还原"), List.of(), List.of());
        return rollback.record(op, r -> {
            int n = 0;
            List<String> failed = new ArrayList<>();
            for (Path p : list) {
                try {
                    r.holdForDelete(p, keep);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                try {
                    trash.delete(p);
                    n++;
                } catch (RuntimeException e) {
                    r.dropLastDelete();
                    failed.add(p.getFileName() + "：" + e.getMessage());
                }
            }
            if (n == 0) {
                throw new BizException("删除失败：" + String.join("；", failed));
            }
            failed.forEach(f -> r.warn("没有删除 " + f));
            if (!keep) {
                r.warn("删除的文件超过 2 GB，没有备份，回档时只能到系统回收站里还原");
            }
            r.done("删除了 " + n + " 项，已放进系统回收站");
            return "已删除 " + n + " 项，放进了系统回收站" + (failed.isEmpty() ? "" : "；" + failed.size() + " 项没有删除：" + String.join("；", failed));
        });
    }

    private List<Path> deletePaths(List<String> paths) {
        if (paths == null || paths.isEmpty()) {
            throw new BizException("请提供要删除的文件或文件夹");
        }
        if (paths.size() > 200) {
            throw new BizException("一次最多删除 200 项");
        }
        List<Path> list = new ArrayList<>();
        for (String raw : paths) {
            Path p = existing(scope.check(raw, FileScopeService.Use.MODIFY));
            if (list.contains(p)) {
                continue;
            }
            for (Path o : list) {
                if (p.startsWith(o) || o.startsWith(p)) {
                    throw new BizException("“" + p.getFileName() + "”和“" + o.getFileName() + "”有包含关系，只需要删除外层的那个");
                }
            }
            list.add(p);
        }
        long[] size = size(list);
        if (size[1] > MAX_DELETE_FILES) {
            throw new BizException("一次要删除 " + size[1] + " 个文件，超过上限 " + MAX_DELETE_FILES + "，请分批删除，或者在资源管理器里手动删除");
        }
        return list;
    }

    // ---------------------------------------------------------------- 工具函数

    private static long[] size(List<Path> list) {
        long bytes = 0, files = 0;
        for (Path p : list) {
            try {
                long[] s = RollbackService.sizeOf(p);
                bytes += s[0];
                files += s[1];
            } catch (IOException e) {
                throw new BizException("无法读取 " + p.getFileName() + "：" + e.getMessage());
            }
        }
        return new long[]{bytes, files};
    }

    private static Path existing(Path p) {
        if (!Files.exists(p)) {
            throw new BizException("不存在：" + p);
        }
        return p;
    }

    static String validName(String name) {
        String n = name == null ? "" : name.strip();
        if (n.isEmpty() || n.equals(".") || n.equals("..")) {
            throw new BizException("名称不能为空");
        }
        if (BAD_NAME.matcher(n).find()) {
            throw new BizException("名称“" + n + "”里有不能用的字符（\\ / : * ? \" < > |）");
        }
        if (n.endsWith(".")) {
            throw new BizException("名称不能以“.”结尾");
        }
        String stem = n.contains(".") ? n.substring(0, n.indexOf('.')) : n;
        if (RESERVED.contains(stem.toUpperCase())) {
            throw new BizException("“" + n + "”是 Windows 保留的名称，不能使用");
        }
        if (n.length() > 120) {
            throw new BizException("名称太长（最多 120 个字符）");
        }
        return n;
    }

    /** Windows 上只改大小写时，新旧路径指向同一个文件 */
    private static boolean sameFileDifferentCase(Path a, Path b) {
        try {
            return a.toString().equalsIgnoreCase(b.toString()) && Files.isSameFile(a, b);
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean sameDisk(Path a, Path b) {
        try {
            return Files.getFileStore(a).equals(Files.getFileStore(b));
        } catch (IOException e) {
            return a.getRoot() != null && a.getRoot().equals(b.getRoot());
        }
    }

    static String human(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024 * 1024) {
            return String.format("%.1f MB", bytes / 1024.0 / 1024);
        }
        return String.format("%.2f GB", bytes / 1024.0 / 1024 / 1024);
    }

    /** 给确认卡片用的统计 */
    public static Map<String, Object> stats(Preview p) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (p.files() > 0) {
            m.put("files", p.files());
        }
        if (p.bytes() > 0) {
            m.put("size", human(p.bytes()));
        }
        return m;
    }
}
