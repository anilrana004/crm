package com.securetravels.crm.communications;

import com.securetravels.crm.common.exception.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Module 4 — the outbound send state machine.
 *
 * <p>Two invariants carry most of the weight here:
 *
 * <ul>
 *   <li><b>One winner per message.</b> If the atomic claim reports 0 rows, the
 *       provider must not be called at all. A read-then-write would let two
 *       consumers both send, and a duplicated "you are booked" to a customer is
 *       a support incident, not a retry.</li>
 *   <li><b>Retry only what can succeed.</b> A permanent rejection is recorded
 *       once and never re-queued; a retryable one goes back to QUEUED until the
 *       budget is spent, then becomes DEAD_LETTERED.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WhatsAppSenderTest {

    private static final String CODE = "BOOKING_CONFIRMED";
    private static final String NAME = "securetravels_booking_confirmed";

    @Mock private WhatsAppMessageRepository messages;
    @Mock private WhatsAppTemplateRepository templates;
    @Mock private TimelineEventRepository timeline;
    @Mock private WhatsAppGateway gateway;

    private WhatsAppSender sender;

    @BeforeEach
    void setUp() {
        // A TransactionTemplate that runs the callback without a real
        // transaction, so the unit test exercises the sender's logic and not
        // the transaction manager.
        PlatformTransactionManager noop = new PlatformTransactionManager() {
            @Override public TransactionStatus getTransaction(TransactionDefinition d) {
                return new SimpleTransactionStatus();
            }
            @Override public void commit(TransactionStatus status) { }
            @Override public void rollback(TransactionStatus status) { }
        };
        sender = new WhatsAppSender(messages, templates, timeline, gateway, new TransactionTemplate(noop));
    }

    private WhatsAppMessage row(int alreadyAttempted) {
        WhatsAppMessage m = WhatsAppMessage.queued(SubjectType.BOOKING, UUID.randomUUID(), CODE,
                "9876500000", "+91", List.of("Asha", "TOH-2026-0001", "Manali", "2026-11-02"));
        m.attemptsForTest(alreadyAttempted);
        return m;
    }

    /** Stage a claimable row: the bulk UPDATE wins and the re-read sees N+1. */
    private WhatsAppMessage claimable(int alreadyAttempted) {
        WhatsAppMessage m = row(alreadyAttempted);
        when(messages.claimForSending(eq(m.getId()), eq(WhatsAppMessage.Status.QUEUED),
                eq(WhatsAppMessage.Status.SENDING))).thenReturn(1);
        when(messages.findById(m.getId())).thenReturn(Optional.of(m));
        when(templates.findByCodeAndEnabledTrue(CODE))
                .thenReturn(Optional.of(new WhatsAppTemplate(CODE, NAME, "Booking confirmed", "en", 4, true)));
        return m;
    }

    @Test
    @DisplayName("a successful send marks the row SENT and writes a timeline row")
    void success() {
        WhatsAppMessage m = claimable(0);
        when(gateway.send(any())).thenReturn(WhatsAppGateway.SendResult.ok("interakt-1"));

        WhatsAppSender.AttemptResult result = sender.attempt(m.getId(), 3);

        assertThat(result.sent()).isTrue();
        assertThat(m.getStatus()).isEqualTo(WhatsAppMessage.Status.SENT);
        assertThat(m.getProviderMessageId()).isEqualTo("interakt-1");
        assertThat(m.getSentAt()).isNotNull();

        ArgumentCaptor<TimelineEvent> saved = ArgumentCaptor.forClass(TimelineEvent.class);
        verify(timeline).save(saved.capture());
        assertThat(saved.getValue().getKind()).isEqualTo(TimelineEvent.Kind.TEMPLATE_SENT);
        assertThat(saved.getValue().getDirection()).isEqualTo(TimelineEvent.Direction.OUTBOUND);
    }

    @Test
    @DisplayName("a lost claim does not call the provider at all")
    void lostClaimIsSilent() {
        WhatsAppMessage m = row(0);
        // Someone else already flipped it to SENDING.
        when(messages.claimForSending(eq(m.getId()), eq(WhatsAppMessage.Status.QUEUED),
                eq(WhatsAppMessage.Status.SENDING))).thenReturn(0);

        WhatsAppSender.AttemptResult result = sender.attempt(m.getId(), 3);

        assertThat(result.sent()).isFalse();
        assertThat(result.retryable()).isFalse();
        verify(gateway, never()).send(any());
        verify(messages, never()).save(any());
    }

    @Test
    @DisplayName("a retryable failure goes back to QUEUED while budget remains")
    void retryableFailureRequeues() {
        WhatsAppMessage m = claimable(0);
        when(gateway.send(any())).thenReturn(
                WhatsAppGateway.SendResult.failure("Rate limit exceeded", "429", "Rate limit exceeded", true));

        WhatsAppSender.AttemptResult result = sender.attempt(m.getId(), 3);

        assertThat(result.sent()).isFalse();
        assertThat(result.retryable()).isTrue();
        assertThat(m.getStatus()).isEqualTo(WhatsAppMessage.Status.QUEUED);
        // A queued retry must not also look like a delivered failure.
        verify(timeline, never()).save(any());
    }

    @Test
    @DisplayName("a spent budget dead-letters the message and says so on the timeline")
    void exhaustedBudgetDeadLetters() {
        // The claim UPDATE has already advanced the counter, so a row on its
        // third and final attempt reads back attempts == 3 == budget. That is
        // the first state where the budget is genuinely spent.
        WhatsAppMessage m = claimable(3);
        when(gateway.send(any())).thenReturn(
                WhatsAppGateway.SendResult.failure("Rate limit exceeded", "429", "Rate limit exceeded", true));

        WhatsAppSender.AttemptResult result = sender.attempt(m.getId(), 3);

        assertThat(result.sent()).isFalse();
        assertThat(result.retryable()).isFalse();
        assertThat(m.getStatus()).isEqualTo(WhatsAppMessage.Status.DEAD_LETTERED);
        assertThat(m.getLastError()).contains("Rate limit exceeded");

        ArgumentCaptor<TimelineEvent> saved = ArgumentCaptor.forClass(TimelineEvent.class);
        verify(timeline).save(saved.capture());
        assertThat(saved.getValue().getKind()).isEqualTo(TimelineEvent.Kind.TEMPLATE_FAILED);
        assertThat(saved.getValue().getSummary()).contains("3 attempt(s)");
    }

    @Test
    @DisplayName("a permanent rejection fails once and is never retried")
    void permanentFailureIsNotRetried() {
        WhatsAppMessage m = claimable(0);
        when(gateway.send(any())).thenReturn(WhatsAppGateway.SendResult.failure(
                "Customer matching query does not exist.", null, "Customer not registered", false));

        WhatsAppSender.AttemptResult result = sender.attempt(m.getId(), 3);

        assertThat(result.sent()).isFalse();
        // Retrying an unregistered recipient can never succeed.
        assertThat(result.retryable()).isFalse();
        assertThat(m.getStatus()).isEqualTo(WhatsAppMessage.Status.FAILED);
        assertThat(m.getChannelErrorCode()).isNull();
    }

    @Test
    @DisplayName("a disabled template fails permanently instead of consuming retries")
    void missingTemplateIsPermanent() {
        WhatsAppMessage m = claimable(0);
        when(messages.claimForSending(eq(m.getId()), eq(WhatsAppMessage.Status.QUEUED),
                eq(WhatsAppMessage.Status.SENDING))).thenReturn(1);
        when(messages.findById(m.getId())).thenReturn(Optional.of(m));
        when(templates.findByCodeAndEnabledTrue(CODE)).thenReturn(Optional.empty());

        WhatsAppSender.AttemptResult result = sender.attempt(m.getId(), 3);

        assertThat(result.retryable()).isFalse();
        assertThat(m.getStatus()).isEqualTo(WhatsAppMessage.Status.FAILED);
        verify(gateway, never()).send(any());
    }

    @Test
    @DisplayName("a gateway that throws is treated as retryable rather than losing the message")
    void gatewayThrowIsContained() {
        WhatsAppMessage m = claimable(0);
        when(gateway.send(any())).thenThrow(new IllegalStateException("boom"));

        WhatsAppSender.AttemptResult result = sender.attempt(m.getId(), 3);

        assertThat(result.sent()).isFalse();
        assertThat(result.retryable()).isTrue();
        assertThat(m.getStatus()).isEqualTo(WhatsAppMessage.Status.QUEUED);
    }

    @Test
    @DisplayName("a row lost before the claim is a no-op, never a send")
    void vanishedBeforeClaimIsNoOp() {
        // Claimed, but the follow-up read finds nothing. The safe outcome is to
        // do nothing at all: there is no recipient data to send and no row to
        // record against, so a crash here would buy nothing.
        WhatsAppMessage m = row(0);
        when(messages.claimForSending(eq(m.getId()), eq(WhatsAppMessage.Status.QUEUED),
                eq(WhatsAppMessage.Status.SENDING))).thenReturn(1);
        when(messages.findById(m.getId())).thenReturn(Optional.empty());

        WhatsAppSender.AttemptResult result = sender.attempt(m.getId(), 3);

        assertThat(result.sent()).isFalse();
        assertThat(result.retryable()).isFalse();
        verify(gateway, never()).send(any());
        verify(messages, never()).save(any());
    }

    @Test
    @DisplayName("a row lost after a successful send fails loudly instead of lying")
    void vanishedAfterSendThrows() {
        // The dangerous variant: the provider already accepted the message, so
        // the outcome must be recorded. Losing the row means we can neither
        // confirm it nor re-queue it — that warrants a hard failure.
        WhatsAppMessage m = row(0);
        when(messages.claimForSending(eq(m.getId()), eq(WhatsAppMessage.Status.QUEUED),
                eq(WhatsAppMessage.Status.SENDING))).thenReturn(1);
        when(messages.findById(m.getId()))
                .thenReturn(Optional.of(m))   // the claim read
                .thenReturn(Optional.empty()); // the outcome read
        when(templates.findByCodeAndEnabledTrue(CODE))
                .thenReturn(Optional.of(new WhatsAppTemplate(CODE, NAME, "Booking confirmed", "en", 4, true)));
        when(gateway.send(any())).thenReturn(WhatsAppGateway.SendResult.ok("interakt-1"));

        assertThatThrownBy(() -> sender.attempt(m.getId(), 3))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("vanished");
    }
}
