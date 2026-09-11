package com.securetravels.crm.user;

import java.util.UUID;

/**
 * Authenticated principal surfaced from the JWT into each request.
 */
public record UserPrincipal(UUID id, String email, String fullName, Role role, boolean active) {
}