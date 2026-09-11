package com.securetravels.crm.task;

import com.securetravels.crm.task.Task.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Module 2 — maintenance sweep. Every 5 minutes:
 *   1. PENDING tasks past due_at   → OVERDUE
 *   2. still-open tasks past sla_deadline → escalate to a MANAGER (one shot,
 *      guarded by escalated_at + non-COMPLETED) instead of silently decaying.
 * Directly invokable from tests (runSweep) for deterministic coverage.
 */
@Component
public class FollowUpSweep {

    private static final Logger log = LoggerFactory.getLogger(FollowUpSweep.class);

    private final TaskRepository tasks;
    private final FollowUpAutomation automation;

    public FollowUpSweep(TaskRepository tasks, FollowUpAutomation automation) {
        this.tasks = tasks;
        this.automation = automation;
    }

    @Scheduled(fixedDelay = 300_000, initialDelay = 60_000)
    @Transactional
    public void runSweep() {
        Instant now = Instant.now();
        int overdue = 0;
        for (Task t : tasks.findByStatusAndDueAtBefore(Status.PENDING, now)) {
            t.markOverdue(now);
            overdue++;
        }
        int escalated = 0;
        for (Task t : tasks.findByStatusInAndSlaDeadlineBefore(List.of(Status.PENDING, Status.OVERDUE), now)) {
            if (t.getEscalatedAt() != null || t.getStatus() == Status.COMPLETED) continue;
            automation.escalateToManager(t, "SLA deadline missed (due " + t.getDueAt() + ")");
            escalated++;
        }
        if (overdue > 0 || escalated > 0) {
            log.info("[sweep] markedOverdue={} escalated={}", overdue, escalated);
        }
    }
}