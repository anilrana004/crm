package com.securetravels.crm.communications.sms;

/**
 * The port every SMS provider sits behind (Phase 5 Module 2).
 *
 * <p>Same contract shape as {@code WhatsAppGateway} and {@code EmailGateway}:
 * every failure, including transport errors, becomes a {@link SendResult} so
 * the retry budget in {@link SmsDispatchService} is the only retry policy in the
 * system.
 */
public interface SmsGateway {

    /** Recorded on {@code sms_messages.provider} and timeline rows. */
    String provider();

    SendResult send(SendCommand command);

    /**
     * @param senderId the DLT-registered sender id. Indian traffic is rejected
     *                 without one on most operators, so it is a first-class
     *                 field rather than a hidden config lookup.
     */
    record SendCommand(String countryCode,
                       String phoneNumber,
                       String body,
                       String senderId,
                       String dltTemplateId) {
    }

    /**
     * @param retryable a throttle or a 5xx can be retried; a rejected DLT
     *                  template or an invalid number cannot.
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
}
