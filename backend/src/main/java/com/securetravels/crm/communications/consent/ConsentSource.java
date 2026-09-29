package com.securetravels.crm.communications.consent;

/**
 * How a consent/suppression record came to exist. Kept as data, not prose, so
 * the audit trail can be filtered ("which of these were self-served vs typed by
 * staff?").
 */
public enum ConsentSource {
    WEB_FORM,
    WHATSAPP_OPTIN,
    SMS_OPTIN,
    EMAIL_OPTIN,
    STAFF_RECORDED,
    WHATSAPP_OPTOUT,
    SMS_OPTOUT,
    EMAIL_UNSUBSCRIBE,
    SYSTEM_DEFAULT
}