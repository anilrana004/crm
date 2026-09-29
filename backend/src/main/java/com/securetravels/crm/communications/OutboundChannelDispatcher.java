package com.securetravels.crm.communications;

import com.securetravels.crm.communications.consent.Purpose;

import java.util.UUID;

/**
 * A channel's transport adapter. Registered per {@link #channel()} and picked by
 * the {@link SendGateService}; a channel that is not registered yet rejects its
 * sends with a 503 rather than bypassing the gate.
 *
 * <p>Implementations are the only code allowed to touch a provider. They
 * validate the request shape (template existence, arity), record their own
 * outbound row and timeline event, then hand off to the provider or queue.
 */
public interface OutboundChannelDispatcher {

    TimelineEvent.Channel channel();

    /**
     * The purpose a request actually carries. Template sends return the
     * template's category (MARKETING stays MARKETING — callers cannot relabel
     * a promotion as transactional); free-form requests fall back to the
     * explicit {@link SendRequest#purpose()}.
     */
    Purpose resolvePurpose(SendRequest request);

    /**
     * Send. Only called after the gate has approved consent; a rejected request
     * never reaches here. Returns the channel's message id for the receipt.
     */
    UUID dispatch(SendRequest request);
}