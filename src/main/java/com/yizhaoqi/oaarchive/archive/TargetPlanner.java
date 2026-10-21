package com.yizhaoqi.oaarchive.archive;

import com.yizhaoqi.oaarchive.model.FileRecord;
import com.yizhaoqi.oaarchive.template.NamingRenderer;
import com.yizhaoqi.oaarchive.template.TemplateConfig;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 根据模板计算新文件名和目标路径；完整路径超过 240 个字符时截断标题（技术文档 6.7.2）。
 */
public final class TargetPlanner {

    static final int MAX_PATH = 240;

    public record Plan(String fileName, Path relative, String archiveNo) {
    }

    private TargetPlanner() {
    }

    public static Plan plan(FileRecord f, TemplateConfig tpl, Path root, int seq) {
        NamingRenderer.Fields fields = new NamingRenderer.Fields(
                f.getDepartment(), f.getDocType(), f.getDocDate(), f.getTitle(), f.getOriginalName());
        Map<String, String> vars = NamingRenderer.variables(fields, tpl, seq);
        String ext = f.getExt() == null || f.getExt().isBlank() ? "" : "." + f.getExt().toLowerCase();
        List<String> dirs = NamingRenderer.dirSegments(tpl, vars);
        String name = NamingRenderer.fileName(tpl, vars) + ext;
        Path rel = relative(dirs, name);
        String title = vars.get("标题");
        while (root.resolve(rel).toString().length() > MAX_PATH && title.length() > 4) {
            title = title.substring(0, title.length() - Math.max(1, (title.length() - 4) / 4));
            vars.put("标题", title);
            name = NamingRenderer.fileName(tpl, vars) + ext;
            rel = relative(dirs, name);
        }
        return new Plan(name, rel, vars.get("档号"));
    }

    private static Path relative(List<String> dirs, String name) {
        Path p = Path.of("");
        for (String d : dirs) {
            p = p.resolve(d);
        }
        return p.resolve(name);
    }

    /** “a.pdf”→“a(2).pdf” */
    public static String withSuffix(String name, int n) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) + "(" + n + ")" + name.substring(dot) : name + "(" + n + ")";
    }
}
