package com.securetravels.crm.communications;

import com.securetravels.crm.common.config.AppProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Module 4 — the Interakt wire contract.
 *
 * <p>These tests exist to pin the three things that are easy to get wrong and
 * impossible to notice locally: the trailing slash in the path, the verbatim
 * (already-base64, trailing-colon) auth header, and {@code bodyValues} being an
 * array rather than a {@code {"1": ...}} map. All three produce a baffling
 * remote failure instead of a compile error.
 *
 * <p>Fixtures are the payload shapes from Interakt's published documentation —
 * no live credentials are involved.
 */
class InteraktWhatsAppGatewayTest {

    /** As pasted from the dashboard: base64 of "accessToken:" including the colon. */
    private static final String API_KEY = "V1VmdFhrcWN4V01WTGs5b01XY3YyVEpzM0NiS3lyYURYYVBiblpnQUFmdzo=";

    private MockRestServiceServer server;
    private InteraktWhatsAppGateway gateway;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();

        AppProperties props = new AppProperties();
        props.getWhatsApp().setMode(AppProperties.WhatsApp.Mode.INTERAKT);
        props.getWhatsApp().setBaseUrl("https://api.interakt.ai/v1/public");
        props.getWhatsApp().setApiKey(API_KEY);

        gateway = new InteraktWhatsAppGateway(builder.build(), new com.fasterxml.jackson.databind.ObjectMapper(), props);
    }

    private WhatsAppGateway.SendCommand command() {
        return new WhatsAppGateway.SendCommand("+91", "9876500000", "securetravels_booking_confirmed",
                "en", List.of("Asha", "TOH-2026-0001", "Manali", "2026-11-02"), "st-callback-token");
    }

    @Test
    @DisplayName("posts to the trailing-slash route with the API key sent verbatim")
    void requestShape() {
        server.expect(requestTo("https://api.interakt.ai/v1/public/message/"))
                .andExpect(header("Authorization", "Basic " + API_KEY))
                .andExpect(header("Content-Type", MediaType.APPLICATION_JSON_VALUE))
                .andExpect(jsonPath("$.type").value("Template"))
                .andExpect(jsonPath("$.countryCode").value("+91"))
                .andExpect(jsonPath("$.phoneNumber").value("9876500000"))
                .andExpect(jsonPath("$.callbackData").value("st-callback-token"))
                .andExpect(jsonPath("$.template.name").value("securetravels_booking_confirmed"))
                .andExpect(jsonPath("$.template.languageCode").value("en"))
                // Positional array, not a "1"/"2" keyed map.
                .andExpect(jsonPath("$.template.bodyValues[0]").value("Asha"))
                .andExpect(jsonPath("$.template.bodyValues[3]").value("2026-11-02"))
                .andRespond(withSuccess(
                        "{\"result\":true,\"message\":\"Message created successfully\","
                                + "\"id\":\"6c2d7175-fddd-4fbf-b0eb-084f170dbe08\"}",
                        MediaType.APPLICATION_JSON));

        WhatsAppGateway.SendResult result = gateway.send(command());

        assertThat(result.success()).isTrue();
        assertThat(result.providerMessageId()).isEqualTo("6c2d7175-fddd-4fbf-b0eb-084f170dbe08");
        assertThat(result.retryable()).isFalse();
        server.verify();
    }

    @Test
    @DisplayName("an empty bodyValues list is sent explicitly, not omitted")
    void emptyBodyValuesSentAsEmptyArray() {
        // Interakt does not document whether the key may be omitted for a
        // template with no placeholders. An explicit empty array states the
        // intent without depending on the provider coercing null -> [].
        server.expect(requestTo("https://api.interakt.ai/v1/public/message/"))
                .andExpect(jsonPath("$.template.bodyValues").isEmpty())
                .andRespond(withSuccess("{\"result\":true,\"id\":\"abc\"}", MediaType.APPLICATION_JSON));

        WhatsAppGateway.SendResult result = gateway.send(new WhatsAppGateway.SendCommand(
                "+91", "9876500000", "securetravels_post_trip_review", "en", List.of(), "cb"));

        assertThat(result.success()).isTrue();
        server.verify();
    }

    @Test
    @DisplayName("result:false is a rejection, not a transport error")
    void rejectedMessage() {
        server.expect(requestTo("https://api.interakt.ai/v1/public/message/"))
                .andRespond(withSuccess("{\"result\":false,\"message\":\"Customer matching query does not exist.\"}",
                        MediaType.APPLICATION_JSON));

        WhatsAppGateway.SendResult result = gateway.send(command());

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Customer matching query does not exist");
        // An unregistered recipient will never register itself; retrying only
        // burns quota and DLQ slots.
        assertThat(result.retryable()).isFalse();
    }

    @Test
    @DisplayName("HTTP 429 is retryable")
    void rateLimitedIsRetryable() {
        server.expect(requestTo("https://api.interakt.ai/v1/public/message/"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"result\":false,\"message\":\"Rate limit exceeded for this resource\"}"));

        WhatsAppGateway.SendResult result = gateway.send(command());

        assertThat(result.success()).isFalse();
        assertThat(result.retryable()).isTrue();
        assertThat(result.channelErrorCode()).isEqualTo("429");
    }

    @Test
    @DisplayName("HTTP 503 is retryable, HTTP 400 is not")
    void serverErrorRetryableClientErrorNot() {
        server.reset();
        server.expect(requestTo("https://api.interakt.ai/v1/public/message/"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                        .contentType(MediaType.APPLICATION_JSON).body("{\"result\":false}"));
        assertThat(gateway.send(command()).retryable()).isTrue();

        server.reset();
        server.expect(requestTo("https://api.interakt.ai/v1/public/message/"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        // Interakt puts a JSON-serialized error array inside `message`.
                        .body("{\"result\":false,\"message\":\"[\\\"template_name is a required field\\\"]\"}"));
        WhatsAppGateway.SendResult bad = gateway.send(command());
        assertThat(bad.retryable()).isFalse();
        assertThat(bad.error()).contains("template_name");
    }

    @Test
    @DisplayName("an HTML 404 becomes a clear failure, not a JSON parse error")
    void htmlNotFoundIsHandled() {
        // Interakt 404s with Django's text/html debug page. Parsing it as JSON
        // would surface as a confusing parse failure and mask the real cause.
        server.expect(requestTo("https://api.interakt.ai/v1/public/message/"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND)
                        .contentType(MediaType.TEXT_HTML)
                        .body("<html><head><title>Not Found</title></head><body><h1>Not Found</h1></body></html>"));

        WhatsAppGateway.SendResult result = gateway.send(command());

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("404");
    }

    @Test
    @DisplayName("an unset API key fails fast and permanently instead of calling out")
    void missingApiKeyShortCircuits() {
        AppProperties props = new AppProperties();
        props.getWhatsApp().setMode(AppProperties.WhatsApp.Mode.INTERAKT);
        props.getWhatsApp().setApiKey("");
        InteraktWhatsAppGateway unconfigured = new InteraktWhatsAppGateway(
                RestClient.builder().build(), new com.fasterxml.jackson.databind.ObjectMapper(), props);

        WhatsAppGateway.SendResult result = unconfigured.send(command());

        assertThat(result.success()).isFalse();
        assertThat(result.retryable()).isFalse();
        assertThat(result.error()).contains("INTERAKT_API_KEY");
        // Nothing was published to the mock server, so no expectation is verified.
    }
}
