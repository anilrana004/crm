package com.securetravels.crm.document;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Object-key scheme for compliance uploads:
 * {@code compliance/<travellerId>/<yyyyMM>/<uuid>-<DOC_TYPE>}. The key is
 * the only thing a client ever knows about the file until the upload is
 * confirmed; file bytes never enter the application.
 */
public final class StorageObjectKey {

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyyMM").withZone(ZoneOffset.UTC);

    private static final Pattern KEY = Pattern.compile(
            "^compliance/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})/"
                    + "\\d{6}/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})-(ID_PROOF|MEDICAL_CERT|CONSENT_FORM|TRIP_PHOTO)$");

    private StorageObjectKey() {}

    public static String generate(UUID travellerId, Document.DocType docType, Instant now) {
        return "compliance/" + travellerId + "/" + MONTH.format(now) + "/"
                + UUID.randomUUID() + "-" + docType.name();
    }

    /** True when the key is a well-formed compliance key for this traveller. */
    public static boolean matchesTraveller(String key, UUID travellerId) {
        Matcher m = KEY.matcher(key);
        return m.matches() && m.group(1).equals(travellerId.toString());
    }

    public static Optional<Document.DocType> docTypeOf(String key) {
        Matcher m = KEY.matcher(key);
        return m.matches() ? Optional.ofNullable(Document.DocType.valueOf(m.group(3))) : Optional.empty();
    }
}