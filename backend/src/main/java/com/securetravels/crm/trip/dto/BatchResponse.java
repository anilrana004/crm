package com.securetravels.crm.trip.dto;

import com.securetravels.crm.trip.Batch;
import com.securetravels.crm.trip.CapacityColor;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Departure batch view; {@code available} is the derived seat pool (I3) and
 * {@code fillPercent}/{@code capacityColor} are Module 3's derived capacity
 * signals. All three are computed on read from the row, never stored, so they
 * cannot disagree with the seats.
 */
public record BatchResponse(
        UUID id,
        UUID tripId,
        LocalDate departureDate,
        int maxCapacity,
        int seatsBooked,
        int available,
        int fillPercent,
        CapacityColor capacityColor,
        UUID guideId,
        String guideName,
        String transportPlan,
        Batch.Status status
) {

    /**
     * @param amberPercent configured scarcity threshold; also the AMBER cut-off
     */
    public static BatchResponse of(Batch batch, UUID tripId, String guideName, int amberPercent) {
        int fill = batch.fillPercent();
        return new BatchResponse(
                batch.getId(), tripId, batch.getDepartureDate(), batch.getMaxCapacity(),
                batch.getSeatsBooked(), batch.seatsAvailable(), fill,
                CapacityColor.of(fill, amberPercent),
                batch.getGuideId(), guideName, batch.getTransportPlan(), batch.getStatus());
    }
}
