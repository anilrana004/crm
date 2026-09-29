package com.securetravels.crm.customer;

import java.util.Optional;
import java.util.UUID;

/**
 * The only view of customer identity other features are allowed to have.
 *
 * <p>Exists because consent management (communications) and the send gate have
 * to answer "is there a customer for this number / address?" constantly, and
 * the obvious implementation — injecting {@link Customer360Repository} from
 * another package — breaks the feature boundary this codebase is built on. A
 * repository is not an API; it is the customer's storage detail.
 *
 * <p>Deliberately id-only. A caller that needs more than identity should call
 * {@link Customer360Service} instead of widening this into a general-purpose
 * read model that slowly becomes a second, less-guarded door into customer360.
 */
public interface CustomerContactDirectory {

    /** @param mobileDigits 10 digits, normalized by {@code PhoneUtils}. */
    Optional<UUID> idByMobileDigits(String mobileDigits);

    /** @param email case-insensitive. */
    Optional<UUID> idByEmail(String email);

    boolean exists(UUID customerId);
}
