package com.securetravels.crm.task;

import com.securetravels.crm.common.security.CurrentUser;
import com.securetravels.crm.task.dto.TaskResponse;
import com.securetravels.crm.user.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    /** Task list. SALES/OPS see only their own; managers/admin/CEO see all and may filter. */
    @Operation(summary = "List follow-up tasks (SALES see own; managers see all)",
            security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    public List<TaskResponse> list(@RequestParam(required = false) UUID leadId,
                                   @RequestParam(required = false) Task.Status status,
                                   @CurrentUser UserPrincipal caller) {
        return taskService.list(leadId, status, caller);
    }

    @Operation(summary = "Mark a task completed", security = @SecurityRequirement(name = "bearerAuth"))
    @PatchMapping(path = "/{id}/complete", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    public TaskResponse complete(@PathVariable UUID id, @CurrentUser UserPrincipal caller) {
        return taskService.complete(id, caller);
    }
}