package com.securetravels.crm.common.audit;

import com.securetravels.crm.common.security.JwtService;
import com.securetravels.crm.user.UserPrincipal;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class AuditService {

    private final AuditLogRepository repository;

    public AuditService(AuditLogRepository repository) {
        this.repository = repository;
    }

    public void record(String entity, UUID entityId, AuditAction action, String field,
                       String oldValue, String newValue) {
        repository.save(new AuditLog(entity, entityId, action, field, oldValue, newValue, currentActorId()));
    }

    public void statusChange(String entity, UUID entityId, String field,
                             String oldValue, String newValue) {
        record(entity, entityId, AuditAction.STATUS_CHANGE, field, oldValue, newValue);
    }

    private UUID currentActorId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof UserPrincipal p) {
            return p.id();
        }
        return null;
    }
}