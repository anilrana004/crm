package com.securetravels.crm.vendors;

import com.securetravels.crm.common.audit.AuditAction;
import com.securetravels.crm.common.audit.AuditService;
import com.securetravels.crm.common.exception.BadRequestException;
import com.securetravels.crm.common.exception.ForbiddenException;
import com.securetravels.crm.common.exception.NotFoundException;
import com.securetravels.crm.common.util.XssSanitizer;
import com.securetravels.crm.user.Role;
import com.securetravels.crm.user.UserPrincipal;
import com.securetravels.crm.vendors.dto.VendorCreateRequest;
import com.securetravels.crm.vendors.dto.VendorResponse;
import com.securetravels.crm.vendors.dto.VendorUpdateRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Service
public class VendorService {

    private final VendorRepository vendors;
    private final AuditService auditService;

    public VendorService(VendorRepository vendors, AuditService auditService) {
        this.vendors = vendors;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<VendorResponse> list(Vendor.Category category, boolean activeOnly) {
        List<Vendor> result;
        if (category != null) {
            result = activeOnly
                    ? vendors.findAllByActiveTrueAndCategoryOrderByNameAsc(category)
                    : vendors.findAllByCategoryOrderByNameAsc(category);
        } else {
            result = activeOnly
                    ? vendors.findAllByActiveTrueOrderByNameAsc()
                    : vendors.findAllByOrderByNameAsc();
        }
        return result.stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public VendorResponse get(UUID id) {
        return toResponse(vendors.findById(id)
                .orElseThrow(() -> new NotFoundException("Vendor not found: " + id)));
    }

    @Transactional
    public VendorResponse create(VendorCreateRequest request, UserPrincipal caller) {
        requireManager(caller);
        Vendor vendor = new Vendor();
        vendor.setCategory(request.category());
        vendor.setName(XssSanitizer.text(request.name()).trim());
        vendor.setPhone(request.phone());
        vendor.setEmail(request.email());
        vendor.setCity(XssSanitizer.text(request.city()));
        vendor.setGstin(normalizeGstin(request.gstin()));
        vendor.setBankAccountRef(request.bankAccountRef());
        vendor.setDailyRate(request.dailyRate());
        vendor.setNotes(XssSanitizer.text(request.notes()));
        vendor.setActive(true);

        Vendor saved = vendors.save(vendor);
        auditService.record("VENDOR", saved.getId(), AuditAction.CREATE, "name", null, saved.getName());
        return toResponse(saved);
    }

    @Transactional
    public VendorResponse update(UUID id, VendorUpdateRequest request, UserPrincipal caller) {
        requireManager(caller);
        Vendor vendor = vendors.findById(id)
                .orElseThrow(() -> new NotFoundException("Vendor not found: " + id));

        if (request.category() != null && request.category() != vendor.getCategory()) {
            auditChange(vendor, "category", vendor.getCategory().name(), request.category().name(), v -> vendor.setCategory(Vendor.Category.valueOf(v)));
        }
        if (request.name() != null) {
            String sanitized = XssSanitizer.text(request.name()).trim();
            auditChange(vendor, "name", vendor.getName(), sanitized, vendor::setName);
        }
        auditChange(vendor, "phone", vendor.getPhone(), request.phone(), vendor::setPhone);
        auditChange(vendor, "email", vendor.getEmail(), request.email(), vendor::setEmail);
        auditChange(vendor, "city", vendor.getCity(), XssSanitizer.text(request.city()), vendor::setCity);
        String gstin = normalizeGstin(request.gstin());
        auditChange(vendor, "gstin", vendor.getGstin(), gstin, vendor::setGstin);
        auditChange(vendor, "bank_account_ref", vendor.getBankAccountRef(), request.bankAccountRef(), vendor::setBankAccountRef);
        if (request.dailyRate() != null && !request.dailyRate().equals(vendor.getDailyRate())) {
            auditChange(vendor, "daily_rate",
                    vendor.getDailyRate() == null ? null : vendor.getDailyRate().toString(),
                    request.dailyRate().toString(), v -> vendor.setDailyRate(new BigDecimal(v)));
        }
        auditChange(vendor, "notes", vendor.getNotes(), XssSanitizer.text(request.notes()), vendor::setNotes);
        if (request.active() != null && request.active() != vendor.isActive()) {
            auditService.record("VENDOR", vendor.getId(), AuditAction.STATUS_CHANGE, "active",
                    String.valueOf(vendor.isActive()), String.valueOf(request.active()));
            vendor.setActive(request.active());
        }

        vendors.save(vendor);
        return toResponse(vendor);
    }

    private void auditChange(Vendor vendor, String field, String oldValue, String newValue,
                             java.util.function.Consumer<String> apply) {
        if (newValue == null || newValue.equals(oldValue)) return;
        apply.accept(newValue);
        auditService.record("VENDOR", vendor.getId(), AuditAction.UPDATE, field, oldValue, newValue);
    }

    private static String normalizeGstin(String gstin) {
        if (gstin == null) return null;
        String normalized = gstin.trim().toUpperCase();
        if (!normalized.matches("^[0-9A-Z]{15}$")) {
            throw new BadRequestException("GSTIN must be exactly 15 alphanumeric characters");
        }
        return normalized;
    }

    private static void requireManager(UserPrincipal caller) {
        if (caller.role() != Role.MANAGER && caller.role() != Role.ADMIN && caller.role() != Role.CEO) {
            throw new ForbiddenException("Only managers can manage the vendor catalogue");
        }
    }

    private VendorResponse toResponse(Vendor vendor) {
        return new VendorResponse(vendor.getId(), vendor.getCategory(), vendor.getName(),
                vendor.getPhone(), vendor.getEmail(), vendor.getCity(), vendor.getGstin(),
                vendor.getBankAccountRef(), vendor.getDailyRate(), vendor.getNotes(),
                vendor.isActive(), vendor.getCreatedAt(), vendor.getUpdatedAt());
    }
}