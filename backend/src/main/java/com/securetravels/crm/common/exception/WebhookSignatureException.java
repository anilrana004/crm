package com.securetravels.crm.common.exception;

/** Mapped to HTTP 401 — public webhook call failed HMAC verification. */
public class WebhookSignatureException extends RuntimeException {

    public WebhookSignatureException(String message) {
        super(message);
    }
}