package com.securetravels.crm.communications.consent;

import com.securetravels.crm.common.audit.AuditAction;
import com.securetravels.crm.common.audit.AuditService;
import com.securetravels.crm.common.util.PhoneUtils;
import com.securetravels.crm.communications.TimelineEvent;
import com.securetravels.crm.customer.CustomerContactDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The consent authority (Phase 5 Module 1). Every MARKETING send asks this
 * service whether the recipient has GRANTED it on the channel; nothing on an
 * outbound path is allowed to second-guess it.
 *
 * <p>Rules that are deliberately encoded here, once, so they cannot drift:
 * <ul>
 *   <li>A missing record and an UNKNOWN record are the same answer.</li>
 *   <li>TRANSACTIONAL needs no consent (a booking confirmation, a payment link,
 *       an OTP is the service the customer already asked for).</li>
 *   <li>Effective status = the latest row for (customer, channel, purpose).
 *       Rows are append-only.</li>
 *   <li>An opt-out for a number with no customer row becomes a suppression;
 *       when a customer later exists for that number the newer consent row wins
 *       over the stale STOP.</li>
 * </ul>
 */
@Service
public class ConsentService {

    private static final Logger log = LoggerFactory.getLogger(ConsentService.class);

    private final ConsentRecordRepository records;
    private final ConsentSuppressionRepository suppressions;
    private final CustomerContactDirectory customers;
    private final AuditService auditService;

    private static final TimelineEvent.Channel[] COMMS_CHANNELS = {
            TimelineEvent.Channel.WHATSAPP, TimelineEvent.Channel.EMAIL, TimelineEvent.Channel.SMS
    };

    public ConsentService(ConsentRecordRepository records, ConsentSuppressionRepository suppressions,
                          CustomerContactDirectory customers, AuditService auditService) {
        this.records = records;
        this.suppressions = suppressions;
        this.customers = customers;
        this.auditService = auditService;
    }

    /** Effective status for a known customer. TRANSACTIONAL is always allowed. */
    @Transactional(readOnly = true)
    public ConsentStatus effective(UUID customerId, TimelineEvent.Channel channel, Purpose purpose) {
        if (purpose != Purpose.MARKETING) {
            return ConsentStatus.GRANTED;
        }
        return records.findFirstByCustomerIdAndChannelAndPurposeOrderByOccurredAtDesc(
                        customerId, channel, Purpose.MARKETING)
                .map(ConsentRecord::getStatus)
                .orElse(ConsentStatus.UNKNOWN);
    }

    /**
     * Effective status for a recipient we only know by phone number (a lead, or
     * a number with no customer row at all). Used by the send gate when a send
     * is not subject-scoped to a Customer360; keeps the lookup where the rules
     * live instead of leaking mobile-number scraping into every sender.
     */
    @Transactional(readOnly = true)
    public ConsentStatus effectiveForMobile(String mobile, TimelineEvent.Channel channel) {
        String digits = PhoneUtils.normalize(mobile);
        if (digits == null) {
            return ConsentStatus.UNKNOWN;
        }
        Optional<UUID> customerId = customers.idByMobileDigits(digits);
        if (customerId.isPresent()) {
            return effective(customerId.get(), channel, Purpose.MARKETING);
        }
        return suppressions.findByMobileDigitsAndChannel(digits, channel).isPresent()
                ? ConsentStatus.REVOKED
                : ConsentStatus.UNKNOWN;
    }

    /** Per-channel MARKETING status for the Customer 360 consent panel. */
    @Transactional(readOnly = true)
    public Map<String, String> marketingStatus(UUID customerId) {
        Map<String, String> status = new LinkedHashMap<>();
        for (TimelineEvent.Channel channel : COMMS_CHANNELS) {
            status.put(channel.name(), effective(customerId, channel, Purpose.MARKETING).name());
        }
        return status;
    }

    /**
     * Reconciles the legacy single-boolean {@code marketing_opt_in} with the
     * append-only history: a staff member toggling it in the Customer 360 form
     * is an explicit, recorded instruction on every channel. Kept as a distinct
     * method so the legacy path cannot bypass the normal grant/revoke rules.
     */
    @Transactional
    public void syncMarketingOptIn(UUID customerId, boolean optedIn, UUID actor) {
        ConsentSource source = ConsentSource.STAFF_RECORDED;
        String evidence = "legacy marketing_opt_in set to " + optedIn;
        for (TimelineEvent.Channel channel : COMMS_CHANNELS) {
            if (optedIn) {
                grant(customerId, channel, Purpose.MARKETING, source, evidence, actor);
            } else {
                revoke(customerId, channel, Purpose.MARKETING, source, evidence, actor);
            }
        }
    }

    /** A new GRANTED record — the only word that lets a MARKETING send through. */
    @Transactional
    public UUID grant(UUID customerId, TimelineEvent.Channel channel, Purpose purpose,
                      ConsentSource source, String evidenceRef, UUID actor) {
        ConsentStatus previous = effective(customerId, channel, purpose);
        ConsentRecord row = new ConsentRecord(customerId, channel, purpose, ConsentStatus.GRANTED,
                source, Instant.now(), evidenceRef, actor);
        records.save(row);
        auditService.record("CUSTOMER360", customerId, AuditAction.UPDATE,
                consentField(channel, purpose), String.valueOf(previous),
                String.valueOf(ConsentStatus.GRANTED));
        log.info("[consent] GRANTED {} {} for customer {} ({})", channel, purpose, customerId, source);
        return row.getId();
    }

    @Transactional
    public UUID revoke(UUID customerId, TimelineEvent.Channel channel, Purpose purpose,
                       ConsentSource source, String evidenceRef, UUID actor) {
        ConsentStatus previous = effective(customerId, channel, purpose);
        ConsentRecord row = new ConsentRecord(customerId, channel, purpose, ConsentStatus.REVOKED,
                source, Instant.now(), evidenceRef, actor);
        records.save(row);
        auditService.record("CUSTOMER360", customerId, AuditAction.UPDATE,
                consentField(channel, purpose), String.valueOf(previous),
                String.valueOf(ConsentStatus.REVOKED));
        log.info("[consent] REVOKED {} {} for customer {} ({})", channel, purpose, customerId, source);
        return row.getId();
    }

    /**
     * An opt-out arrives on an inbound channel (a WhatsApp "STOP", an SMS STOP,
     * an unsubscribe link). Revokes MARKETING for that recipient on that
     * channel; if the number is not (yet) a customer, records a suppression so
     * the block survives them becoming one.
     *
     * @return true if the number matched a customer and their consent was
     *         revoked; false if it only produced a suppression record.
     */
    @Transactional
    public boolean handleOptOut(TimelineEvent.Channel channel, String mobile, String evidenceRef) {
        ConsentSource source = switch (channel) {
            case WHATSAPP -> ConsentSource.WHATSAPP_OPTOUT;
            case SMS -> ConsentSource.SMS_OPTOUT;
            case EMAIL -> ConsentSource.EMAIL_UNSUBSCRIBE;
            case SYSTEM -> throw new IllegalArgumentException("Cannot opt out on SYSTEM channel");
        };
        String digits = PhoneUtils.normalize(mobile);
        if (digits == null) {
            log.warn("[consent] opt-out for unparseable mobile {}", mobile);
            return false;
        }
        Optional<UUID> customerId = customers.idByMobileDigits(digits);
        if (customerId.isPresent()) {
            revoke(customerId.get(), channel, Purpose.MARKETING, source, evidenceRef, null);
            return true;
        }
        suppressions.findByMobileDigitsAndChannel(digits, channel)
                .orElseGet(() -> suppressions.save(
                        ConsentSuppression.forMobile(digits, channel, source, Instant.now(), evidenceRef)));
        log.info("[consent] number {} opted out of {} (no customer row; suppression kept)", digits, channel);
        return false;
    }

    /**
     * Effective status for a recipient we only know by email address.
     */
    @Transactional(readOnly = true)
    public ConsentStatus effectiveForEmail(String email, TimelineEvent.Channel channel) {
        if (email == null || email.isBlank()) {
            return ConsentStatus.UNKNOWN;
        }
        Optional<UUID> customerId = customers.idByEmail(email);
        if (customerId.isPresent()) {
            return effective(customerId.get(), channel, Purpose.MARKETING);
        }
        return suppressions.findByEmailAddress(email, channel).isPresent()
                ? ConsentStatus.REVOKED
                : ConsentStatus.UNKNOWN;
    }

    /**
     * An email hard bounce or spam complaint. Unlike a STOP keyword this arrives
     * with no customer action at all, but it is still a negative answer: the
     * address does not exist, or the recipient called us spam. Revoke when we
     * can identify the customer, and always keep a suppression so a later send
     * against the same address is blocked.
     */
    @Transactional
    public void suppressEmailAddress(String email, String evidenceRef) {
        if (email == null || email.isBlank()) {
            return;
        }
        String address = email.trim();
        Optional<UUID> customerId = customers.idByEmail(address);
        if (customerId.isPresent()) {
            revoke(customerId.get(), TimelineEvent.Channel.EMAIL, Purpose.MARKETING,
                    ConsentSource.EMAIL_UNSUBSCRIBE, evidenceRef, null);
        }
        suppressions.findByEmailAddress(address, TimelineEvent.Channel.EMAIL)
                .orElseGet(() -> suppressions.save(ConsentSuppression.forEmail(
                        address, ConsentSource.EMAIL_UNSUBSCRIBE, Instant.now(), evidenceRef)));
        log.info("[consent] email {} suppressed (customer row revoked: {})", address, customerId.isPresent());
    }

    private static String consentField(TimelineEvent.Channel channel, Purpose purpose) {
        return "consent." + channel.name().toLowerCase() + "." + purpose.name().toLowerCase();
    }
}