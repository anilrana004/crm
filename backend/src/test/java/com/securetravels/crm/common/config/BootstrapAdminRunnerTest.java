package com.securetravels.crm.common.config;

import com.securetravels.crm.user.Role;
import com.securetravels.crm.user.User;
import com.securetravels.crm.user.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A fresh production database has no users, so no account can log in to create
 * the first real user. {@link BootstrapAdminRunner} must create exactly one CEO
 * account from the env vars when the table is empty, and must never create a
 * duplicate once any user exists.
 *
 * <p>Runs against the real test database (no MockMvc needed — this is a
 * CommandLineRunner, so it is invoked directly rather than over HTTP).
 */
@SpringBootTest
@ActiveProfiles("test")
class BootstrapAdminRunnerTest {

    @Autowired BootstrapAdminRunner runner;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JdbcTemplate jdbcTemplate;

    private void truncateUsers() {
        jdbcTemplate.execute("TRUNCATE refresh_tokens, users RESTART IDENTITY CASCADE");
    }

    @Test
    @DisplayName("empty DB + both env vars set -> creates one CEO admin")
    void createsAdminOnEmptyDatabase() {
        truncateUsers();
        assertThat(users.count()).isZero();

        boolean created = runner.bootstrap("First.Admin@SecureTravels.in", "S3cret-pass!");

        assertThat(created).isTrue();
        assertThat(users.count()).isEqualTo(1);

        User admin = users.findAll().get(0);
        assertThat(admin.getEmail()).isEqualTo("first.admin@securetravels.in");
        assertThat(admin.getRole()).isEqualTo(Role.CEO);
        assertThat(admin.isActive()).isTrue();
        assertThat(admin.getFullName()).isNotBlank();
    }

    @Test
    @DisplayName("bootstrap password is stored BCrypt-hashed, never in plaintext")
    void storesHashedPassword() {
        truncateUsers();
        runner.bootstrap("hash.check@securetravels.in", "PlainTextLeaked?");

        String stored = users.findAll().get(0).getPasswordHash();
        assertThat(stored).isNotEqualTo("PlainTextLeaked?");
        assertThat(stored).startsWith("$2");
        assertThat(passwordEncoder.matches("PlainTextLeaked?", stored)).isTrue();
    }

    @Test
    @DisplayName("non-empty DB + env vars set -> no duplicate created")
    void doesNotDuplicateWhenUsersExist() {
        truncateUsers();
        users.save(new User("existing.owner@securetravels.in",
                passwordEncoder.encode("already-here"), "Existing", Role.MANAGER, null));
        assertThat(users.count()).isEqualTo(1);

        boolean created = runner.bootstrap("second.admin@securetravels.in", "AnotherPass1!");

        assertThat(created).isFalse();
        assertThat(users.count()).isEqualTo(1);
        assertThat(users.findAll().get(0).getEmail()).isEqualTo("existing.owner@securetravels.in");
    }

    @Test
    @DisplayName("running twice on an empty-then-populated DB stays idempotent")
    void secondInvocationIsNoOp() {
        truncateUsers();

        assertThat(runner.bootstrap("once.only@securetravels.in", "FirstPass1!")).isTrue();
        assertThat(runner.bootstrap("twice.only@securetravels.in", "SecondPass1!")).isFalse();

        assertThat(users.count()).isEqualTo(1);
        assertThat(users.findAll().get(0).getEmail()).isEqualTo("once.only@securetravels.in");
    }

    @Test
    @DisplayName("missing email or password -> no user created, no crash")
    void missingEnvVarsAreSafeNoOps() {
        truncateUsers();

        assertThat(runner.bootstrap(null, "SomePass1!")).isFalse();
        assertThat(runner.bootstrap("someone@securetravels.in", null)).isFalse();
        assertThat(runner.bootstrap("someone@securetravels.in", "   ")).isFalse();
        assertThat(runner.bootstrap("  ", "SomePass1!")).isFalse();
        assertThat(runner.bootstrap("", "")).isFalse();

        assertThat(users.count()).isZero();
    }
}
