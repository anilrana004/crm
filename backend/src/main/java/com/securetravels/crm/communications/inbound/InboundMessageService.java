package com.securetravels.crm.communications.inbound;

import com.securetravels.crm.common.util.PhoneUtils;
import com.securetravels.crm.communications.SubjectType;
import com.securetravels.crm.communications.thread.CommunicationChannel;
import com.securetravels.crm.communications.thread.CommunicationThread;
import com.securetravels.crm.communications.thread.CommunicationThreadService;
import com.securetravels.crm.customer.CustomerContactDirectory;
import com.securetravels.crm.lead.Lead;
import com.securetravels.crm.lead.LeadService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * The single entry point for every inbound customer message, on every channel
 * (Phase 5 Module 2).
 *
 * <p>All of WhatsApp, email and SMS funnel through {@link #record}. That is
 * deliberate: the hard requirement — <em>three webhook deliveries of one message
 * must produce at most one message and at most one lead</em> — is only
 * enforceable if there is one place that can be the one.
 *
 * <p>How the guarantee is built, in order:
 * <ol>
 *   <li>{@link InboundMessageRepository#insertIfAbsent} claims the
 *       {@code (provider, provider_message_id)} unique index atomically. A
 *       concurrent duplicate loses that race in the database rather than in a
 *       check-then-act, and losing it is a zero-row return rather than an
 *       exception, so this transaction stays usable.</li>
 *   <li>Resolution happens <em>after</em> the row exists, so a stranger's first
 *       message is never dropped just because we could not identify them.</li>
 *   <li>Lead creation is idempotent on its own terms too, via the existing
 *       active-duplicate check in {@link LeadService#findOrCreateFromInbound}.</li>
 * </ol>
 *
 * <p>One transaction covers the claim and the resolution together. Splitting
 * them would let a resolution failure leave a permanently unlinked row that a
 * webhook retry could never repair, because the retry would see the id as
 * already claimed and skip it.
 *
 * <p>Note this class holds no repository from another feature. Lead lookups go
 * through {@link LeadService} and customer lookups through
 * {@link CustomerContactDirectory}, so the inbound path cannot quietly depend
 * on another feature's schema.
 */
@Service
public class InboundMessageService {

    private static final Logger log = LoggerFactory.getLogger(InboundMessageService.class);

    /** Kept short: it is written into the lead's remarks as a breadcrumb. */
    private static final int SUMMARY_MAX = 240;

    private final InboundMessageRepository inbound;
    private final CommunicationThreadService threads;
    private final CustomerContactDirectory customers;
    private final LeadService leadService;

    public InboundMessageService(InboundMessageRepository inbound, CommunicationThreadService threads,
                                 CustomerContactDirectory customers, LeadService leadService) {
        this.inbound = inbound;
        this.threads = threads;
        this.customers = customers;
        this.leadService = leadService;
    }

    /**
     * Record one inbound message, resolving who sent it.
     *
     * @return the newly recorded message, or {@link Optional#empty()} if this
     *         provider id was already processed — the normal outcome of a
     *         gateway retry, and never an error.
     */
    @Transactional
    public Optional<InboundMessage> record(CommunicationChannel channel, String provider,
                                          String providerMessageId, String fromMobile, String fromEmail,
                                          String body, boolean isMedia, String mediaType,
                                          Instant receivedAt) {
        if (providerMessageId == null || providerMessageId.isBlank()) {
            // Without a provider id we have no way to recognise a redelivery, and
            // an unrecognisable duplicate is worse than a dropped message: it
            // could mean a second lead and a second message to a real person.
            log.warn("[inbound] {} message from {} has no provider id; not recorded",
                    channel, fromMobile == null ? fromEmail : fromMobile);
            return Optional.empty();
        }

        if (inbound.existsByProviderAndProviderMessageId(provider, providerMessageId)) {
            log.info("[inbound] duplicate {} message {} ignored", channel, providerMessageId);
            return Optional.empty();
        }

        String mobile = normalizeMobile(fromMobile);
        String email = normalizeEmail(fromEmail);
        Instant at = receivedAt == null ? Instant.now() : receivedAt;

        int inserted = inbound.insertIfAbsent(UUID.randomUUID(), channel.name(), provider,
                providerMessageId, mobile, email, body, isMedia, mediaType, at);
        if (inserted == 0) {
            // Lost the race against a concurrent delivery. The unique index did
            // its job and this transaction is still healthy, so the caller can
            // report it as an ordinary duplicate.
            log.info("[inbound] concurrent duplicate {} message {} ignored", channel, providerMessageId);
            return Optional.empty();
        }

        InboundMessage message = inbound.findByProviderAndProviderMessageId(provider, providerMessageId)
                .orElseThrow(() -> new IllegalStateException(
                        "inbound message " + providerMessageId + " vanished immediately after insert"));

        attach(message, at);
        return Optional.of(message);
    }

    /** Resolve the sender to a person and open/advance their inbox thread. */
    private void attach(InboundMessage message, Instant receivedAt) {
        String mobile = message.getFromMobile();
        String email = message.getFromEmail();
        Instant at = receivedAt == null ? Instant.now() : receivedAt;

        // 1) A known customer.
        Optional<UUID> customerId = mobile != null
                ? customers.idByMobileDigits(mobile)
                : (email != null ? customers.idByEmail(email) : Optional.empty());

        if (customerId.isPresent()) {
            CommunicationThread thread = threads.recordInbound(SubjectType.CUSTOMER, customerId.get(),
                    message.getChannel(), mobile, email, preview(message.getBody()), at);
            message.attach(thread, SubjectType.CUSTOMER, customerId.get(), null);
            inbound.save(message);
            return;
        }

        // 2) An existing lead for this number. Found by number rather than by
        //    "has an open lead" so a re-engagement after a LOST lead still lands
        //    somewhere a human can see it. The lookup is by id, so no lead state
        //    leaks across the feature boundary.
        Optional<UUID> activeLeadId = mobile == null
                ? Optional.empty()
                : leadService.findActiveLeadIdByMobile(mobile);
        if (activeLeadId.isPresent()) {
            UUID leadId = activeLeadId.get();
            CommunicationThread thread = threads.recordInbound(SubjectType.LEAD, leadId,
                    message.getChannel(), mobile, email, preview(message.getBody()), at);
            message.attach(thread, SubjectType.LEAD, leadId, leadId);
            inbound.save(message);
            return;
        }

        // 3) A stranger. The person contacted us, so a lead is warranted — and
        //    LeadService owns that decision, including the consent basis and
        //    duplicate check. This grants no marketing consent.
        if (mobile == null) {
            log.info("[inbound] {} message from {} with no resolvable identity; stored without a thread",
                    message.getChannel(), email);
            return;
        }
        Lead created = leadService.findOrCreateFromInbound(mobile, sourceFor(message.getChannel()),
                "Inbound " + message.getChannel().name().toLowerCase()
                        + " message: " + preview(message.getBody()));
        CommunicationThread thread = threads.recordInbound(SubjectType.LEAD, created.getId(),
                message.getChannel(), mobile, email, preview(message.getBody()), at);
        message.attach(thread, SubjectType.LEAD, created.getId(), created.getId());
        inbound.save(message);
    }

    /**
     * The lead's acquisition source for an inbound message.
     *
     * <p>{@code Lead.Source} records <em>where the customer was acquired</em> and
     * is a database check constraint that predates this module. A message that
     * arrived because the customer chose to write to us is not an acquisition
     * campaign, so everything except WhatsApp maps to {@code OTHER} rather than
     * inventing an enum value — a lead mislabelled "WhatsApp" for an SMS would be
     * visible, and wrong, in every report. The precise channel is not lost: it
     * is on the inbound message row and in the remark written above.
     */
    private static Lead.Source sourceFor(CommunicationChannel channel) {
        return channel == CommunicationChannel.WHATSAPP ? Lead.Source.WHATSAPP : Lead.Source.OTHER;
    }

    private static String normalizeMobile(String mobile) {
        if (mobile == null) {
            return null;
        }
        // PhoneUtils returns null for anything it cannot read as an Indian mobile
        // rather than throwing, so a number we cannot parse simply cannot be a
        // customer key and the message still gets stored.
        return PhoneUtils.normalize(mobile);
    }

    private static String normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        return email.trim().toLowerCase();
    }

    private static String preview(String body) {
        if (body == null) {
            return "(no text)";
        }
        String flat = body.replaceAll("\\s+", " ").trim();
        return flat.length() <= SUMMARY_MAX ? flat : flat.substring(0, SUMMARY_MAX - 1) + "\u2026";
    }
}
