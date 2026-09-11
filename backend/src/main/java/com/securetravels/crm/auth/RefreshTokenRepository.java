package com.securetravels.crm.auth;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /** Deletes rotated/expired rows for a user (keeps the table small). */
    void deleteByUserIdAndRevokedTrue(UUID userId);
}