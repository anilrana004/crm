package com.securetravels.crm.communications.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Interakt {@code POST /v1/public/message/} request body.
 *
 * <p>Field names are taken from Interakt's published examples. Two shapes are
 * load-bearing and are easy to get wrong:
 *
 * <ul>
 *   <li>{@code bodyValues} is a JSON <strong>array</strong>, positional
 *       ({@code {{1}}, {{2}}}). Some third-party guides show a
 *       {@code {"1": ...}} map; that is not the documented contract and would
 *       be dropped on the floor.</li>
 *   <li>{@code type} is the exact string {@code "Template"} — capital T.
 *       Free-text sending is not part of the public API, so it is not modelled
 *       here at all.</li>
 * </ul>
 *
 * <p>{@code phoneNumber} must be 10 digits with no country code and no leading
 * zero; {@code countryCode} carries the code ({@code "+91"}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record InteraktSendRequest(
        @JsonProperty("countryCode") String countryCode,
        @JsonProperty("phoneNumber") String phoneNumber,
        @JsonProperty("callbackData") String callbackData,
        @JsonProperty("type") String type,
        @JsonProperty("template") TemplateBody template) {

    public static final String TYPE_TEMPLATE = "Template";

    public static InteraktSendRequest template(String countryCode, String phoneNumber, String templateName,
                                               String languageCode, java.util.List<String> bodyValues,
                                               String callbackData) {
        return new InteraktSendRequest(countryCode, phoneNumber, callbackData, TYPE_TEMPLATE,
                new TemplateBody(templateName, languageCode, bodyValues));
    }

    public record TemplateBody(
            @JsonProperty("name") String name,
            @JsonProperty("languageCode") String languageCode,
            @JsonProperty("bodyValues") java.util.List<String> bodyValues) {
    }
}
