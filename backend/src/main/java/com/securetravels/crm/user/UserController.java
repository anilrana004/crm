package com.securetravels.crm.user;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Minimal user directory used by managers to assign per-executive targets. */
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserRepository userRepository;

    public UserController(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public record SalesUser(UUID id, String fullName, String email) {}

    @GetMapping("/sales")
    @PreAuthorize("hasAnyRole('MANAGER', 'ADMIN', 'CEO')")
    public List<SalesUser> sales() {
        return userRepository.findAllByRoleOrderByCreatedAtAsc(Role.SALES).stream()
                .map(u -> new SalesUser(u.getId(), u.getFullName(), u.getEmail()))
                .toList();
    }
}