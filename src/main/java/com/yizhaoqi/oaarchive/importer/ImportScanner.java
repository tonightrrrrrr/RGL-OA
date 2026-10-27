package com.yizhaoqi.oaarchive.importer;

import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 递归扫描源文件夹并过滤（技术文档 6.1.2）：不跟随符号链接，跳过 Office 临时锁文件、隐藏文件、空文件和超大文件。
 */
public final class ImportScanner {

    private static final Set<String> SYSTEM_FILES = Set.of("thumbs.db", "desktop.ini", ".ds_store");

    public record Scanned(Path path, long size) {
    }

    public record Skipped(String name, String reason) {
    }

    /** tooMany 为 true 时可导入的文件超过了 maxFiles，扫描已提前停止，accepted 不完整 */
    public record Result(List<Scanned> accepted, List<Skipped> skipped, boolean tooMany) {
    }

    private ImportScanner() {
    }

    public static Result scan(Path root, int maxDepth, long maxBytes) throws IOException {
        return scan(root, maxDepth, maxBytes, Integer.MAX_VALUE);
    }

    /** maxFiles：可导入的文件超过这个数就停止扫描，避免误选整个磁盘时长时间扫描 */
    public static Result scan(Path root, int maxDepth, long maxBytes, int maxFiles) throws IOException {
        List<Scanned> accepted = new ArrayList<>();
        boolean[] tooMany = {false};
        List<Skipped> skipped = new ArrayList<>();
        Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), maxDepth, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                return !dir.equals(root) && name.startsWith(".") ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (!attrs.isRegularFile()) {
                    return FileVisitResult.CONTINUE;
                }
                String reason = skipReason(file.getFileName().toString(), attrs.size(), maxBytes, isHidden(file));
                if (reason != null) {
                    skipped.add(new Skipped(root.relativize(file).toString(), reason));
                } else {
                    accepted.add(new Scanned(file, attrs.size()));
                    if (accepted.size() > maxFiles) {
                        tooMany[0] = true;
                        return FileVisitResult.TERMINATE;
                    }
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                skipped.add(new Skipped(root.relativize(file).toString(), "无法读取"));
                return FileVisitResult.CONTINUE;
            }
        });
        accepted.sort((a, b) -> a.path().toString().compareTo(b.path().toString()));
        return new Result(accepted, skipped, tooMany[0]);
    }

    /** 返回跳过原因；可以导入时返回 null。上传的文件也用这条规则 */
    public static String skipReason(String name, long size, long maxBytes, boolean hidden) {
        if (name.startsWith("~$")) {
            return "Office 临时文件";
        }
        if (name.startsWith(".") || hidden) {
            return "隐藏文件";
        }
        if (SYSTEM_FILES.contains(name.toLowerCase())) {
            return "系统文件";
        }
        if (size == 0) {
            return "空文件";
        }
        if (size > maxBytes) {
            return "超过大小上限（" + maxBytes / 1024 / 1024 + " MB）";
        }
        return null;
    }

    private static boolean isHidden(Path file) {
        try {
            return Files.isHidden(file);
        } catch (IOException e) {
            return false;
        }
    }
}
