package com.securetravels.crm.task;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface TaskRepository extends JpaRepository<Task, UUID> {

    List<Task> findTop20ByAssigneeIdAndStatusOrderByDueAtAsc(UUID assigneeId, Task.Status status);

    List<Task> findTop20ByAssigneeIdOrderByDueAtAsc(UUID assigneeId);

    List<Task> findTop50ByStatusOrderByDueAtAsc(Task.Status status);

    List<Task> findTop50ByOrderByDueAtAsc();

    List<Task> findByLeadIdOrderByDueAtAsc(UUID leadId);

    /** Pending tasks whose due instant has passed (overdue sweep). */
    List<Task> findByStatusAndDueAtBefore(Task.Status status, Instant before);

    /** Tasks still pending at or past SLA (escalation sweep). */
    List<Task> findByStatusInAndSlaDeadlineBefore(List<Task.Status> statuses, Instant before);

    boolean existsByLeadIdAndTypeAndStatusIn(UUID leadId, Task.Type type, List<Task.Status> statuses);

    long countByLeadIdAndType(UUID leadId, Task.Type type);

    List<Task> findByLeadIdAndTypeAndStatusIn(UUID leadId, Task.Type type, List<Task.Status> statuses);

    /** All tasks of a lead in the given states (used by the booking-confirmed sweep). */
    List<Task> findByLeadIdAndStatusIn(UUID leadId, List<Task.Status> statuses);

    /** Payment-reminder dedupe/cancel for bookings (Module 5). */
    boolean existsByBookingIdAndTypeAndStatusIn(UUID bookingId, Task.Type type, List<Task.Status> statuses);

    List<Task> findByBookingIdAndTypeAndStatusIn(UUID bookingId, Task.Type type, List<Task.Status> statuses);
}