package com.securetravels.crm.task.dto;

import com.securetravels.crm.task.Task;

import java.time.Instant;
import java.util.UUID;

public record TaskResponse(
        UUID id,
        UUID leadId,
        String customerName,
        UUID assigneeId,
        String assigneeName,
        Task.Type type,
        Task.Status status,
        Instant dueAt,
        Instant slaDeadline,
        Instant completedAt,
        Instant escalatedAt,
        String notes
) {
}