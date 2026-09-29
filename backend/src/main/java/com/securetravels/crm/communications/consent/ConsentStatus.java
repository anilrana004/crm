package com.securetravels.crm.communications.consent;

/**
 * Effective consent status for a (customer, channel, purpose).
 *
 * <p>UNKNOWN is the default until a GRANTED or REVOKED row exists — a missing
 * row and an UNKNOWN row are the same answer. The send gate only lets a
 * MARKETING send through on {@code GRANTED}.
 */
public enum ConsentStatus {
    GRANTED, REVOKED, UNKNOWN
}