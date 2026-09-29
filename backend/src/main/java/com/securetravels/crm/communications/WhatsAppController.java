package com.securetravels.crm.communications;

import com.securetravels.crm.communications.dto.WhatsAppMessageResponse;
import com.securetravels.crm.communications.dto.WhatsAppSendRequest;
import com.securetravels.crm.communications.dto.WhatsAppTemplateResponse;
import com.securetravels.crm.common.exception.BadRequestException;
import com.securetravels.crm.common.exception.ForbiddenException;
import com.securetravels.crm.common.security.CurrentUser;
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

import java.util.List;
import java.util.UUID;

/**
 * WhatsApp operations: template catalogue, per-subject message history, and an
 * operator-initiated send (Module 4).
 *
 * <p>Message history is deliberately <strong>scoped by subject</strong> rather
 * than exposed as a global list. A global "all WhatsApp messages" endpoint would
 * hand every authenticated user every customer's phone number and message body,
 * which no other endpoint in this system does. Triage across subjects is a
 * broker/DB operator task, and belongs in the runbook.
 */
@RestController
@RequestMapping("/api/whatsapp")
@Tag(name = "WhatsApp")
@SecurityRequirement(name = "bearerAuth")
public class WhatsAppController {

    private final SendGateService sendGate;
    private final WhatsAppMessageRepository messages;
    private final WhatsAppTemplateRepository templates;
    private final TimelineService timeline;

    public WhatsAppController(SendGateService sendGate, WhatsAppMessageRepository messages,
                              WhatsAppTemplateRepository templates, TimelineService timeline) {
        this.sendGate = sendGate;
        this.messages = messages;
        this.templates = templates;
        this.timeline = timeline;
    }

    @Operation(summary = "List WhatsApp templates",
            description = "The nine Module 4 templates with their Interakt code names and expected "
                    + "parameter counts. interaktName is what must exist in the Interakt dashboard.")
    @GetMapping("/templates")
    @PreAuthorize("isAuthenticated()")
    public List<WhatsAppTemplateResponse> listTemplates() {
        return templates.findAll().stream()
                .sorted(java.util.Comparator.comparing(WhatsAppTemplate::getCode))
                .map(WhatsAppTemplateResponse::from)
                .toList();
    }

    @Operation(summary = "Message history for a lead, customer, or booking",
            description = "Delivery state per outbound message, including failures and the attempt count.")
    @GetMapping("/messages")
    @PreAuthorize("isAuthenticated()")
    public List<WhatsAppMessageResponse> listMessages(
            @RequestParam SubjectType subjectType,
            @RequestParam UUID subjectId,
            @CurrentUser UserPrincipal caller) {
        timeline.assertCanRead(subjectType, subjectId, caller);
        return messages.findBySubjectTypeAndSubjectIdOrderByQueuedAtDesc(subjectType, subjectId)
                .stream()
                .map(WhatsAppMessageResponse::from)
                .toList();
    }

    @Operation(summary = "Send a WhatsApp template to a customer",
            description = "Queues the message and routes it per the active messaging mode. Returns once "
                    + "the message is recorded, not once it is delivered — track delivery on the "
                    + "subject's timeline or via /api/whatsapp/messages.")
    @PostMapping("/send")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("isAuthenticated()")
    public WhatsAppMessageResponse send(@Valid @RequestBody WhatsAppSendRequest request,
                                         @CurrentUser UserPrincipal caller) {
        // Sending is a disclosure of the customer's data, so it is gated on the
        // same ownership check as reading their timeline.
        timeline.assertCanRead(request.subjectType(), request.subjectId(), caller);
        SendDecision decision = sendGate.request(SendRequest.whatsappTemplate(
                request.subjectType(), request.subjectId(), request.templateCode(),
                request.mobile(), request.bodyValues(), caller.id()));
        if (!decision.accepted()) {
            // Consent failures are 403s ("this is against the customer's
            // recorded preference"); input mistakes stay 400s, exactly as they
            // were before the gate existed.
            throw decision.httpStatus() == 403
                    ? new ForbiddenException(decision.reason())
                    : new BadRequestException(decision.reason());
        }
        return messages.findById(decision.messageId())
                .map(WhatsAppMessageResponse::from)
                .orElseThrow(() -> new IllegalStateException(
                        "Accepted send left no message row for " + decision.messageId()));
    }
}
