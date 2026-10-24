package com.yizhaoqi.oaarchive.ledger;

import com.yizhaoqi.oaarchive.model.LedgerEntry;
import com.yizhaoqi.oaarchive.repository.LedgerEntryRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * 台账查询（技术文档 6.8.2）：关键词模糊匹配 + 组合筛选 + 分页排序。数据量在数万条以内，LIKE 查询足够。
 */
@Service
public class LedgerService {

    private static final Set<String> SORTABLE = Set.of("id", "archiveNo", "title", "department", "docType", "docDate", "archivedAt", "size");

    private final LedgerEntryRepository repo;

    public LedgerService(LedgerEntryRepository repo) {
        this.repo = repo;
    }

    /** 查询条件；ids 不为空时只查这些台账记录（台账页勾选导出），其他条件同时生效 */
    public record Query(String keyword, String department, String docType, LocalDate dateFrom, LocalDate dateTo,
                        String format, String recognizeMode, boolean includeUndone, Collection<Long> ids) {

        public Query(String keyword, String department, String docType, LocalDate dateFrom, LocalDate dateTo,
                     String format, String recognizeMode, boolean includeUndone) {
            this(keyword, department, docType, dateFrom, dateTo, format, recognizeMode, includeUndone, null);
        }
    }

    public Page<LedgerEntry> search(Query q, int page, int size, String sort, boolean asc) {
        String field = SORTABLE.contains(sort) ? sort : "id";
        Sort s = asc ? Sort.by(field).ascending() : Sort.by(field).descending();
        return repo.findAll(spec(q), PageRequest.of(Math.max(page - 1, 0), Math.min(Math.max(size, 1), 500), s));
    }

    public List<LedgerEntry> all(Query q) {
        return repo.findAll(spec(q), Sort.by("id").ascending());
    }

    static Specification<LedgerEntry> spec(Query q) {
        return (root, cq, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (!q.includeUndone()) {
                ps.add(cb.isFalse(root.get("undone")));
            }
            if (q.keyword() != null && !q.keyword().isBlank()) {
                for (String word : q.keyword().strip().split("\\s+")) {
                    String like = "%" + word.toLowerCase().replace("%", "\\%").replace("_", "\\_") + "%";
                    List<Predicate> any = new ArrayList<>();
                    for (String f : List.of("title", "originalName", "newName", "keywords", "archiveNo", "contractNo",
                            "docNo", "partyA", "partyB", "department", "docType", "sealUnit")) {
                        any.add(cb.like(cb.lower(root.get(f)), like, '\\'));
                    }
                    ps.add(cb.or(any.toArray(new Predicate[0])));
                }
            }
            if (notBlank(q.department())) {
                ps.add(cb.equal(root.get("department"), q.department()));
            }
            if (notBlank(q.docType())) {
                ps.add(cb.equal(root.get("docType"), q.docType()));
            }
            if (notBlank(q.format())) {
                ps.add(cb.equal(root.get("format"), q.format()));
            }
            if (notBlank(q.recognizeMode())) {
                ps.add(cb.equal(root.get("recognizeMode"), q.recognizeMode()));
            }
            if (q.dateFrom() != null) {
                ps.add(cb.greaterThanOrEqualTo(root.get("docDate"), q.dateFrom()));
            }
            if (q.dateTo() != null) {
                ps.add(cb.lessThanOrEqualTo(root.get("docDate"), q.dateTo()));
            }
            if (q.ids() != null) {
                ps.add(q.ids().isEmpty() ? cb.disjunction() : root.get("id").in(q.ids()));
            }
            return cb.and(ps.toArray(new Predicate[0]));
        };
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
