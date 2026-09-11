package com.securetravels.crm.trip;

import com.securetravels.crm.common.audit.AuditAction;
import com.securetravels.crm.common.audit.AuditService;
import com.securetravels.crm.common.exception.BadRequestException;
import com.securetravels.crm.common.exception.ForbiddenException;
import com.securetravels.crm.common.exception.NotFoundException;
import com.securetravels.crm.trip.dto.GuideCreateRequest;
import com.securetravels.crm.trip.dto.GuideResponse;
import com.securetravels.crm.trip.dto.GuideUpdateRequest;
import com.securetravels.crm.user.Role;
import com.securetravels.crm.user.UserPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Service
public class GuideService {

    private final GuideRepository guides;
    private final AuditService auditService;

    public GuideService(GuideRepository guides, AuditService auditService) {
        this.guides = guides;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<GuideResponse> list(boolean activeOnly) {
        List<Guide> result = activeOnly
                ? guides.findAllByActiveTrueOrderByFullNameAsc()
                : guides.findAllByOrderByFullNameAsc();
        return result.stream().map(this::toResponse).toList();
    }

    @Transactional
    public GuideResponse create(GuideCreateRequest request, UserPrincipal caller) {
        requireManager(caller);
        Guide guide = new Guide(request.fullName(), request.phone(), request.dailyRate());
        Guide saved = guides.save(guide);
        auditService.record("GUIDE", saved.getId(), AuditAction.CREATE, "full_name", null, saved.getFullName());
        return toResponse(saved);
    }

    @Transactional
    public GuideResponse update(UUID id, GuideUpdateRequest request, UserPrincipal caller) {
        requireManager(caller);
        Guide guide = guides.findById(id).orElseThrow(() -> new NotFoundException("Guide not found: " + id));

        if (request.fullName() != null && !request.fullName().equals(guide.getFullName())) {
            auditService.record("GUIDE", guide.getId(), AuditAction.UPDATE, "full_name",
                    guide.getFullName(), request.fullName());
            guide.setFullName(request.fullName());
        }
        if (request.phone() != null && !request.phone().equals(guide.getPhone())) {
            auditService.record("GUIDE", guide.getId(), AuditAction.UPDATE, "phone",
                    guide.getPhone(), request.phone());
            guide.setPhone(request.phone());
        }
        if (request.dailyRate() != null && !request.dailyRate().equals(guide.getDailyRate())) {
            auditService.record("GUIDE", guide.getId(), AuditAction.UPDATE, "daily_rate",
                    guide.getDailyRate() == null ? null : guide.getDailyRate().toString(),
                    request.dailyRate().toString());
            guide.setDailyRate(request.dailyRate());
        }
        if (request.active() != null && request.active() != guide.isActive()) {
            auditService.record("GUIDE", guide.getId(), AuditAction.STATUS_CHANGE, "active",
                    String.valueOf(guide.isActive()), String.valueOf(request.active()));
            guide.setActive(request.active());
        }

        guides.save(guide);
        return toResponse(guide);
    }

    private static void requireManager(UserPrincipal caller) {
        if (caller.role() != Role.MANAGER && caller.role() != Role.ADMIN && caller.role() != Role.CEO) {
            throw new ForbiddenException("Only managers can manage guides");
        }
    }

    private GuideResponse toResponse(Guide guide) {
        return new GuideResponse(guide.getId(), guide.getFullName(), guide.getPhone(),
                guide.getDailyRate(), guide.isActive(), guide.getCreatedAt());
    }
}