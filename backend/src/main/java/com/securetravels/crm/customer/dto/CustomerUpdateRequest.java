package com.securetravels.crm.customer.dto;

import jakarta.validation.constraints.Size;

/**
 * Marketing/remarketing flags and notes an authorised user can edit on a
 * customer. Consent/identity (name, mobile, email) are not editable here —
 * that flows through the lead/consent lifecycle.
 */
public record CustomerUpdateRequest(
        @Size(max = 200) String suggestOffer,
        String[] offerTags,
        Boolean marketingOptIn,
        @Size(max = 2000) String notes
) {
}
