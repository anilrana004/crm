package com.securetravels.crm.document.dto;

import com.securetravels.crm.document.ChecklistItem;
import com.securetravels.crm.document.ComplianceStatus;

import java.util.UUID;

/** One checklist item as rendered on the compliance board. */
public record ComplianceItemView(
        ChecklistItem item,
        boolean required,
        ComplianceStatus status,
        String color,
        UUID documentId
) {
}