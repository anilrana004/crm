package com.securetravels.crm.trip;

/**
 * The architecture discriminator. FIXED_BATCH trips run shared scheduled
 * batches with a decrementing seat pool; CUSTOM_FIT trips are private
 * bookings with no shared seats.
 */
public enum BookingType {
    FIXED_BATCH, CUSTOM_FIT
}