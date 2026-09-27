package com.securetravels.crm.document;

/**
 * Per-item compliance tri-state mapped to the Red/Yellow/Green board:
 * MISSING (red), IN_PROGRESS (yellow — provided but awaiting review),
 * VERIFIED (green). Only VERIFIED counts toward the gate.
 */
public enum ComplianceStatus {

    MISSING, IN_PROGRESS, VERIFIED;

    public boolean verified() {
        return this == VERIFIED;
    }

    public String color() {
        return switch (this) {
            case MISSING -> "RED";
            case IN_PROGRESS -> "YELLOW";
            case VERIFIED -> "GREEN";
        };
    }
}