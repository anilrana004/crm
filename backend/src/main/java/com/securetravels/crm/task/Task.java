package com.securetravels.crm.task;

import com.securetravels.crm.common.audit.CreatedUpdated;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Automation task: 5-min first contact, Day +1/+3/+8/+15 cadence, quotation,
 * payment reminders, ops handoffs. SLA breaches escalate the task to a
 * MANAGER (task row + notification) instead of being silently overdue.
 */
@Entity
@Table(name = "tasks", indexes = {
        @Index(name = "idx_tasks_assignee_due", columnList = "assignee_id, status, due_at"),
        @Index(name = "idx_tasks_lead", columnList = "lead_id")
})
public class Task extends CreatedUpdated {

    public enum Type {
        INITIAL_CALL, FOLLOW_UP_1D, FOLLOW_UP_3D, FOLLOW_UP_8D, FOLLOW_UP_15D,
        QUOTATION, PAYMENT_REMINDER, OPS, REVIEW, CUSTOM
    }
    public enum Status { PENDING, COMPLETED, OVERDUE, CANCELLED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "lead_id")
    private UUID leadId;

    @Column(name = "booking_id")
    private UUID bookingId;

    @Column(name = "assignee_id", nullable = false)
    private UUID assigneeId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Type type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.PENDING;

    @Column(name = "due_at", nullable = false)
    private Instant dueAt;

    @Column(name = "sla_deadline")
    private Instant slaDeadline;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "escalated_at")
    private Instant escalatedAt;

    @Column(columnDefinition = "text")
    private String notes;

    protected Task() {}

    public Task(UUID leadId, UUID assigneeId, Type type, Instant dueAt, Instant slaDeadline) {
        this.leadId = leadId;
        this.assigneeId = assigneeId;
        this.type = type;
        this.dueAt = dueAt;
        this.slaDeadline = slaDeadline;
    }

    public UUID getId() { return id; }
    public UUID getLeadId() { return leadId; }
    public UUID getBookingId() { return bookingId; }
    public void setBookingId(UUID bookingId) { this.bookingId = bookingId; }
    public UUID getAssigneeId() { return assigneeId; }
    public Type getType() { return type; }
    public Status getStatus() { return status; }
    public Instant getDueAt() { return dueAt; }
    public Instant getSlaDeadline() { return slaDeadline; }
    public Instant getCompletedAt() { return completedAt; }
    public Instant getEscalatedAt() { return escalatedAt; }
    public String getNotes() { return notes; }

    public void complete(Instant at) {
        this.status = Status.COMPLETED;
        this.completedAt = at;
    }
    public void cancel() { this.status = Status.CANCELLED; }
    public void markOverdue(Instant at) {
        if (this.status == Status.PENDING || this.status == Status.OVERDUE) {
            this.status = Status.OVERDUE;
        }
    }
    public void escalate(Instant at) { this.escalatedAt = at; }
}