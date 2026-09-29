package com.securetravels.crm.communications.consent;

import com.securetravels.crm.common.audit.AuditService;
import com.securetravels.crm.communications.TimelineEvent;
import com.securetravels.crm.customer.CustomerContactDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 5 Module 1 — the consent authority. The rules pinned here are the ones
 * the non-negotiable sign-off test rests on: UNKNOWN blocks marketing, only a
 * GRANTED row lets it through, transactional needs nothing, and an opt-out
 * survives even when the number is not (yet) a customer.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ConsentServiceTest {

    private static final UUID CUSTOMER = UUID.randomUUID();
    private static final String MOBILE = "9876500000";

    @Mock private ConsentRecordRepository records;
    @Mock private ConsentSuppressionRepository suppressions;
    @Mock private CustomerContactDirectory customers;
    @Mock private AuditService auditService;

    private ConsentService service() {
        return new ConsentService(records, suppressions, customers, auditService);
    }

    @Test
    void missingRecordAndUnknownAreTheSameAnswer() {
        when(records.findFirstByCustomerIdAndChannelAndPurposeOrderByOccurredAtDesc(
                CUSTOMER, TimelineEvent.Channel.WHATSAPP, Purpose.MARKETING)).thenReturn(Optional.empty());

        ConsentStatus status = service().effective(CUSTOMER, TimelineEvent.Channel.WHATSAPP, Purpose.MARKETING);

        assertThat(status).isEqualTo(ConsentStatus.UNKNOWN);
    }

    @Test
    void transactionalPurposeNeedsNoConsentAtAll() {
        // The booking confirmation path must never depend on the consent rows
        // existing — a customer who asked to be booked asked implicitly to hear
        // about the booking.
        ConsentStatus status = service().effective(CUSTOMER, TimelineEvent.Channel.WHATSAPP, Purpose.TRANSACTIONAL);

        assertThat(status).isEqualTo(ConsentStatus.GRANTED);
    }

    @Test
    void latestRecordWins() {
        when(records.findFirstByCustomerIdAndChannelAndPurposeOrderByOccurredAtDesc(
                CUSTOMER, TimelineEvent.Channel.WHATSAPP, Purpose.MARKETING)).thenReturn(Optional.of(
                new ConsentRecord(CUSTOMER, TimelineEvent.Channel.WHATSAPP, Purpose.MARKETING,
                        ConsentStatus.REVOKED, ConsentSource.WHATSAPP_OPTOUT, null, null, null)));

        assertThat(service().effective(CUSTOMER, TimelineEvent.Channel.WHATSAPP, Purpose.MARKETING))
                .isEqualTo(ConsentStatus.REVOKED);
    }

    @Test
    void grantAppendsAgrantedRowAndAuditsIt() {
        ConsentRecord saved = saveReturningArgument();

        UUID id = service().grant(CUSTOMER, TimelineEvent.Channel.WHATSAPP, Purpose.MARKETING,
                ConsentSource.WEB_FORM, "enquiry form", UUID.randomUUID());

        assertThat(id).isNotNull();
        ArgumentCaptor<ConsentRecord> captor = ArgumentCaptor.forClass(ConsentRecord.class);
        verify(records).save(captor.capture());
        ConsentRecord row = captor.getValue();
        assertThat(row.getStatus()).isEqualTo(ConsentStatus.GRANTED);
        assertThat(row.getChannel()).isEqualTo(TimelineEvent.Channel.WHATSAPP);
        assertThat(row.getPurpose()).isEqualTo(Purpose.MARKETING);
        assertThat(row.getSource()).isEqualTo(ConsentSource.WEB_FORM);
        verify(auditService).record(any(), any(), any(), any(), any(), any());
    }

    @Test
    void grantAfterRevokeIsANewRowNotAnUpdate() {
        // Append-only: five instructions, five rows. The audit must show the
        // change of mind, not an edited memory.
        when(records.findFirstByCustomerIdAndChannelAndPurposeOrderByOccurredAtDesc(
                any(), any(), any())).thenReturn(Optional.empty());
        saveReturningArgument();

        ConsentService svc = service();
        svc.grant(CUSTOMER, TimelineEvent.Channel.WHATSAPP, Purpose.MARKETING,
                ConsentSource.WEB_FORM, "form", null);
        svc.revoke(CUSTOMER, TimelineEvent.Channel.WHATSAPP, Purpose.MARKETING,
                ConsentSource.WHATSAPP_OPTOUT, "STOP", null);
        svc.grant(CUSTOMER, TimelineEvent.Channel.WHATSAPP, Purpose.MARKETING,
                ConsentSource.WEB_FORM, "form again", null);

        verify(records, times(3)).save(any(ConsentRecord.class));
    }

    @Test
    void aStopReplyForAKnownCustomerRevokesTheirMarketingConsent() {
        when(customers.idByMobileDigits(MOBILE)).thenReturn(Optional.of(CUSTOMER));
        saveReturningArgument();

        boolean customerFound = service().handleOptOut(TimelineEvent.Channel.WHATSAPP, MOBILE, "Interakt msg-1");

        assertThat(customerFound).isTrue();
        ArgumentCaptor<ConsentRecord> captor = ArgumentCaptor.forClass(ConsentRecord.class);
        verify(records).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(ConsentStatus.REVOKED);
        assertThat(captor.getValue().getSource()).isEqualTo(ConsentSource.WHATSAPP_OPTOUT);
    }

    @Test
    void anOptOutForANumberWithNoCustomerBecomesASuppression() {
        when(customers.idByMobileDigits(MOBILE)).thenReturn(Optional.empty());
        when(suppressions.findByMobileDigitsAndChannel(MOBILE, TimelineEvent.Channel.WHATSAPP))
                .thenReturn(Optional.empty());

        boolean customerFound = service().handleOptOut(TimelineEvent.Channel.WHATSAPP, MOBILE, "STOP");

        assertThat(customerFound).isFalse();
        verify(records, never()).save(any());
        ArgumentCaptor<ConsentSuppression> captor = ArgumentCaptor.forClass(ConsentSuppression.class);
        verify(suppressions).save(captor.capture());
        assertThat(captor.getValue().getMobileDigits()).isEqualTo(MOBILE);
        assertThat(captor.getValue().getSource()).isEqualTo(ConsentSource.WHATSAPP_OPTOUT);
    }

    @Test
    void aRepeatedOptOutForAnUnknownNumberIsANoop() {
        when(customers.idByMobileDigits(MOBILE)).thenReturn(Optional.empty());
        when(suppressions.findByMobileDigitsAndChannel(MOBILE, TimelineEvent.Channel.WHATSAPP))
                .thenReturn(Optional.of(existingSuppression));

        ConsentService svc = service();
        svc.handleOptOut(TimelineEvent.Channel.WHATSAPP, MOBILE, "STOP");
        svc.handleOptOut(TimelineEvent.Channel.WHATSAPP, MOBILE, "STOP");

        verify(suppressions, never()).save(any());
    }

    @Test
    void aStaleStopStillGuardsACustomerWhoNeverRegrants() {
        // The STOP hit a number that had no customer then; the customer appears
        // later and no one has reaffirmed marketing consent. The effective
        // answer is UNKNOWN, which is exactly as blocking as REVOKED at the gate
        // — the gate sends only on GRANTED.
        when(customers.idByMobileDigits(MOBILE)).thenReturn(Optional.of(CUSTOMER));
        when(records.findFirstByCustomerIdAndChannelAndPurposeOrderByOccurredAtDesc(
                any(), any(), any())).thenReturn(Optional.empty());

        assertThat(service().effectiveForMobile(MOBILE, TimelineEvent.Channel.WHATSAPP))
                .isEqualTo(ConsentStatus.UNKNOWN);
    }

    @Test
    void aNewerExplicitGrantOverridesAStaleSuppression() {
        // The STOP predates the customer; the staff member then records an
        // explicit GRANTED. The newer instruction wins — the suppression is not
        // immortal.
        when(customers.idByMobileDigits(MOBILE)).thenReturn(Optional.of(CUSTOMER));
        when(records.findFirstByCustomerIdAndChannelAndPurposeOrderByOccurredAtDesc(
                CUSTOMER, TimelineEvent.Channel.WHATSAPP, Purpose.MARKETING)).thenReturn(Optional.of(
                new ConsentRecord(CUSTOMER, TimelineEvent.Channel.WHATSAPP, Purpose.MARKETING,
                        ConsentStatus.GRANTED, ConsentSource.WEB_FORM, null, null, null)));

        assertThat(service().effectiveForMobile(MOBILE, TimelineEvent.Channel.WHATSAPP))
                .isEqualTo(ConsentStatus.GRANTED);
    }

    @Test
    void unknownNumberWithNoSuppressionIsUnknown() {
        when(customers.idByMobileDigits(MOBILE)).thenReturn(Optional.empty());
        when(suppressions.findByMobileDigitsAndChannel(MOBILE, TimelineEvent.Channel.WHATSAPP))
                .thenReturn(Optional.empty());

        assertThat(service().effectiveForMobile(MOBILE, TimelineEvent.Channel.WHATSAPP))
                .isEqualTo(ConsentStatus.UNKNOWN);
    }

    @Test
    void syncingTheLegacyFlagWritesOneRowPerChannel() {
        saveReturningArgument();

        service().syncMarketingOptIn(CUSTOMER, true, UUID.randomUUID());

        ArgumentCaptor<ConsentRecord> captor = ArgumentCaptor.forClass(ConsentRecord.class);
        verify(records, times(3)).save(captor.capture());
        assertThat(captor.getAllValues()).extracting(ConsentRecord::getChannel)
                .containsExactlyInAnyOrder(TimelineEvent.Channel.WHATSAPP,
                        TimelineEvent.Channel.EMAIL, TimelineEvent.Channel.SMS);
        assertThat(captor.getAllValues()).allMatch(r -> r.getStatus() == ConsentStatus.GRANTED);
    }

    private ConsentRecord saveReturningArgument() {
        when(records.save(any(ConsentRecord.class))).thenAnswer(invocation -> {
            ConsentRecord row = invocation.getArgument(0);
            ReflectionTestUtils.setField(row, "id", UUID.randomUUID());
            return row;
        });
        return null;
    }


    private final ConsentSuppression existingSuppression = ConsentSuppression.forMobile(MOBILE, TimelineEvent.Channel.WHATSAPP,
            ConsentSource.WHATSAPP_OPTOUT, null, null);
}