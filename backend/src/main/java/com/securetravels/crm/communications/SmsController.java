package com.securetravels.crm.communications;

import com.securetravels.crm.common.exception.BadRequestException;
import com.securetravels.crm.common.exception.ForbiddenException;
import com.securetravels.crm.common.security.CurrentUser;
import com.securetravels.crm.communications.sms.SmsMessage;
import com.securetravels.crm.communications.sms.SmsMessageRepository;
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
 * SMS operations: the template catalogue and an operator-initiated send
 * (Phase 5 Module 2).
 *
 * <p>The catalogue is deliberately shorter than email's: a 160-character segment
 * cannot carry an itinerary, so seeding those templates would advertise a
 * capability the medium does not have.
 */
@RestController
@RequestMapping("/api/v1/sms")
@Tag(name = "SMS")
@SecurityRequirement(name = "bearerAuth")
public class SmsController {

    private final SendGateService sendGate;
    private final SmsMessageRepository messages;
    private final ChannelTemplateRepository templates;
    private final TimelineService timeline;

    public SmsController(SendGateService sendGate, SmsMessageRepository messages,
                         ChannelTemplateRepository templates, TimelineService timeline) {
        this.sendGate = sendGate;
        this.messages = messages;
        this.templates = templates;
        this.timeline = timeline;
    }

    @Operation(summary = "List SMS templates",
            description = "Approved short-form templates. Codes match the WhatsApp catalogue.")
    @GetMapping("/templates")
    @PreAuthorize("isAuthenticated()")
    public List<Map<String, Object>> listTemplates() {
        return templates.findByChannelAndEnabledTrueOrderByLabelAsc(CommunicationChannel.SMS).stream()
                .sorted(Comparator.comparing(ChannelTemplate::getCode))
                .map(t -> Map.<String, Object>of(
                        "code", t.getCode(),
                        "label", t.getLabel(),
                        "expectedParams", t.getExpectedParams(),
                        "category", t.getCategory().name(),
                        "approvalStatus", t.getApprovalStatus().name()))
                .toList();
    }

    @Operation(summary = "Send an SMS",
            description = "Queues the message and routes it per the active messaging mode. Returns once "
                    + "the intent is recorded, not once it is delivered.")
    @PostMapping("/send")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("isAuthenticated()")
    public Map<String, Object> send(@Valid @RequestBody SmsSendRequest request,
                                    @CurrentUser UserPrincipal caller) {
        timeline.assertCanRead(request.subjectType(), request.subjectId(), caller);

        SendDecision decision = sendGate.request(request.toSendRequest(caller.id()));
        if (!decision.accepted()) {
            throw decision.httpStatus() == 403
                    ? new ForbiddenException(decision.reason())
                    : new BadRequestException(decision.reason());
        }
        SmsMessage row = messages.findById(decision.messageId())
                .orElseThrow(() -> new IllegalStateException(
                        "Accepted send left no SMS row for " + decision.messageId()));
        return Map.of("id", row.getId(), "status", row.getStatus().name(),
                "recipient", row.getRecipientMobile(),
                "templateCode", row.getTemplateCode() == null ? "" : row.getTemplateCode());
    }

    @Operation(summary = "SMS history for a lead, customer, or booking")
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
                        "recipient", m.getRecipientMobile(),
                        "attempts", m.getAttempts(),
                        "queuedAt", String.valueOf(m.getQueuedAt()),
                        "lastError", m.getLastError() == null ? "" : m.getLastError()))
                .toList();
    }
}
