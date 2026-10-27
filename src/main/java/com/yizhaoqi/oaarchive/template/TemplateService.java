package com.yizhaoqi.oaarchive.template;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.oaarchive.common.BizException;
import com.yizhaoqi.oaarchive.model.TemplateEntity;
import com.yizhaoqi.oaarchive.repository.TemplateRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 模板管理（技术文档 6.6.4）：内置两套只读模板，用户可以复制后修改、导入、导出。
 */
@Slf4j
@Service
public class TemplateService {

    public static final String DEFAULT_NAME = "默认模板";
    public static final String SAMPLE_STYLE_NAME = "赛题示例风格";

    private final TemplateRepository repo;
    private final ObjectMapper mapper;

    public TemplateService(TemplateRepository repo, ObjectMapper mapper) {
        this.repo = repo;
        this.mapper = mapper;
    }

    /** 每次启动都用程序自带的内容刷新内置模板，升级程序后内置模板自动更新 */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void seedBuiltin() {
        TemplateConfig base = loadBuiltinDefault();
        upsertBuiltin(DEFAULT_NAME, base);
        TemplateConfig sample = loadBuiltinDefault();
        sample.setNamingPattern("{部门}_{标题}_{日期}"); // 和赛题示例文件的命名方式一致
        upsertBuiltin(SAMPLE_STYLE_NAME, sample);
        addResumeRules(base);
    }

    /**
     * v1.8 以前新建的自定义模板没有“简历”类型和岗位规则：从内置模板补上，部门不在这个模板里的规则不补。
     * 只在模板 JSON 里还没有 positionRules 这一项时补一次；之后用户删掉也不会再加回来。
     */
    private void addResumeRules(TemplateConfig builtin) {
        for (TemplateEntity e : repo.findAll()) {
            if (e.isBuiltin() || e.getContentJson() == null || e.getContentJson().contains("\"positionRules\"")) {
                continue;
            }
            TemplateConfig c = parse(e.getContentJson());
            java.util.Set<String> depts = new java.util.HashSet<>();
            c.getDepartments().forEach(d -> depts.add(d.getName()));
            c.setPositionRules(new ArrayList<>(builtin.getPositionRules().stream().filter(r -> depts.contains(r.getDepartment())).toList()));
            TemplateConfig.TypeDef resume = builtin.findType("简历");
            boolean codeTaken = resume != null && c.getDocTypes().stream().anyMatch(t -> resume.getCode().equals(t.getCode()));
            if (resume != null && c.findType("简历") == null && !codeTaken) {
                int other = c.getDocTypes().indexOf(c.findType(TemplateConfig.OTHER_TYPE));
                c.getDocTypes().add(other < 0 ? c.getDocTypes().size() : other, resume);
            }
            e.setContentJson(toJson(c));
            e.setUpdatedAt(LocalDateTime.now());
            repo.save(e);
            log.info("模板“{}”补上了简历类型和 {} 条岗位规则（v1.8）", e.getName(), c.getPositionRules().size());
        }
    }

    private void upsertBuiltin(String name, TemplateConfig cfg) {
        TemplateEntity e = repo.findByName(name).orElseGet(TemplateEntity::new);
        e.setName(name);
        e.setBuiltin(true);
        e.setContentJson(toJson(cfg));
        if (e.getCreatedAt() == null) {
            e.setCreatedAt(LocalDateTime.now());
        }
        e.setUpdatedAt(LocalDateTime.now());
        repo.save(e);
    }

    public TemplateConfig loadBuiltinDefault() {
        try (InputStream in = new ClassPathResource("templates-builtin/default.json").getInputStream()) {
            return mapper.readValue(in, TemplateConfig.class);
        } catch (IOException e) {
            throw new IllegalStateException("内置模板读取失败", e);
        }
    }

    public List<TemplateEntity> list() {
        return repo.findAllByOrderByBuiltinDescIdAsc();
    }

    public TemplateEntity get(Long id) {
        return repo.findById(id).orElseThrow(() -> new BizException("模板不存在"));
    }

    public TemplateEntity getOrDefault(Long id) {
        if (id != null) {
            return get(id);
        }
        return repo.findByName(DEFAULT_NAME).orElseThrow(() -> new BizException("默认模板不存在"));
    }

    /**
     * 只解析 JSON，不做规则校验。批次快照、智能检查都用它：
     * 校验规则以后变严格时，已经导入的批次仍然能正常读取。
     */
    public TemplateConfig parse(String json) {
        if (json == null || json.isBlank()) {
            throw new BizException("模板内容不能为空");
        }
        try {
            return mapper.readValue(json, TemplateConfig.class);
        } catch (JsonProcessingException e) {
            throw new BizException("模板 JSON 格式不正确：" + e.getOriginalMessage());
        }
    }

    @Transactional
    public TemplateEntity create(String name, TemplateConfig cfg) {
        if (name == null || name.isBlank()) {
            throw new BizException("请填写模板名称");
        }
        if (repo.findByName(name.strip()).isPresent()) {
            throw new BizException("已有同名模板：" + name);
        }
        validate(cfg);
        TemplateEntity e = new TemplateEntity();
        e.setName(name.strip());
        e.setBuiltin(false);
        e.setContentJson(toJson(cfg));
        e.setCreatedAt(LocalDateTime.now());
        e.setUpdatedAt(LocalDateTime.now());
        return repo.save(e);
    }

    @Transactional
    public TemplateEntity update(Long id, String name, TemplateConfig cfg) {
        TemplateEntity e = get(id);
        if (e.isBuiltin()) {
            throw new BizException("内置模板不能修改，请先复制一份");
        }
        validate(cfg);
        if (name != null && !name.isBlank() && !name.strip().equals(e.getName())) {
            if (repo.findByName(name.strip()).isPresent()) {
                throw new BizException("已有同名模板：" + name);
            }
            e.setName(name.strip());
        }
        e.setContentJson(toJson(cfg));
        e.setUpdatedAt(LocalDateTime.now());
        return repo.save(e);
    }

    @Transactional
    public TemplateEntity copy(Long id) {
        TemplateEntity src = get(id);
        return createFrom(uniqueName(src.getName() + " - 副本"), parse(src.getContentJson()));
    }

    /** 新建模板：基于某个已有模板，或者 baseId 为空时从空白模板开始 */
    @Transactional
    public TemplateEntity createFromBase(String name, Long baseId) {
        TemplateConfig cfg = baseId == null ? blank() : parse(get(baseId).getContentJson());
        return createFrom(name == null || name.isBlank() ? uniqueName("新模板") : name.strip(), cfg);
    }

    /** 复制、新建时不做“错误”校验：旧模板里的问题留到用户修改后保存时再提示 */
    private TemplateEntity createFrom(String name, TemplateConfig cfg) {
        if (repo.findByName(name).isPresent()) {
            throw new BizException("已有同名模板：" + name);
        }
        TemplateEntity e = new TemplateEntity();
        e.setName(name);
        e.setBuiltin(false);
        e.setContentJson(toJson(cfg));
        e.setCreatedAt(LocalDateTime.now());
        e.setUpdatedAt(LocalDateTime.now());
        return repo.save(e);
    }

    private String uniqueName(String base) {
        String name = base;
        for (int i = 2; repo.findByName(name).isPresent(); i++) {
            name = base + i;
        }
        return name;
    }

    @Transactional
    public void delete(Long id) {
        TemplateEntity e = get(id);
        if (e.isBuiltin()) {
            throw new BizException("内置模板不能删除");
        }
        repo.delete(e);
    }

    public String toJson(TemplateConfig cfg) {
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(cfg);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 保存前校验：智能检查里的“错误”级问题会阻止保存，“警告”和“提示”不阻止 */
    static void validate(TemplateConfig cfg) {
        List<TemplateAnalyzer.Issue> errors = TemplateAnalyzer.lint(cfg).stream()
                .filter(i -> i.level() == TemplateAnalyzer.Level.ERROR).toList();
        if (!errors.isEmpty()) {
            String more = errors.size() > 1 ? "（共 " + errors.size() + " 个错误，详见“智能检查”）" : "";
            throw new BizException("模板有错误，无法保存：" + errors.get(0).message() + more);
        }
    }

    /** 空白模板：只有一个部门、通知和其他两个类型，以及常用的命名规则 */
    public TemplateConfig blank() {
        TemplateConfig c = new TemplateConfig();
        c.setCompanyName("");
        TemplateConfig.DeptDef d = new TemplateConfig.DeptDef();
        d.setName("综合部");
        d.setCode("ZH");
        c.getDepartments().add(d);
        TemplateConfig.TypeDef notice = new TemplateConfig.TypeDef();
        notice.setName("通知");
        notice.setCode("TZ");
        notice.getSuffixes().add("通知");
        TemplateConfig.TypeDef other = new TemplateConfig.TypeDef();
        other.setName(TemplateConfig.OTHER_TYPE);
        other.setCode("QT");
        c.getDocTypes().add(notice);
        c.getDocTypes().add(other);
        return c;
    }
}
