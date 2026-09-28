/**
 * Phase 3 Module 2: reporting and analytics.
 *
 * <p>Named {@code analytics} rather than {@code reporting} because
 * {@code com.securetravels.crm.reporting/package-info.java} explicitly reserves
 * that package for Phase 10/11 advanced analytics, BI, forecasting and ML lead
 * scoring. Putting the module reports there would have quietly taken a boundary
 * a later phase is documented to own. The earlier Module 4/5 summary, target and
 * performance endpoints stay in {@code com.securetravels.crm.dashboard} and are
 * left untouched; see {@code TeamPerformanceResponse} for how its revenue
 * attribution differs and why.
 *
 * <p>Revenue attribution is written by {@code com.securetravels.crm.commission}
 * on the booking lifecycle and only read here.
 */
package com.securetravels.crm.analytics;
