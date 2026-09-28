/**
 * Commission-grade revenue attribution (Phase 3 Module 2, feeding Phase 7).
 *
 * <p>Separate from {@code com.securetravels.crm.reporting}, which
 * {@code reporting/package-info.java} reserves for Phase 10/11 advanced
 * analytics, and from {@code com.securetravels.crm.dashboard}, which holds the
 * earlier Module 4/5 summary and target endpoints.
 *
 * <p>The package exists because revenue attribution is a write-side concern
 * owned by the booking lifecycle, while analytics only reads it. Folding the
 * write into the analytics package would have made the report service a
 * dependency of every booking confirmation.
 */
package com.securetravels.crm.commission;
