package com.securetravels.crm.communications;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * No-credential WhatsApp gateway (Module 4 default).
 *
 * <p>Records the send and returns a synthetic provider id so the whole
 * pipeline — templates, dispatch, DLQ, timeline, webhook correlation — is
 * buildable and testable before an Interakt account exists. It follows the
 * existing stub convention from {@code EmailNotifier}: log it, don't pretend.
 *
 * <p>Selected by {@code app.whatsapp.mode=SANDBOX} (the default). It is
 * deliberately <strong>not</strong> a silent no-op: returning a synthetic id
 * keeps the status-webhook path exercised end to end, which is what makes
 * switching to {@link InteraktWhatsAppGateway} a config change rather than a
 * rewrite.
 *
 * <p>Mutually exclusive with the live gateway via
 * {@code @ConditionalOnProperty}, so exactly one {@link WhatsAppGateway} bean
 * exists and injection is never ambiguous.
 */
@Component
@ConditionalOnProperty(prefix = "app.whatsapp", name = "mode",
        havingValue = "SANDBOX", matchIfMissing = true)
public class SandboxWhatsAppGateway implements WhatsAppGateway {

    private static final Logger log = LoggerFactory.getLogger(SandboxWhatsAppGateway.class);
    public static final String PROVIDER = "INTERAKT_SANDBOX";

    @Override
    public String provider() {
        return PROVIDER;
    }

    @Override
    public SendResult send(SendCommand command) {
        log.info("[whatsapp][sandbox] to={}{} template={} values={} callback={}",
                command.countryCode(), command.phoneNumber(), command.templateName(),
                command.bodyValues(), command.callbackData());
        return SendResult.ok("sandbox-" + UUID.randomUUID());
    }
}
