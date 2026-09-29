package com.securetravels.crm.communications.email;

import java.util.List;

/**
 * The port every email provider sits behind (Phase 5 Module 2).
 *
 * <p>Mirrors {@code WhatsAppGateway} deliberately, including the absence of
 * {@code throws}: a gateway converts every failure — transport or provider —
 * into a {@link SendResult}, so the retry/dead-letter policy in
 * {@link EmailDispatchService} is the single place that decides what is
 * retried. Two implementations ship: {@link SandboxEmailGateway} (default, no
 * credentials) and {@link SesEmailGateway} (live AWS SES).
 */
public interface EmailGateway {

    /** Recorded on {@code email_messages.provider} and timeline rows. */
    String provider();

    /**
     * Send one message. Templates are resolved by the dispatcher, so this
     * receives rendered content rather than a template name.
     */
    SendResult send(SendCommand command);

    record SendCommand(String toEmail,
                       String subjectLine,
                       String bodyText,
                       String bodyHtml,
                       String replyTo,
                       String idempotencyKey) {
    }

    /**
     * @param retryable whether a retry could plausibly succeed: a throttle or a
     *                  5xx can, a rejected address cannot. A non-retryable
     *                  failure must be a hard bounce so it reaches consent.
     */
    record SendResult(boolean success,
                      String providerMessageId,
                      String error,
                      String failureReason,
                      boolean retryable) {

        public static SendResult ok(String providerMessageId) {
            return new SendResult(true, providerMessageId, null, null, false);
        }

        public static SendResult failure(String error, String failureReason, boolean retryable) {
            return new SendResult(false, null, error, failureReason, retryable);
        }
    }

    /** Reserved for provider-side template management (SES templates). */
    default List<String> listTemplates() {
        return List.of();
    }
}
