package com.securetravels.crm.communications.consent;

/**
 * Why a message is being sent. TRANSACTIONAL sends (booking confirmations,
 * payment links, OTPs) need no consent; MARKETING sends (offers, re-engagement,
 * nurture steps) require an explicit GRANTED record per channel.
 */
public enum Purpose {
    TRANSACTIONAL, MARKETING
}