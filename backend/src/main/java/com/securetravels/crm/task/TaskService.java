package com.securetravels.crm.task;

import com.securetravels.crm.common.exception.ForbiddenException;
import com.securetravels.crm.common.exception.NotFoundException;
import com.securetravels.crm.lead.Lead;
import com.securetravels.crm.lead.LeadRepository;
import com.securetravels.crm.task.dto.TaskResponse;
import com.securetravels.crm.user.Role;
import com.securetravels.crm.user.User;
import com.securetravels.crm.user.UserPrincipal;
import com.securetravels.crm.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class TaskService {

    private static final Set<Role> SEES_ALL = EnumSet.of(Role.MANAGER, Role.ADMIN, Role.CEO);

    private final TaskRepository tasks;
    private final LeadRepository leads;
    private final UserRepository users;

    public TaskService(TaskRepository tasks, LeadRepository leads, UserRepository users) {
        this.tasks = tasks;
        this.leads = leads;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public List<TaskResponse> list(UUID leadId, Task.Status status, UserPrincipal caller) {
        List<Task> rows;
        if (leadId != null) {
            rows = tasks.findByLeadIdOrderByDueAtAsc(leadId);
        } else if (SEES_ALL.contains(caller.role())) {
            rows = status == null ? tasks.findTop50ByOrderByDueAtAsc()
                    : tasks.findTop50ByStatusOrderByDueAtAsc(status);
        } else {
            rows = status == null ? tasks.findTop20ByAssigneeIdOrderByDueAtAsc(caller.id())
                    : tasks.findTop20ByAssigneeIdAndStatusOrderByDueAtAsc(caller.id(), status);
        }
        return rows.stream()
                .filter(t -> SEES_ALL.contains(caller.role()) || t.getAssigneeId().equals(caller.id()))
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public TaskResponse complete(UUID id, UserPrincipal caller) {
        Task task = tasks.findById(id).orElseThrow(() -> new NotFoundException("Task not found: " + id));
        if (!SEES_ALL.contains(caller.role()) && !task.getAssigneeId().equals(caller.id())) {
            throw new ForbiddenException("You can only complete tasks assigned to you");
        }
        if (task.getStatus() != Task.Status.COMPLETED) {
            task.complete(Instant.now());
        }
        return toResponse(task);
    }

    private TaskResponse toResponse(Task task) {
        String customerName = task.getLeadId() == null ? null
                : leads.findById(task.getLeadId()).map(Lead::getCustomerName).orElse(null);
        String assigneeName = users.findById(task.getAssigneeId()).map(User::getFullName).orElse(null);
        return new TaskResponse(task.getId(), task.getLeadId(), customerName, task.getAssigneeId(),
                assigneeName, task.getType(), task.getStatus(), task.getDueAt(), task.getSlaDeadline(),
                task.getCompletedAt(), task.getEscalatedAt(), task.getNotes());
    }
}