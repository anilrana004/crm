package com.securetravels.crm.communications;

/**
 * What a timeline entry or outbound message is attached to (Module 4).
 *
 * <p>Deliberately a single shared enum rather than a nested one per entity:
 * {@link WhatsAppMessage} and {@link TimelineEvent} describe the same
 * conversation from two angles, and a message that is on a Lead's timeline must
 * name the same subject as the message itself. Two parallel copies of this enum
 * would be a silent data-integrity bug waiting for the first divergent value.
 */
public enum SubjectType {
    LEAD, CUSTOMER, BOOKING
}
