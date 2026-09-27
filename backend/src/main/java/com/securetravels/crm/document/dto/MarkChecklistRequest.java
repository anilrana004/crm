package com.securetravels.crm.document.dto;

import com.securetravels.crm.document.ChecklistItem;
import com.securetravels.crm.document.ComplianceStatus;

import java.util.List;

/** Mark one or more checklist items on a traveller's checklist. */
public record MarkChecklistRequest(
        List<MarkChecklistItemRequest> items,
        String emergencyContactName,
        String emergencyContactPhone
) {
    public record MarkChecklistItemRequest(ChecklistItem item, ComplianceStatus status) {}
}