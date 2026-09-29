package com.securetravels.crm.communications.sms;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * No-credential SMS gateway (Module 2 default).
 *
 * <p>Logs the send and returns a synthetic id, matching
 * {@code SandboxWhatsAppGateway} and {@code SandboxEmailGateway}. Selected by
 * {@code app.sms.mode=SANDBOX} (the default) and mutually exclusive with
 * {@link Msg91SmsGateway}.
 */
@Component
@ConditionalOnProperty(prefix = "app.sms", name = "mode",
        havingValue = "SANDBOX", matchIfMissing = true)
public class SandboxSmsGateway implements SmsGateway {

    private static final Logger log = LoggerFactory.getLogger(SandboxSmsGateway.class);
    public static final String PROVIDER = "MSG91_SANDBOX";

    @Override
    public String provider() {
        return PROVIDER;
    }

    @Override
    public SendResult send(SendCommand command) {
        log.info("[sms][sandbox] to={}{} body=\"{}\" senderId={} dltTemplate={}",
                command.countryCode(), command.phoneNumber(), command.body(),
                command.senderId(), command.dltTemplateId());
        return SendResult.ok("sandbox-" + UUID.randomUUID());
    }
}
