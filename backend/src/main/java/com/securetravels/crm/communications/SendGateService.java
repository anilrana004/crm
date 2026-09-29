package com.securetravels.crm.communications;

import com.securetravels.crm.common.audit.AuditAction;
import com.securetravels.crm.common.audit.AuditService;
import com.securetravels.crm.common.exception.BadRequestException;
import com.securetravels.crm.common.exception.NotFoundException;
import com.securetravels.crm.communications.consent.ConsentService;
import com.securetravels.crm.communications.consent.ConsentStatus;
import com.securetravels.crm.communications.consent.Purpose;
import com.securetravels.crm.communications.thread.CommunicationChannel;
import com.securetravels.crm.communications.thread.CommunicationThreadService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The <strong>only</strong> outbound door (Phase 5 Module 1). Every message —
 * manual, bulk, automation, sequence — must pass through {@link #request(SendRequest)};
 * a channel adapter is never called directly.
 *
 * <p>What the gate upholds, in order:
 * <ol>
 *   <li>The channel must have an adapter. Until Email/SMS adapters land in
 *       Module 2 their requests are rejected (503), not silently dropped or
 *       routed through the WhatsApp sender.</li>
 *   <li>Purpose comes from the template, not the caller. Sends whose template
 *       is MARKETING (or free-form marketed) require {@link ConsentStatus#GRANTED}
 *       on the recipient's channel; anything else is blocked. TRANSACTIONAL
 *       sends pass unconditionally — that is the service the customer asked for.</li>
 *   <li>Input mistakes (unknown template, arity mismatch) surface as 400s, the
 *       same as an operator would have seen before the gate.</li>
 * </ol>
 *
 * <p>Rejections are recorded on the subject's timeline as a SYSTEM_NOTE and
 * audited, so "we did not reach this customer" is a queryable fact, not a gap.
 */
@Service
public class SendGateService {

    private static final Logger log = LoggerFactory.getLogger(SendGateService.class);

    private final Map<TimelineEvent.Channel, OutboundChannelDispatcher> dispatchers;
    private final ConsentService consent;
    private final TimelineEventRepository timeline;
    private final AuditService auditService;
    private final CommunicationThreadService threads;

    public SendGateService(List<OutboundChannelDispatcher> channelDispatchers,
                           ConsentService consent, TimelineEventRepository timeline,
                           AuditService auditService, CommunicationThreadService threads) {
        this.dispatchers = channelDispatchers.stream()
                .collect(Collectors.toUnmodifiableMap(OutboundChannelDispatcher::channel, Function.identity()));
        this.consent = consent;
        this.timeline = timeline;
        this.auditService = auditService;
        this.threads = threads;
    }

    public SendDecision request(SendRequest request) {
        OutboundChannelDispatcher dispatcher = dispatchers.get(request.channel());
        if (dispatcher == null) {
            return reject(request, 503,
                    "No outbound channel configured for " + request.channel());
        }

        try {
            // Inside the try, deliberately. resolvePurpose() looks the template
            // up to derive its category, so an unknown code throws here first --
            // before dispatch() is ever reached. Left outside, the
            // NotFoundException escaped as a 404 that reads as "no such
            // endpoint" and sent operators looking in the wrong place.
            Purpose purpose = dispatcher.resolvePurpose(request);
            if (purpose == Purpose.MARKETING) {
                ConsentStatus status = consentStatus(request);
                if (status != ConsentStatus.GRANTED) {
                    return reject(request, 403,
                            "Marketing " + request.channel() + " blocked: consent is " + status);
                }
            }

            // WhatsApp's 24h customer-service window. Checked after consent,
            // because both rules are about whether we may contact this person at
            // all and a caller blocked for one deserves the other reason too --
            // but only when the message would actually be a business-initiated
            // one.
            if (!withinServiceWindow(request)) {
                return reject(request, 403,
                        "WhatsApp service window has expired for this conversation; only an approved "
                                + "utility template may be sent, or wait for the customer to write in again");
            }

            UUID messageId = dispatcher.dispatch(request);
            log.info("[send-gate] {} {} {} to {} accepted",
                    request.channel(), request.templateCode() == null ? "free-form" : request.templateCode(),
                    request.subjectType(), request.subjectId());
            return SendDecision.accepted(request.channel(), messageId);
        } catch (BadRequestException e) {
            return reject(request, 400, e.getMessage());
        } catch (NotFoundException e) {
            // An unknown template code is a mistyped input, not a missing
            // resource: the request itself (the code) is what is wrong, and
            // before the gate existed an operator got a 400 for it.
            return reject(request, 400, e.getMessage());
        }
    }

    private ConsentStatus consentStatus(SendRequest request) {
        if (request.subjectType() == SubjectType.CUSTOMER && request.subjectId() != null) {
            return consent.effective(request.subjectId(), request.channel(), Purpose.MARKETING);
        }
        return consent.effectiveForMobile(request.mobile(), request.channel());
    }

    /**
     * Whether this send is allowed outside a WhatsApp 24h service window.
     *
     * <p>The rule follows the provider's own, and is deliberately narrow: it
     * restricts <em>free-form text only</em>.
     *
     * <ul>
     *   <li>Free-form text is permitted only inside the window, ever.</li>
     *   <li>An approved template is permitted outside it, whatever its purpose.
     *       That is the whole point of an approved template — a marketing push to
     *       a customer who has never written to us is the ordinary WhatsApp
     *       Business case, and a post-trip review 30 days after departure is a
     *       utility message, not an intrusion. Blocking those would break the
     *       channel's primary use.</li>
     * </ul>
     *
     * <p>Marketing is not thereby unconstrained: the consent check above already
     * requires {@code GRANTED} on that channel, which is the control that
     * actually matters for an unsolicited send.
     *
     * <p>Email and SMS have no equivalent restriction, so this short-circuits to
     * true and never touches the thread table for them.
     */
    private boolean withinServiceWindow(SendRequest request) {
        if (request.channel() != TimelineEvent.Channel.WHATSAPP) {
            return true;
        }
        if (request.templateCode() != null) {
            return true;                       // approved template: allowed outside the window
        }
        return windowOpen(request);            // free-form text: window required
    }

    /**
     * Whether the customer is currently inside their 24h service window.
     *
     * <p>A conversation we have never seen is closed, not open: a thread that
     * does not exist is not evidence that the customer wrote to us.
     */
    private boolean windowOpen(SendRequest request) {
        if (request.subjectId() == null) {
            return false;
        }
        return threads.isTemplateSendAllowed(request.subjectId(), request.subjectType(),
                CommunicationChannel.WHATSAPP, Instant.now());
    }

    private SendDecision reject(SendRequest request, int httpStatus, String reason) {
        String detail = "Send gate: " + reason;
        timeline.save(TimelineEvent.systemNote(request.subjectType(), request.subjectId(), detail, request.actorId()));
        auditService.record("COMMUNICATION_SEND", request.subjectId(), AuditAction.STATUS_CHANGE,
                "send_gate." + request.channel() + "."
                        + (request.templateCode() == null ? "freeform" : request.templateCode()),
                "", reason);
        log.warn("[send-gate] rejected {} {} to {}: {}",
                request.channel(), request.templateCode() == null ? "free-form" : request.templateCode(),
                request.subjectId(), reason);
        return SendDecision.rejected(request.channel(), httpStatus, reason);
    }
}