package com.securetravels.crm.task;

import com.securetravels.crm.common.notify.EmailNotifier;
import com.securetravels.crm.lead.Lead;
import com.securetravels.crm.notification.Notification;
import com.securetravels.crm.notification.NotificationRepository;
import com.securetravels.crm.user.Role;
import com.securetravels.crm.user.User;
import com.securetravels.crm.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
*  Module 2 — follow-up automation rules:
 *   • lead created            → INITIAL_CALL task, due +5 min, SLA +1 h
 *   • lead → INTERESTED       → FOLLOW_UP_1D/3D/8D/15D (from lead creation:
 *                              cumulative day-offsets +1, +3, +8, +15 per spec 19.2)
 *   • lead → QUOTATION_SENT   → QUOTATION task, due +2 days
 * Each task raises an in-app notification for the assignee plus an email via
 * the stub notifier. Re-entering a status re-schedules (old open tasks of
 * that kind are cancelled first) so batches never duplicate.
 */
@Service
public class FollowUpAutomation {

    private static final Logger log = LoggerFactory.getLogger(FollowUpAutomation.class);

    private static final String APP_URL = "http://localhost:3000";

    private final TaskRepository tasks;
    private final UserRepository users;
    private final NotificationRepository notifications;
    private final EmailNotifier email;

    public FollowUpAutomation(TaskRepository tasks, UserRepository users,
                              NotificationRepository notifications, EmailNotifier email) {
        this.tasks = tasks;
        this.users = users;
        this.notifications = notifications;
        this.email = email;
    }

    @Transactional
    public void onLeadCreated(UUID leadId, UUID assigneeId) {
        scheduleOne(leadId, assigneeId, Task.Type.INITIAL_CALL, Instant.now().plus(5, ChronoUnit.MINUTES),
                1, "Call within 5 minutes",
                "New lead assigned to you — call the customer now.");
    }

    @Transactional
    public void onInterested(Lead lead) {
        cancelOpenFollowUps(lead.getId());
        Instant base = base(lead);
        for (int day : new int[]{1, 3, 8, 15}) {
            scheduleOne(lead.getId(), lead.getOwnerId(), Task.Type.valueOf("FOLLOW_UP_" + day + "D"),
                    base.plus(day, ChronoUnit.DAYS), 24, "Follow-up day +" + day,
                    "Scheduled follow-up for " + lead.getCustomerName() + " is due today.");
        }
    }

    @Transactional
    public void onQuotationSent(Lead lead) {
        cancelOpen(lead.getId(), Task.Type.QUOTATION);
        scheduleOne(lead.getId(), lead.getOwnerId(), Task.Type.QUOTATION,
                base(lead).plus(2, ChronoUnit.DAYS), 24, "Quotation follow-up",
                "Send / confirm the quotation for " + lead.getCustomerName() + ".");
    }

    /** A booking was confirmed — every open follow-up task on the lead is cancelled. */
    @Transactional
    public void onBooked(Lead lead) {
        cancelAllOpen(lead.getId());
        String title = "Booking confirmed — follow-up closed";
        String body = "No further follow-up needed for " + lead.getCustomerName() + ".";
        notifications.save(new Notification(lead.getOwnerId(), Notification.Channel.IN_APP, title, body,
                leadPath(lead.getId())));
        log.info("[automation] lead={} booked; outstanding follow-ups cancelled", lead.getId());
    }

    /** Escalation support used by the overdue sweep (SLA breach -> MANAGER). */
    @Transactional
    public void escalateToManager(Task task, String reason) {
        User manager = users.findFirstByRoleOrderByCreatedAtAsc(Role.MANAGER).orElse(null);
        UUID target = manager == null ? task.getAssigneeId() : manager.getId();
        task.escalate(Instant.now());
        String title = "SLA breach: " + humanType(task.getType());
        notifications.save(new Notification(target, Notification.Channel.IN_APP, title,
                humanType(task.getType()) + " task breached its SLA. " + reason
                        + " (due " + task.getDueAt() + ")",
                leadPath(task.getLeadId())));
        if (manager != null) {
            email.send(manager.getEmail(), title, reason + " " + APP_URL + leadPath(task.getLeadId()));
        }
        log.info("[automation] escalated task={} to={} reason={}", task.getId(), target, reason);
    }

    private Instant base(Lead lead) {
        return lead.getCreatedAt() == null ? Instant.now() : lead.getCreatedAt();
    }

    private void cancelOpenFollowUps(UUID leadId) {
        for (Task.Type t : List.of(Task.Type.FOLLOW_UP_1D, Task.Type.FOLLOW_UP_3D,
                Task.Type.FOLLOW_UP_8D, Task.Type.FOLLOW_UP_15D)) {
            cancelOpen(leadId, t);
        }
    }

    private void cancelOpen(UUID leadId, Task.Type type) {
        List<Task.Status> open = List.of(Task.Status.PENDING, Task.Status.OVERDUE);
        for (Task existing : tasks.findByLeadIdAndTypeAndStatusIn(leadId, type, open)) {
            existing.cancel();
        }
    }

    private void cancelAllOpen(UUID leadId) {
        List<Task.Status> open = List.of(Task.Status.PENDING, Task.Status.OVERDUE);
        for (Task existing : tasks.findByLeadIdAndStatusIn(leadId, open)) {
            existing.cancel();
        }
    }

    private void scheduleOne(UUID leadId, UUID assigneeId, Task.Type type, Instant dueAt,
                             int slaHours, String title, String body) {
        Task task = tasks.save(new Task(leadId, assigneeId, type, dueAt,
                dueAt.plus(slaHours, ChronoUnit.HOURS)));
        notifications.save(new Notification(assigneeId, Notification.Channel.IN_APP, title, body,
                leadPath(leadId)));
        users.findById(assigneeId).ifPresent(user ->
                email.send(user.getEmail(), title, body + " " + APP_URL + leadPath(leadId)));
        log.info("[automation] lead={} type={} assignee={} dueAt={}", leadId, type, assigneeId, dueAt);
    }

    private String humanType(Task.Type type) {
        return switch (type) {
            case INITIAL_CALL -> "first call";
            case QUOTATION -> "quotation follow-up";
            case PAYMENT_REMINDER -> "payment reminder";
            case OPS -> "ops handoff";
            default -> "follow-up (" + type + ")";
        };
    }

    private String leadPath(UUID leadId) {
        return "/leads/" + leadId;
    }
}