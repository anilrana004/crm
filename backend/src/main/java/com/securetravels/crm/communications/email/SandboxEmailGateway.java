package com.securetravels.crm.communications.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * No-credential email gateway (Module 2 default).
 *
 * <p>Same convention as {@code SandboxWhatsAppGateway} and {@code EmailNotifier}:
 * log the send, return a synthetic provider id. It is <strong>not</strong> a
 * silent no-op — a synthetic id keeps the delivery-tracking path (SES event
 * webhook) exercised end to end, which is what makes switching to
 * {@link SesEmailGateway} a config change rather than a rewrite.
 *
 * <p>Selected by {@code app.email.mode=SANDBOX} (the default) and mutually
 * exclusive with the live gateway, so exactly one bean exists.
 */
@Component
@ConditionalOnProperty(prefix = "app.email", name = "mode",
        havingValue = "SANDBOX", matchIfMissing = true)
public class SandboxEmailGateway implements EmailGateway {

    private static final Logger log = LoggerFactory.getLogger(SandboxEmailGateway.class);
    public static final String PROVIDER = "SES_SANDBOX";

    @Override
    public String provider() {
        return PROVIDER;
    }

    @Override
    public SendResult send(SendCommand command) {
        log.info("[email][sandbox] to={} subject=\"{}\" chars={} idempotencyKey={}",
                command.toEmail(), command.subjectLine(),
                command.bodyText() == null ? 0 : command.bodyText().length(),
                command.idempotencyKey());
        return SendResult.ok("sandbox-" + UUID.randomUUID());
    }
}
