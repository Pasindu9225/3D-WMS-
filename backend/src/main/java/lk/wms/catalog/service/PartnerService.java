package lk.wms.catalog.service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lk.wms.catalog.domain.Partner;
import lk.wms.catalog.repo.PartnerRepository;
import lk.wms.common.audit.AuditService;
import lk.wms.common.errors.ApiException;
import lk.wms.common.security.CurrentUser;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

/** Suppliers now, customers in v1.0 (v0.4 task 2). */
@Service
public class PartnerService {

    public record PartnerView(String id, String type, String code, String name, String contactName, String phone,
            String email, String address, int version) {
    }

    public record PartnerSpec(String name, String contactName, String phone, String email, String address) {
    }

    /** One CSV row, already validated field by field. */
    public record ImportRow(int row, String code, PartnerSpec spec) {
    }

    private final PartnerRepository partners;
    private final AuditService audit;

    public PartnerService(PartnerRepository partners, AuditService audit) {
        this.partners = partners;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public Page<PartnerView> list(Partner.Type type, String query, Pageable pageable) {
        return partners.search(type, ProductService.likeText(query), pageable).map(PartnerService::view);
    }

    @Transactional
    public PartnerView create(CurrentUser cu, Partner.Type type, String code, PartnerSpec s) {
        Partner p = new Partner(type, code);
        apply(p, s);
        try {
            p = partners.saveAndFlush(p);
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(HttpStatus.CONFLICT, "CODE_IN_USE", "Another " + type.name().toLowerCase() + " already uses this code");
        }
        audit.record(cu.tenantId(), cu.userId(), "PARTNER_CREATED", "partner", p.getPublicId(), null, view(p));
        return view(p);
    }

    @Transactional
    public PartnerView update(CurrentUser cu, Partner.Type type, String publicId, int version, PartnerSpec s) {
        Partner p = find(type, publicId);
        if (p.getVersion() != version) {
            throw new ApiException(HttpStatus.CONFLICT, "PARTNER_CHANGED", "Someone else changed this record; reload it");
        }
        PartnerView before = view(p);
        apply(p, s);
        partners.flush();
        audit.record(cu.tenantId(), cu.userId(), "PARTNER_UPDATED", "partner", p.getPublicId(), before, view(p));
        return view(p);
    }

    @Transactional
    public void archive(CurrentUser cu, Partner.Type type, String publicId) {
        Partner p = find(type, publicId);
        p.archive();
        audit.record(cu.tenantId(), cu.userId(), "PARTNER_ARCHIVED", "partner", p.getPublicId(), view(p), null);
    }

    /**
     * CSV import (SEC-46): creates new codes and updates existing ones, all or nothing. Rows are already checked
     * field by field; a code repeated in the file is an error. Nothing is saved when any row is wrong.
     */
    @Transactional
    public ImportResult importRows(CurrentUser cu, Partner.Type type, List<ImportRow> rows, List<ImportResult.RowError> errors) {
        Map<String, Integer> firstRow = new java.util.HashMap<>();
        for (ImportRow r : rows) {
            Integer seen = firstRow.putIfAbsent(r.code(), r.row());
            if (seen != null) {
                errors.add(new ImportResult.RowError(r.row(), "code", "Code " + r.code() + " is already on row " + seen));
            }
        }
        Map<String, Partner> existing = partners.findByTypeAndCodeIn(type, firstRow.keySet()).stream()
                .collect(Collectors.toMap(Partner::getCode, Function.identity()));
        int created = 0;
        int updated = 0;
        if (errors.isEmpty()) {
            for (ImportRow r : rows) {
                Partner p = existing.get(r.code());
                if (p == null) {
                    p = new Partner(type, r.code());
                    created++;
                } else {
                    updated++;
                }
                apply(p, r.spec());
                partners.save(p);
            }
        }
        ImportResult result = ImportResult.of(created, updated, errors);
        if (!result.saved()) {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            return result;
        }
        audit.record(cu.tenantId(), cu.userId(), "PARTNERS_IMPORTED", "partner", null, null,
                Map.of("type", type.name(), "created", created, "updated", updated));
        return result;
    }

    private static void apply(Partner p, PartnerSpec s) {
        p.set(s.name().trim(), blankToNull(s.contactName()), blankToNull(s.phone()), blankToNull(s.email()),
                blankToNull(s.address()));
    }

    static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    private Partner find(Partner.Type type, String publicId) {
        return partners.findByPublicIdAndType(publicId, type)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PARTNER_NOT_FOUND", "Not found"));
    }

    static PartnerView view(Partner p) {
        return new PartnerView(p.getPublicId(), p.getType().name(), p.getCode(), p.getName(), p.getContactName(),
                p.getPhone(), p.getEmail(), p.getAddress(), p.getVersion());
    }
}
