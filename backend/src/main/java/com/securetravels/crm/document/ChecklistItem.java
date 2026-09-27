package com.securetravels.crm.document;

/**
 * The fixed checklist items per traveller (Module 1). Required-ness is
 * derived per traveller/trip: ID proof and emergency contact always; a
 * medical certificate when the trip difficulty is at/above the configured
 * threshold (or the traveller is flagged); minor consent when age < 18.
 */
public enum ChecklistItem {
    ID_PROOF,
    MEDICAL_FITNESS,
    EMERGENCY_CONTACT,
    MINOR_CONSENT
}