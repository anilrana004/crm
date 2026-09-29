package com.securetravels.crm.communications;

import com.securetravels.crm.common.exception.BadRequestException;
import com.securetravels.crm.common.exception.ForbiddenException;
import com.securetravels.crm.common.security.CurrentUser;
import com.securetravels.crm.communications.email.EmailMessage;
import com.securetravels.crm.communications.email.EmailMessageRepository;
import com.securetravels.crm.communications.template.ChannelTemplate;
import com.securetravels.crm.communications.template.ChannelTemplateRepository;
import com.securetravels.crm.communications.thread.CommunicationChannel;
import com.securetravels.crm.user.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Email operations: the template catalogue and an operator-initiated send
 * (Phase 5 Module 2).
 *
 * <p>Goes through {@link SendGateService} like every other channel, so consent
 * and template approval are enforced in one place rather than per controller.
 */
@RestController
@RequestMapping("/api/v1/email")
@Tag(name = "Email")
@SecurityRequirement(name = "bearerAuth")
public class EmailController {

    private final SendGateService sendGate;
    private final EmailMessageRepository messages;
    private final ChannelTemplateRepository templates;
    private final TimelineService timeline;

    public EmailController(SendGateService sendGate, EmailMessageRepository messages,
                           ChannelTemplateRepository templates, TimelineService timeline) {
        this.sendGate = sendGate;
        this.messages = messages;
        this.templates = templates;
        this.timeline = timeline;
    }

    @Operation(summary = "List email templates",
            description = "Approved cross-channel templates with their expected parameter counts. "
                    + "Codes match the WhatsApp catalogue, so PAYMENT_LINK means the same message on "
                    + "every channel.")
    @GetMapping("/templates")
    @PreAuthorize("isAuthenticated()")
    public List<Map<String, Object>> listTemplates() {
        return templates.findByChannelAndEnabledTrueOrderByLabelAsc(CommunicationChannel.EMAIL).stream()
                .sorted(Comparator.comparing(ChannelTemplate::getCode))
                .map(t -> Map.<String, Object>of(
                        "code", t.getCode(),
                        "label", t.getLabel(),
                        "subjectLine", t.getSubjectLine() == null ? "" : t.getSubjectLine(),
                        "expectedParams", t.getExpectedParams(),
                        "category", t.getCategory().name(),
                        "approvalStatus", t.getApprovalStatus().name()))
                .toList();
    }

    @Operation(summary = "Send an email",
            description = "Queues the message and routes it per the active messaging mode. Returns once "
                    + "the intent is recorded, not once it is delivered — poll the timeline or "
                    + "/api/v1/threads for delivery state.")
    @PostMapping("/send")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("isAuthenticated()")
    public Map<String, Object> send(@Valid @RequestBody EmailSendRequest request,
                                    @CurrentUser UserPrincipal caller) {
        // Sending discloses the customer's data, so it carries the same
        // ownership check as reading their timeline.
        timeline.assertCanRead(request.subjectType(), request.subjectId(), caller);

        SendDecision decision = sendGate.request(request.toSendRequest(caller.id()));
        if (!decision.accepted()) {
            // Consent and service-window refusals are 403s ("we may not contact
            // this customer"); input mistakes stay 400s.
            throw decision.httpStatus() == 403
                    ? new ForbiddenException(decision.reason())
                    : new BadRequestException(decision.reason());
        }
        EmailMessage row = messages.findById(decision.messageId())
                .orElseThrow(() -> new IllegalStateException(
                        "Accepted send left no email row for " + decision.messageId()));
        return Map.of("id", row.getId(), "status", row.getStatus().name(),
                "recipient", row.getRecipientEmail(),
                "templateCode", row.getTemplateCode() == null ? "" : row.getTemplateCode());
    }

    @Operation(summary = "Email history for a lead, customer, or booking")
    @GetMapping("/messages")
    @PreAuthorize("isAuthenticated()")
    public List<Map<String, Object>> listMessages(@RequestParam SubjectType subjectType,
                                                  @RequestParam UUID subjectId,
                                                  @CurrentUser UserPrincipal caller) {
        timeline.assertCanRead(subjectType, subjectId, caller);
        return messages.findBySubjectTypeAndSubjectIdOrderByQueuedAtDesc(subjectType, subjectId).stream()
                .map(m -> Map.<String, Object>of(
                        "id", m.getId(),
                        "status", m.getStatus().name(),
                        "recipient", m.getRecipientEmail(),
                        "attempts", m.getAttempts(),
                        "queuedAt", String.valueOf(m.getQueuedAt()),
                        "lastError", m.getLastError() == null ? "" : m.getLastError()))
                .toList();
    }
}
