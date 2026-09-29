package com.securetravels.crm.communications.thread.dto;

import java.util.UUID;

/** Assign / status / read actions on a thread. All fields optional. */
public record ThreadUpdateRequest(
        UUID assignedTo,
        String status
) {
}
