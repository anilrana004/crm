package com.securetravels.crm.communications.thread;

import com.securetravels.crm.common.exception.NotFoundException;
import com.securetravels.crm.communications.SubjectType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Resolves and maintains inbox threads (Phase 5 Module 2).
 *
 * <p>One method matters more than the rest: {@link #recordInbound}. Every
 * inbound webhook on every channel funnels through it, which is what keeps the
 * 24h service window, the unread badge and the inbox preview consistent no
 * matter which provider delivered the message. A provider that reports a
 * reply twice therefore re-records the same window rather than inventing two
 * conversations.
 */
@Service
public class CommunicationThreadService {

    private static final Logger log = LoggerFactory.getLogger(CommunicationThreadService.class);

    /** Message previews are shown in a list; keep them short. */
    private static final int PREVIEW_MAX = 280;

    private final CommunicationThreadRepository threads;

    public CommunicationThreadService(CommunicationThreadRepository threads) {
        this.threads = threads;
    }

    /**
     * Find-or-create the thread for a subject on a channel.
     *
     * <p>Runs in its own transaction ({@code REQUIRES_NEW}) so a thread created
     * while handling an inbound message is visible to the rest of the handler
     * even if the surrounding webhook transaction later rolls back — the
     * conversation the customer actually started should not disappear because a
     * downstream audit insert failed.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CommunicationThread resolve(SubjectType subjectType, UUID subjectId,
                                       CommunicationChannel channel, String mobile, String name) {
        return threads.findBySubjectTypeAndSubjectIdAndChannel(subjectType, subjectId, channel)
                .orElseGet(() -> {
                    log.info("[inbox] opening {} thread for {} {}", channel, subjectType, subjectId);
                    return threads.save(CommunicationThread.open(subjectType, subjectId, channel, mobile, name));
                });
    }

    @Transactional
    public CommunicationThread recordInbound(SubjectType subjectType, UUID subjectId,
                                             CommunicationChannel channel, String mobile, String name,
                                             String preview, Instant at) {
        CommunicationThread thread = resolve(subjectType, subjectId, channel, mobile, name);
        thread.recordInbound(preview(preview), at);
        return threads.save(thread);
    }

    @Transactional
    public CommunicationThread recordOutbound(SubjectType subjectType, UUID subjectId,
                                              CommunicationChannel channel, String mobile, String name,
                                              String preview, Instant at) {
        CommunicationThread thread = resolve(subjectType, subjectId, channel, mobile, name);
        thread.recordOutbound(preview(preview), at);
        return threads.save(thread);
    }

    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<CommunicationThread> inbox(UUID assignedTo, ThreadStatus status,
                                                                          CommunicationChannel channel,
                                                                          boolean unreadOnly,
                                                                          org.springframework.data.domain.Pageable pageable) {
        return threads.inbox(assignedTo, status, channel, unreadOnly, pageable);
    }

    @Transactional(readOnly = true)
    public CommunicationThread get(UUID threadId) {
        return threads.findById(threadId)
                .orElseThrow(() -> new NotFoundException("Communication thread not found: " + threadId));
    }

    @Transactional
    public CommunicationThread markRead(UUID threadId) {
        CommunicationThread thread = get(threadId);
        thread.markRead();
        return threads.save(thread);
    }

    @Transactional
    public CommunicationThread assign(UUID threadId, UUID userId) {
        CommunicationThread thread = get(threadId);
        thread.assign(userId);
        return threads.save(thread);
    }

    @Transactional
    public CommunicationThread changeStatus(UUID threadId, ThreadStatus next) {
        CommunicationThread thread = get(threadId);
        thread.changeStatus(next);
        return threads.save(thread);
    }

    /**
     * Whether a business-initiated template is currently allowed on this thread.
     * Only WhatsApp enforces the 24h window; email and SMS have no equivalent.
     */
    @Transactional(readOnly = true)
    public boolean isTemplateSendAllowed(UUID subjectId, SubjectType subjectType,
                                         CommunicationChannel channel, Instant now) {
        if (channel != CommunicationChannel.WHATSAPP) {
            return true;
        }
        return threads.findBySubjectTypeAndSubjectIdAndChannel(subjectType, subjectId, channel)
                .map(t -> t.isServiceWindowOpen(now))
                .orElse(false);
    }

    static String preview(String text) {
        if (text == null) {
            return null;
        }
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= PREVIEW_MAX ? flat : flat.substring(0, PREVIEW_MAX - 1) + "\u2026";
    }
}
