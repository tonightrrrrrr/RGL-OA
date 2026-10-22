package com.yizhaoqi.oaarchive;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.oaarchive.common.ChangelogService;
import com.yizhaoqi.oaarchive.common.UpdateService;
import com.yizhaoqi.oaarchive.controller.SystemController;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 版本号同步检查：程序和技术难点文档每次一起升版本。下面几处有一处没改，测试就不通过。
 */
class VersionSyncTest {

    private static final List<String> TYPES = List.of("new", "improve", "fix");

    @Test
    void versionIsTheSameEverywhere() throws IOException {
        String v = SystemController.VERSION;
        assertThat(find("pom.xml", "<artifactId>oa-archive-agent</artifactId>\\s*<version>([^<]+)</version>"))
                .as("pom.xml 的 <version>").isEqualTo(v);
        assertThat(find("packaging/package.ps1", "\\$Version = '([^']+)'"))
                .as("packaging/package.ps1 的 $Version").isEqualTo(v);
        assertThat(find("docs/技术难点与解决方案.md", "\\| 文档版本 \\| v([^ |]+) \\|"))
                .as("技术难点文档的版本").isEqualTo(v);
        assertThat(read("README.md")).as("README 里的 zip 文件名").contains("OA-Archive-Agent-v" + v + "-win64.zip");
        assertThat(new ChangelogService(new ObjectMapper()).releases().get(0).version())
                .as("changelog.json 第一条（最新版本）").isEqualTo(v);
    }

    @Test
    void changelogIsWellFormed() {
        List<ChangelogService.Release> list = new ChangelogService(new ObjectMapper()).releases();
        assertThat(list).isNotEmpty();
        Set<String> seen = new HashSet<>();
        String prev = null;
        for (ChangelogService.Release r : list) {
            assertThat(seen.add(r.version())).as("版本 %s 重复", r.version()).isTrue();
            assertThat(r.version()).as("版本号格式").matches("\\d+\\.\\d+(\\.\\d+)?");
            if (prev != null) {
                // 按段比较数字：1.5.1 > 1.5 > 1.4
                assertThat(UpdateService.compare(r.version(), prev)).as("版本要从新到旧排列：%s 应在 %s 之后", r.version(), prev).isNegative();
            }
            prev = r.version();
            assertThat(r.date()).as("%s 的日期", r.version()).matches("\\d{4}-\\d{2}-\\d{2}");
            assertThat(r.title()).as("%s 的标题", r.version()).isNotBlank();
            assertThat(r.highlights()).as("%s 的亮点", r.version()).isNotEmpty();
            assertThat(r.changes()).as("%s 的更新内容", r.version()).isNotEmpty()
                    .allSatisfy(c -> {
                        assertThat(TYPES).contains(c.type());
                        assertThat(c.text()).isNotBlank();
                    });
        }
    }

    private static String find(String file, String regex) throws IOException {
        Matcher m = Pattern.compile(regex).matcher(read(file));
        assertThat(m.find()).as("%s 里找不到版本号", file).isTrue();
        return m.group(1).strip();
    }

    private static String read(String file) throws IOException {
        return Files.readString(Path.of(file), StandardCharsets.UTF_8);
    }
}
