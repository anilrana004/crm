package com.securetravels.crm.auth;

import com.securetravels.crm.common.exception.NotFoundException;
import com.securetravels.crm.common.security.JwtService;
import com.securetravels.crm.user.User;
import com.securetravels.crm.user.UserRepository;
import io.jsonwebtoken.Claims;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class AuthService {

    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(UserRepository users, RefreshTokenRepository refreshTokens,
                       PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @Transactional
    public AuthResponse login(String email, String rawPassword) {
        User user = users.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new BadCredentialsException("Invalid email or password"));
        if (!user.isActive() || !passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            throw new BadCredentialsException("Invalid email or password");
        }
        refreshTokens.deleteByUserIdAndRevokedTrue(user.getId());
        return issuePair(user);
    }

    @Transactional
    public AuthResponse refresh(String rawToken) {
        Claims claims = jwtService.parse(rawToken);
        if (claims == null || !JwtService.TYPE_REFRESH.equals(claims.get(JwtService.CLAIM_TYPE, String.class))) {
            throw new BadCredentialsException("Invalid refresh token");
        }
        String hash = jwtService.sha256Hex(rawToken);
        RefreshToken stored = refreshTokens.findByTokenHash(hash)
                .orElseThrow(() -> new BadCredentialsException("Invalid refresh token"));
        if (stored.isRevoked() || stored.getExpiresAt().isBefore(Instant.now())) {
            throw new BadCredentialsException("Invalid refresh token");
        }
        User user = users.findById(stored.getUserId())
                .orElseThrow(() -> new BadCredentialsException("Invalid refresh token"));
        if (!user.isActive()) {
            throw new BadCredentialsException("Account disabled");
        }

        // rotation: revoke the presented token, persist the replacement hash
        String newRefresh = jwtService.issueRefreshToken(user.getId());
        String newHash = jwtService.sha256Hex(newRefresh);
        stored.revoke(newHash);
        refreshTokens.save(stored);
        refreshTokens.save(new RefreshToken(user.getId(), newHash,
                Instant.now().plus(jwtService.refreshTtl())));

        return new AuthResponse(
                jwtService.issueAccessToken(user.getId(), user.getEmail(), user.getRole()),
                newRefresh,
                "Bearer",
                jwtService.accessTtlSeconds(),
                toUserInfo(user));
    }

    @Transactional
    public void logout(String rawToken) {
        String hash = jwtService.sha256Hex(rawToken);
        refreshTokens.findByTokenHash(hash).ifPresent(t -> {
            t.revoke(null);
            refreshTokens.save(t);
        });
    }

    private AuthResponse issuePair(User user) {
        String refresh = jwtService.issueRefreshToken(user.getId());
        refreshTokens.save(new RefreshToken(user.getId(), jwtService.sha256Hex(refresh),
                Instant.now().plus(jwtService.refreshTtl())));
        return new AuthResponse(
                jwtService.issueAccessToken(user.getId(), user.getEmail(), user.getRole()),
                refresh,
                "Bearer",
                jwtService.accessTtlSeconds(),
                toUserInfo(user));
    }

    private AuthResponse.UserInfo toUserInfo(User user) {
        return new AuthResponse.UserInfo(user.getId(), user.getEmail(), user.getFullName(), user.getRole());
    }
}