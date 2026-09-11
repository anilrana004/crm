package com.securetravels.crm.auth;

import com.securetravels.crm.user.Role;

import java.util.UUID;

public record AuthResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresInSeconds,
        UserInfo user
) {
    public record UserInfo(UUID id, String email, String fullName, Role role) {}
}