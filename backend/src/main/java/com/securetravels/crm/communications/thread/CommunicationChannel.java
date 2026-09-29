package com.securetravels.crm.communications.thread;

/**
 * Customer-facing channels. Mirrors {@code TimelineEvent.Channel} minus
 * {@code SYSTEM}, which is a timeline-only pseudo-channel and has no thread.
 */
public enum CommunicationChannel {
    WHATSAPP, EMAIL, SMS;

    public static CommunicationChannel fromTimeline(com.securetravels.crm.communications.TimelineEvent.Channel channel) {
        return switch (channel) {
            case WHATSAPP -> WHATSAPP;
            case EMAIL -> EMAIL;
            case SMS -> SMS;
            case SYSTEM -> throw new IllegalArgumentException("SYSTEM is not a communication channel");
        };
    }
}
