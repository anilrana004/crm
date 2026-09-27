package com.securetravels.crm.operations.dto;

import com.securetravels.crm.operations.OperationsHandoff.HandoffStatus;

import java.util.UUID;

/**
 * Partial update of the ops arrangement fields. Only the supplied fields are
 * changed; the payment status is derived (mirror of the booking's receivables)
 * and never set here.
 */
public record OpsArrangementsRequest(
        HandoffStatus hotelStatus,
        HandoffStatus transportStatus,
        UUID guideId,
        UUID driverId,
        UUID hotelVendorId,
        UUID transportVendorId) {
}