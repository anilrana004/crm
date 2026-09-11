package com.securetravels.crm.common.notify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Email channel stub. SMTP delivery is not wired in Phase 1; each call logs
 * the would-be message so the automation contract (in-app + email) is real
 * and the seam is ready for Module 9 / deployment config.
 */
@Component
public class EmailNotifier {

    private static final Logger log = LoggerFactory.getLogger(EmailNotifier.class);

    public void send(String to, String subject, String body) {
        log.info("[mail][stub] to={} subject={} body={}", to, subject, body);
    }
}