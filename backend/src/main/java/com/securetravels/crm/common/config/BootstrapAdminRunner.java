package com.securetravels.crm.common.config;

import com.securetravels.crm.user.Role;
import com.securetravels.crm.user.User;
import com.securetravels.crm.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

/**
 * First-boot escape hatch: a brand-new production database has zero users, so
 * there is no account that can log in to create the first real user — a
 * locked-out system. This runner creates exactly one CEO account from
 * {@code BOOTSTRAP_ADMIN_EMAIL} + {@code BOOTSTRAP_ADMIN_PASSWORD} when, and
 * only when, the users table is empty.
 *
 * <p><b>Deliberately NOT wired to {@code app.bootstrap-demo-data}.</b> That flag
 * is a local-dev convenience switched off in production; this is a separate
 * one-shot that is always safe to leave enabled, because the table-empty check
 * makes it naturally idempotent — the moment any user exists (including the
 * bootstrap one) it becomes a no-op forever.
 *
 * <p>Reads the environment directly rather than via {@link AppProperties} so
 * that a missing password can never fall back to a checked-in default. Both
 * variables must be present and non-blank, otherwise the runner does nothing
 * and logs at INFO.
 */
@Component
public class BootstrapAdminRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapAdminRunner.class);

    private static final String ENV_EMAIL = "BOOTSTRAP_ADMIN_EMAIL";
    private static final String ENV_PASSWORD = "BOOTSTRAP_ADMIN_PASSWORD";

    /** Role for the first account: CEO is the top of the flat hierarchy and
     *  satisfies every hasAnyRole(...) check, so it cannot be locked out of
     *  any screen. */
    private static final Role BOOTSTRAP_ROLE = Role.CEO;

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;

    public BootstrapAdminRunner(UserRepository users, PasswordEncoder passwordEncoder) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(String... args) {
        bootstrap(System.getenv(ENV_EMAIL), System.getenv(ENV_PASSWORD));
    }

    /**
     * Package-visible so tests can exercise both branches without mutating the
     * process environment (which is not reliably writable in a forked JVM).
     *
     * @return {@code true} if an account was created by this call
     */
    @Transactional
    boolean bootstrap(String rawEmail, String rawPassword) {
        String email = trimToNull(rawEmail);
        String password = rawPassword;

        if (email == null || password == null || password.isBlank()) {
            log.info("[bootstrap-admin] skipped: set {} and {} to create the first account on an empty database", ENV_EMAIL, ENV_PASSWORD);
            return false;
        }

        if (users.count() > 0) {
            log.info("[bootstrap-admin] skipped: users table already populated ({} row(s))", users.count());
            return false;
        }

        String normalisedEmail = email.toLowerCase(Locale.ROOT);
        User admin = new User(
                normalisedEmail,
                passwordEncoder.encode(password),
                "Bootstrap Admin",
                BOOTSTRAP_ROLE,
                null);
        users.save(admin);

        // Never echo the password. Email is not secret, but keep it out of the
        // log line too so this stays safe to paste into a ticket.
        log.info("[bootstrap-admin] created initial {} account on an empty database; unset {} / {} once you have signed in and changed the password",
                BOOTSTRAP_ROLE, ENV_EMAIL, ENV_PASSWORD);
        return true;
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
