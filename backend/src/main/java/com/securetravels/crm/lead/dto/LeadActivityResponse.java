package com.securetravels.crm.lead.dto;

import com.securetravels.crm.common.audit.AuditAction;

import java.time.Instant;

/**
 * One row of a lead's activity timeline, pulled from audit_log (CREATE,
 * STATUS_CHANGE, field edits, notes). The Lead Detail page renders this
 * newest-first.
 */
public record LeadActivityResponse(
        Long id,
        AuditAction action,
        String field,
        String oldValue,
        String newValue,
        String actorName,
        Instant createdAt
) {
}