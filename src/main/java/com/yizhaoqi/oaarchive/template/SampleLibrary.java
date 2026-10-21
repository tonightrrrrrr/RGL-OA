package com.yizhaoqi.oaarchive.template;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.oaarchive.config.OaProperties;
import com.yizhaoqi.oaarchive.parser.ParserRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * 内置示例文件：赛题提供的 10 份 OA 文件及其标准答案（部门、类型、日期），打包在程序里。
 * 模板预览在还没有导入任何文件时，用它们演示规则修改的效果；有标准答案，所以能算出“识别错”的数量。
 */
@Slf4j
@Component
public class SampleLibrary {

    /** 一份示例：文件名、本地路径和标准答案 */
    public record Sample(String file, Path path, String department, String docType, String date) {
    }

    private record Label(String file, String department, String docType, String date) {
    }

    private final OaProperties props;
    private final ObjectMapper mapper;
    private volatile List<Sample> cached;

    public SampleLibrary(OaProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
    }

    /** 首次使用时把示例从程序包里解压到 data/samples（文件已存在且大小一致时跳过） */
    public synchronized List<Sample> samples() {
        if (cached != null) {
            return cached;
        }
        List<Sample> out = new ArrayList<>();
        Path dir = props.homePath().resolve("samples");
        try (InputStream in = new ClassPathResource("samples/labels.json").getInputStream()) {
            List<Label> labels = mapper.readValue(in, new TypeReference<>() {
            });
            Files.createDirectories(dir);
            for (Label l : labels) {
                ClassPathResource res = new ClassPathResource("samples/" + l.file());
                Path target = dir.resolve(l.file());
                if (!Files.exists(target) || Files.size(target) != res.contentLength()) {
                    try (InputStream src = res.getInputStream()) {
                        Files.copy(src, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
                out.add(new Sample(l.file(), target, l.department(), l.docType(), l.date()));
            }
        } catch (IOException e) {
            log.warn("内置示例文件加载失败：{}", e.getMessage());
            return List.of();
        }
        cached = List.copyOf(out);
        return cached;
    }

    public static String ext(String file) {
        return ParserRegistry.extension(file);
    }
}
