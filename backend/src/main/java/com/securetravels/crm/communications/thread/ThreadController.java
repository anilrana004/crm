package com.securetravels.crm.communications.thread;

import com.securetravels.crm.common.audit.AuditAction;
import com.securetravels.crm.common.audit.AuditService;
import com.securetravels.crm.common.security.CurrentUser;
import com.securetravels.crm.communications.thread.dto.ThreadResponse;
import com.securetravels.crm.communications.thread.dto.ThreadUpdateRequest;
import com.securetravels.crm.user.UserPrincipal;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/**
 * The unified inbox: every WhatsApp, email and SMS conversation in one list
 * (Phase 5 Module 2).
 *
 * <p>Read access is open to all staff roles — a sales rep must be able to see
 * what a customer said on any channel. Mutation is Manager+ because assigning a
 * teammate's inbox is a supervisory action.
 */
@RestController
@RequestMapping("/api/v1/threads")
public class ThreadController {

    private final CommunicationThreadService threads;
    private final AuditService auditService;

    public ThreadController(CommunicationThreadService threads, AuditService auditService) {
        this.threads = threads;
        this.auditService = auditService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('AGENT','MANAGER','ADMIN','CEO')")
    public ResponseEntity<Page<ThreadResponse>> inbox(
            @RequestParam(required = false) UUID assignedTo,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String channel,
            @RequestParam(defaultValue = "false") boolean unreadOnly,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @CurrentUser UserPrincipal caller) {

        ThreadStatus parsedStatus = status == null ? null
                : ThreadStatus.valueOf(status.toUpperCase(Locale.ROOT));
        CommunicationChannel parsedChannel = channel == null ? null
                : CommunicationChannel.valueOf(channel.toUpperCase(Locale.ROOT));

        Page<ThreadResponse> result = threads.inbox(assignedTo, parsedStatus, parsedChannel, unreadOnly,
                        PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100),
                                Sort.by(Sort.Direction.DESC, "lastMessageAt")))
                .map(ThreadController::toResponse);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('AGENT','MANAGER','ADMIN','CEO')")
    public ResponseEntity<ThreadResponse> get(@PathVariable UUID id) {
        return ResponseEntity.ok(toResponse(threads.get(id)));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN','CEO')")
    public ResponseEntity<ThreadResponse> update(@PathVariable UUID id,
                                                 @RequestBody ThreadUpdateRequest request,
                                                 @CurrentUser UserPrincipal caller) {
        if (request.assignedTo() != null) {
            threads.assign(id, request.assignedTo());
            auditService.record("COMMUNICATION_THREAD", id,
                    AuditAction.UPDATE,
                    "assigned_to", null, request.assignedTo().toString());
        }
        if (request.status() != null) {
            // Every status is set through the same door. This used to collapse
            // anything that was not CLOSED into OPEN, so asking for PENDING
            // silently reopened the conversation and moved an agent's work
            // backwards.
            ThreadStatus next = ThreadStatus.valueOf(request.status().trim().toUpperCase(Locale.ROOT));
            threads.changeStatus(id, next);
            auditService.record("COMMUNICATION_THREAD", id,
                    AuditAction.UPDATE,
                    "status", null, next.name());
        }
        return ResponseEntity.ok(toResponse(threads.get(id)));
    }

    @PostMapping("/{id}/read")
    @PreAuthorize("hasAnyRole('AGENT','MANAGER','ADMIN','CEO')")
    public ResponseEntity<ThreadResponse> markRead(@PathVariable UUID id) {
        return ResponseEntity.ok(toResponse(threads.markRead(id)));
    }

    private static ThreadResponse toResponse(CommunicationThread t) {
        return new ThreadResponse(
                t.getId(), t.getSubjectType(), t.getSubjectId(), t.getChannel(),
                t.getCustomerMobile(), t.getCustomerName(), t.getStatus(), t.getUnreadCount(),
                t.getAssignedTo(), t.getLastMessageAt(), t.getLastDirection().name(), t.getLastPreview(),
                t.getWindowExpiresAt(),
                t.getChannel() != CommunicationChannel.WHATSAPP || t.isServiceWindowOpen(Instant.now()));
    }
}
