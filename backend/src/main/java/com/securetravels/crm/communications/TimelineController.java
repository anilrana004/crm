package com.securetravels.crm.communications;

import com.securetravels.crm.communications.dto.TimelineResponse;
import com.securetravels.crm.common.security.CurrentUser;
import com.securetravels.crm.user.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * The shared communication timeline (Module 4).
 *
 * <p>One view over every Lead, Customer360, and Booking: what we sent, what
 * was delivered, and what the customer replied. This is the "shared team inbox"
 * — every salesperson with access to the record sees the same conversation
 * history, so a handover does not lose the thread.
 */
@RestController
@RequestMapping("/api/timeline")
@Tag(name = "Timeline")
@SecurityRequirement(name = "bearerAuth")
public class TimelineController {

    private final TimelineService timeline;

    public TimelineController(TimelineService timeline) {
        this.timeline = timeline;
    }

    @Operation(summary = "Communication timeline for a lead, customer, or booking",
            description = "Newest first. Every WhatsApp send, delivery, read, failure, and inbound "
                    + "reply is recorded. Access follows the owning record's rules: a salesperson sees "
                    + "their own, a manager/admin/CEO sees all.")
    @GetMapping("/{subjectType}/{subjectId}")
    @PreAuthorize("isAuthenticated()")
    public List<TimelineResponse> list(
            @PathVariable SubjectType subjectType,
            @PathVariable UUID subjectId,
            @CurrentUser UserPrincipal caller) {
        return timeline.list(subjectType, subjectId, caller);
    }
}
