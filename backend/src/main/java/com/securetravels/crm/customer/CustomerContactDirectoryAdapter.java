package com.securetravels.crm.customer;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository-backed implementation of {@link CustomerContactDirectory}.
 *
 * <p>Lives inside the customer package on purpose: the repository stays a
 * private detail of the feature that owns it, and every other feature resolves
 * a customer id through the interface.
 */
@Component
public class CustomerContactDirectoryAdapter implements CustomerContactDirectory {

    private final Customer360Repository customers;

    public CustomerContactDirectoryAdapter(Customer360Repository customers) {
        this.customers = customers;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> idByMobileDigits(String mobileDigits) {
        if (mobileDigits == null || mobileDigits.isBlank()) {
            return Optional.empty();
        }
        return customers.findByMobileDigits(mobileDigits).map(Customer360::getId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> idByEmail(String email) {
        if (email == null || email.isBlank()) {
            return Optional.empty();
        }
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        return customers.findByEmailIgnoreCase(normalized).map(Customer360::getId);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean exists(UUID customerId) {
        return customerId != null && customers.existsById(customerId);
    }
}
