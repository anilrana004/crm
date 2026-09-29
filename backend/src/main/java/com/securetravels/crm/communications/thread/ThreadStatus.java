package com.securetravels.crm.communications.thread;

/** Inbox working state of a thread. */
public enum ThreadStatus {
    /** Untouched: nobody has picked it up. */
    OPEN,
    /** Someone owns it and is waiting on the customer (or on us). */
    PENDING,
    /** Resolved. Stays in the inbox for history; templates may be restricted. */
    CLOSED
}
