package com.securetravels.crm.webhook;

import java.util.UUID;

/**
 * Webhook response (201 on creation, 200 on duplicate match, 400/401/503/429
 * on rejection).
 */
public record WebhookLeadResponse(
        boolean ok,
        boolean duplicate,
        UUID leadId,
        UUID ownerId,
        String ownerName,
        String note
) {}