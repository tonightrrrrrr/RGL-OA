package com.yizhaoqi.oaarchive.common;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;

/**
 * 版本更新记录（resources/changelog.json），供“版本更新”页面和更新公告使用。
 * 最新版本放在最前面；它的版本号必须和程序版本一致（VersionSyncTest 会检查）。
 */
@Service
public class ChangelogService {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Highlight(String icon, String title, String desc) {
    }

    /** type：new 新增、improve 优化、fix 修复 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Change(String type, String text) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Release(String version, String date, String title, String summary, String note,
                          List<Highlight> highlights, List<Change> changes) {
    }

    private final List<Release> releases;

    public ChangelogService(ObjectMapper mapper) {
        try (InputStream in = ChangelogService.class.getResourceAsStream("/changelog.json")) {
            if (in == null) {
                throw new IllegalStateException("缺少 changelog.json");
            }
            releases = List.copyOf(mapper.readValue(in, new TypeReference<List<Release>>() { }));
        } catch (IOException e) {
            throw new UncheckedIOException("changelog.json 格式不正确", e);
        }
    }

    public List<Release> releases() {
        return releases;
    }
}
