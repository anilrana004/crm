package com.securetravels.crm.communications;

import java.util.List;

/**
 * The port every WhatsApp provider sits behind (Module 4).
 *
 * <p>Per {@code docs/INTEGRATIONS.md} §3 rule 1, the rest of the system depends
 * on this interface only — never on Interakt's JSON. Two implementations ship:
 * {@link SandboxWhatsAppGateway} (the default, no credentials) and
 * {@link InteraktWhatsAppGateway} (live HTTP).
 *
 * <p>Note the absence of {@code throws}. A gateway converts *every* failure —
 * including transport errors — into a {@link SendResult}. That is deliberate:
 * the retry/dead-letter policy in {@link WhatsAppDispatchService} is the only
 * place that decides what is retried, and a provider that could throw past it
 * would mean two competing retry policies.
 */
public interface WhatsAppGateway {

    /** Recorded on {@code whatsapp_messages.provider} and timeline rows. */
    String provider();

    /**
     * Send one approved template.
     *
     * @throws RuntimeException only for programming errors; never for a
     *                          provider rejection or a transport failure.
     */
    SendResult send(SendCommand command);

    /**
     * @param phoneNumber 10 digits, no country code, no leading zero — Interakt
     *                    rejects both forms.
     */
    record SendCommand(String countryCode,
                       String phoneNumber,
                       String templateName,
                       String languageCode,
                       List<String> bodyValues,
                       String callbackData) {
    }

    /**
     * @param retryable whether a retry could plausibly succeed. A 429 or a 5xx
     *                  can; {@code "customer not registered"} cannot. Getting
     *                  this wrong either wastes a DLQ on a permanent rejection
     *                  or, worse, retries a genuinely dead recipient.
     */
    record SendResult(boolean success,
                      String providerMessageId,
                      String error,
                      String channelErrorCode,
                      String failureReason,
                      boolean retryable) {

        public static SendResult ok(String providerMessageId) {
            return new SendResult(true, providerMessageId, null, null, null, false);
        }

        public static SendResult failure(String error, String channelErrorCode,
                                         String failureReason, boolean retryable) {
            return new SendResult(false, null, error, channelErrorCode, failureReason, retryable);
        }
    }
}
