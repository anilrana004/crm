package com.securetravels.crm.communications;

import com.securetravels.crm.common.audit.AuditService;
import com.securetravels.crm.common.exception.BadRequestException;
import com.securetravels.crm.communications.consent.ConsentService;
import com.securetravels.crm.communications.consent.ConsentStatus;
import com.securetravels.crm.communications.consent.Purpose;
import com.securetravels.crm.communications.thread.CommunicationChannel;
import com.securetravels.crm.communications.thread.CommunicationThreadService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Phase 5 Module 1 — the single outbound door. These pin the sign-off
 * guarantee: a recipient without GRANTED marketing consent receives zero
 * marketing messages, on every path that reaches this gate, and a transactional
 * send is never blocked by a consent lookup.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SendGateServiceTest {

    private static final UUID SUBJECT = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();
    private static final String MOBILE = "9876500000";
    private static final UUID MESSAGE_ID = UUID.randomUUID();

    @Mock private OutboundChannelDispatcher whatsapp;
    @Mock private ConsentService consent;
    @Mock private TimelineEventRepository timeline;
    @Mock private AuditService auditService;
    @Mock private CommunicationThreadService threads;

    private SendGateService gate(OutboundChannelDispatcher dispatcher) {
        return new SendGateService(dispatcher == null ? List.of() : List.of(dispatcher),
                consent, timeline, auditService, threads);
    }

    private SendRequest marketingSend() {
        // The caller claims TRANSACTIONAL; an honest resolver (WhatsApp reads
        // the template category) reports MARKETING and the gate must trust the
        // resolver, not the label.
        return SendRequest.whatsappTemplate(SubjectType.CUSTOMER, SUBJECT, "POST_TRIP_REVIEW",
                MOBILE, List.of("Asha", "Ravi", "2026-11-02"), ACTOR);
    }

    @Test
    void marketingSendWithoutConsentIsRejectedBeforeTheDispatcherIsTouched() {
        when(whatsapp.channel()).thenReturn(TimelineEvent.Channel.WHATSAPP);
        when(whatsapp.resolvePurpose(any())).thenReturn(Purpose.MARKETING);
        when(consent.effective(SUBJECT, TimelineEvent.Channel.WHATSAPP, Purpose.MARKETING))
                .thenReturn(ConsentStatus.UNKNOWN);

        SendDecision decision = gate(whatsapp).request(marketingSend());

        assertThat(decision.accepted()).isFalse();
        assertThat(decision.httpStatus()).isEqualTo(403);
        assertThat(decision.reason()).contains("consent is UNKNOWN");
        verify(whatsapp, never()).dispatch(any());
        // The blocked attempt is a queryable fact, on the timeline and the audit log.
        verify(timeline).save(any(TimelineEvent.class));
        verify(auditService).record(any(), any(), any(), any(), any(), any());
    }

    @Test
    void revokedConsentAlsoBlocks() {
        when(whatsapp.channel()).thenReturn(TimelineEvent.Channel.WHATSAPP);
        when(whatsapp.resolvePurpose(any())).thenReturn(Purpose.MARKETING);
        when(consent.effective(SUBJECT, TimelineEvent.Channel.WHATSAPP, Purpose.MARKETING))
                .thenReturn(ConsentStatus.REVOKED);

        SendDecision decision = gate(whatsapp).request(marketingSend());

        assertThat(decision.accepted()).isFalse();
        assertThat(decision.httpStatus()).isEqualTo(403);
    }

    @Test
    void grantedConsentLetsTheMarketingSendThrough() {
        when(whatsapp.channel()).thenReturn(TimelineEvent.Channel.WHATSAPP);
        when(whatsapp.resolvePurpose(any())).thenReturn(Purpose.MARKETING);
        when(consent.effective(SUBJECT, TimelineEvent.Channel.WHATSAPP, Purpose.MARKETING))
                .thenReturn(ConsentStatus.GRANTED);
        when(whatsapp.dispatch(any())).thenReturn(MESSAGE_ID);

        SendDecision decision = gate(whatsapp).request(marketingSend());

        assertThat(decision.accepted()).isTrue();
        assertThat(decision.messageId()).isEqualTo(MESSAGE_ID);
        verify(whatsapp).dispatch(any());
        verifyNoInteractions(timeline);   // no rejection note for an accepted send
    }

    @Test
    void transactionalSendPassesWithNoConsentLookupAtAll() {
        when(whatsapp.channel()).thenReturn(TimelineEvent.Channel.WHATSAPP);
        when(whatsapp.resolvePurpose(any())).thenReturn(Purpose.TRANSACTIONAL);
        when(whatsapp.dispatch(any())).thenReturn(MESSAGE_ID);

        SendDecision decision = gate(whatsapp).request(SendRequest.whatsappTemplate(
                SubjectType.BOOKING, SUBJECT, "BOOKING_CONFIRMED", MOBILE,
                List.of("Asha", "TOH-1", "Manali", "2026-11-02"), null));

        assertThat(decision.accepted()).isTrue();
        verifyNoInteractions(consent);
    }

    @Test
    void aLeadSubjectResolvesConsentByMobileNumber() {
        when(whatsapp.channel()).thenReturn(TimelineEvent.Channel.WHATSAPP);
        when(whatsapp.resolvePurpose(any())).thenReturn(Purpose.MARKETING);
        when(consent.effectiveForMobile(MOBILE, TimelineEvent.Channel.WHATSAPP))
                .thenReturn(ConsentStatus.GRANTED);
        when(whatsapp.dispatch(any())).thenReturn(MESSAGE_ID);

        SendDecision decision = gate(whatsapp).request(SendRequest.whatsappTemplate(
                SubjectType.LEAD, SUBJECT, "POST_TRIP_REVIEW", MOBILE,
                List.of("Asha", "Ravi", "2026-11-02"), ACTOR));

        assertThat(decision.accepted()).isTrue();
        verify(consent).effectiveForMobile(MOBILE, TimelineEvent.Channel.WHATSAPP);
    }

    @Test
    void anUnregisteredChannelRejectsWith503RatherThanBypassing() {
        SendDecision decision = gate(null).request(SendRequest.whatsappTemplate(
                SubjectType.CUSTOMER, SUBJECT, "BOOKING_CONFIRMED", MOBILE, List.of(), ACTOR));

        assertThat(decision.accepted()).isFalse();
        assertThat(decision.httpStatus()).isEqualTo(503);
        assertThat(decision.reason()).contains("No outbound channel configured");
        verifyNoInteractions(consent);
    }

    @Test
    void anInvalidInputSurfacesAs400TheSameAsBeforeTheGate() {
        when(whatsapp.channel()).thenReturn(TimelineEvent.Channel.WHATSAPP);
        when(whatsapp.resolvePurpose(any())).thenReturn(Purpose.TRANSACTIONAL);
        when(whatsapp.dispatch(any())).thenThrow(
                new BadRequestException("Template BOOKING_CONFIRMED expects 4 body value(s) but 1 were supplied"));

        SendDecision decision = gate(whatsapp).request(SendRequest.whatsappTemplate(
                SubjectType.CUSTOMER, SUBJECT, "BOOKING_CONFIRMED", MOBILE, List.of("one"), ACTOR));

        assertThat(decision.accepted()).isFalse();
        assertThat(decision.httpStatus()).isEqualTo(400);
        assertThat(decision.reason()).contains("expects 4 body value");
    }

    @Test
    void aWhatsAppRequestNeverFallsThroughToAnotherChannel() {
        OutboundChannelDispatcher email = new OutboundChannelDispatcher() {
            @Override public TimelineEvent.Channel channel() { return TimelineEvent.Channel.EMAIL; }
            @Override public Purpose resolvePurpose(SendRequest request) { return Purpose.TRANSACTIONAL; }
            @Override public UUID dispatch(SendRequest request) { return MESSAGE_ID; }
        };
        when(whatsapp.channel()).thenReturn(TimelineEvent.Channel.WHATSAPP);
        when(whatsapp.resolvePurpose(any())).thenReturn(Purpose.TRANSACTIONAL);
        when(whatsapp.dispatch(any())).thenReturn(MESSAGE_ID);

        // A WHATSAPP request on a gate that only has EMAIL registered is
        // rejected, not silently sent as email.
        SendDecision ws = gate(email).request(marketingSend());
        assertThat(ws.accepted()).isFalse();
        assertThat(ws.httpStatus()).isEqualTo(503);

        // And the channel that does match is the only one that is ever touched.
        SendDecision em = gate(whatsapp).request(SendRequest.whatsappTemplate(
                SubjectType.CUSTOMER, SUBJECT, "BOOKING_CONFIRMED", MOBILE,
                List.of("Asha", "TOH-1", "Manali", "2026-11-02"), ACTOR));
        assertThat(em.accepted()).isTrue();
        verify(whatsapp).dispatch(any());
    }

    @Test
    void anApprovedMarketingTemplateAfterConsentIsQueued() {
        when(whatsapp.channel()).thenReturn(TimelineEvent.Channel.WHATSAPP);
        when(whatsapp.resolvePurpose(any())).thenReturn(Purpose.MARKETING);
        when(consent.effective(SUBJECT, TimelineEvent.Channel.WHATSAPP, Purpose.MARKETING))
                .thenReturn(ConsentStatus.GRANTED);
        when(whatsapp.dispatch(any())).thenReturn(MESSAGE_ID);

        SendDecision decision = gate(whatsapp).request(marketingSend());

        assertThat(decision.accepted()).isTrue();
        assertThat(decision.messageId()).isEqualTo(MESSAGE_ID);
    }

    // ---------------------------------------------------------------- service window

    @Test
    @DisplayName("free-form WhatsApp text is refused once the 24h window has closed")
    void freeformOutsideTheWindowIsRejected() {
        when(whatsapp.channel()).thenReturn(TimelineEvent.Channel.WHATSAPP);
        when(whatsapp.resolvePurpose(any())).thenReturn(Purpose.TRANSACTIONAL);
        when(threads.isTemplateSendAllowed(eq(SUBJECT), eq(SubjectType.CUSTOMER),
                eq(CommunicationChannel.WHATSAPP), any())).thenReturn(false);

        SendDecision decision = gate(whatsapp).request(SendRequest.whatsappFreeform(
                SubjectType.CUSTOMER, SUBJECT, MOBILE, "Just checking in", ACTOR));

        assertThat(decision.accepted()).isFalse();
        assertThat(decision.httpStatus()).isEqualTo(403);
        assertThat(decision.reason()).contains("service window");
        // The dispatcher must not be reached: queuing the row first and
        // rejecting afterwards would leave an unsendable row behind.
        verify(whatsapp, never()).dispatch(any());
    }

    @Test
    @DisplayName("free-form WhatsApp text inside the window goes through")
    void freeformInsideTheWindowIsAccepted() {
        when(whatsapp.channel()).thenReturn(TimelineEvent.Channel.WHATSAPP);
        when(whatsapp.resolvePurpose(any())).thenReturn(Purpose.TRANSACTIONAL);
        when(threads.isTemplateSendAllowed(eq(SUBJECT), eq(SubjectType.CUSTOMER),
                eq(CommunicationChannel.WHATSAPP), any())).thenReturn(true);
        when(whatsapp.dispatch(any())).thenReturn(MESSAGE_ID);

        SendDecision decision = gate(whatsapp).request(SendRequest.whatsappFreeform(
                SubjectType.CUSTOMER, SUBJECT, MOBILE, "Just checking in", ACTOR));

        assertThat(decision.accepted()).isTrue();
    }

    @Test
    @DisplayName("an approved template is allowed outside the window, even with no prior thread")
    void approvedTemplateDoesNotNeedTheWindow() {
        // This is the ordinary WhatsApp Business case: a marketing push to a
        // customer who has never written to us. Blocking it would break the
        // channel's primary use, so the window must not gate templates. Consent
        // is the control that applies instead, and it passed above.
        when(whatsapp.channel()).thenReturn(TimelineEvent.Channel.WHATSAPP);
        when(whatsapp.resolvePurpose(any())).thenReturn(Purpose.MARKETING);
        when(consent.effective(SUBJECT, TimelineEvent.Channel.WHATSAPP, Purpose.MARKETING))
                .thenReturn(ConsentStatus.GRANTED);
        when(whatsapp.dispatch(any())).thenReturn(MESSAGE_ID);

        SendDecision decision = gate(whatsapp).request(marketingSend());

        assertThat(decision.accepted()).isTrue();
        // A template send must not even consult the window, so a missing thread
        // cannot make an approved template unsendable.
        verify(threads, never()).isTemplateSendAllowed(any(), any(), any(), any());
    }
}