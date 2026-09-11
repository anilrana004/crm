package com.securetravels.crm.common.exception;

/** Mapped to HTTP 503 (e.g. webhook intake with no available sales exec). */
public class ServiceUnavailableException extends RuntimeException {

    public ServiceUnavailableException(String message) {
        super(message);
    }
}