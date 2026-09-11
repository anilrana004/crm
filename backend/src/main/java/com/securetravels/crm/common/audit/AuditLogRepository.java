package com.securetravels.crm.common.audit;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

    /** Activity timeline for a single record (who did what, newest first). */
    List<AuditLog> findAllByEntityAndEntityIdOrderBySeqDesc(String entity, UUID entityId);
}