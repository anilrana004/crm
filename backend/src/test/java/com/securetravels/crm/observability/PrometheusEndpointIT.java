package com.securetravels.crm.observability;

import com.securetravels.crm.BaseIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Module 5: the Prometheus endpoint itself.
 *
 * <p>Two failure modes this guards against, both of which only show up once
 * Prometheus is pointed at production:
 *
 * <ul>
 *   <li>the endpoint is not exposed, so the whole module silently scrapes
 *       nothing while every dashboard reads "no data";</li>
 *   <li>{@code http.server.requests} grows a {@code uri} label per raw request
 *       path. That is a cardinality bomb: a handful of 404 probes against random
 *       URLs can take the metrics backend down, so the label must be the route
 *       pattern and not the literal path.</li>
 * </ul>
 */
class PrometheusEndpointIT extends BaseIT {

    @Autowired MockMvc mockMvc;

    private String scrape() throws Exception {
        return mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    @DisplayName("/actuator/prometheus is exposed and renders in Prometheus text format")
    void endpointIsExposed() throws Exception {
        String body = scrape();

        assertThat(body).contains("# HELP");
        assertThat(body).contains("# TYPE");
    }

    @Test
    @DisplayName("JVM, Hikari pool and HTTP server metrics are auto-instrumented")
    void standardMetricsArePresent() throws Exception {
        String body = scrape();

        assertThat(body)
                .as("JVM memory")
                .contains("jvm_memory_used_bytes");
        assertThat(body)
                .as("Hikari connection pool (the 'database connection pool utilization' the roadmap asks for)")
                .contains("hikaricp_connections");
        assertThat(body)
                .as("HTTP request latency and error rate source")
                .contains("http_server_requests_seconds_count");
    }

    @Test
    @DisplayName("HTTP timings are published as a histogram, not a summary")
    void httpTimingHistogramIsPublished() throws Exception {
        // A regression guard with teeth. Micrometer publishes a summary by
        // default, which yields only _max: histogram_quantile() in
        // ops/observability/alerts.yml would then have no data and the p95
        // latency alert could never fire, while every other test stayed green.
        assertThat(scrape())
                .as("histogram_quantile() requires _bucket series")
                .contains("http_server_requests_seconds_bucket");
    }

    @Test
    @DisplayName("our own queue-depth metrics are exported for both queues")
    void queueDepthMetricsAreExported() throws Exception {
        String body = scrape();

        assertThat(body).contains("securetravels_rabbitmq_queue_depth");
        assertThat(body)
                .as("dispatch queue labelled")
                .contains("securetravels_rabbitmq_queue_depth{application=\"securetravels\",queue=\"securetravels.whatsapp.dispatch\"");
        assertThat(body)
                .as("dead-letter queue labelled")
                .contains("queue=\"securetravels.whatsapp.dispatch.dlq\"");
        assertThat(body).contains("securetravels_rabbitmq_queue_consumers");
    }

    @Test
    @DisplayName("http.server.requests uri label is a route pattern, never a raw request path")
    void httpUriLabelIsBoundedCardinality() throws Exception {
        // Fire a request at a concrete sub-path. Whether it is served or rejected
        // is irrelevant; what matters is that its literal path must not become a
        // metric label. The status is not asserted because the security filter
        // answers 401 before routing, and a 401 is still an observed request.
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mockMvc.perform(get("/actuator/health/does-not-exist"));

        String body = scrape();

        assertThat(body)
                .as("the real route pattern is present")
                .contains("uri=\"/actuator/health\"");
        assertThat(body)
                .as("a raw, caller-supplied path must never become a label value")
                .doesNotContain("does-not-exist");
    }

    @Test
    @DisplayName("application tag is applied so multi-service scrapes stay distinguishable")
    void applicationTagIsApplied() throws Exception {
        assertThat(scrape()).contains("application=\"securetravels\"");
    }

    @Test
    @DisplayName("the scrape endpoint is reachable without a JWT, which is why the proxy must deny it")
    void endpointIsUnauthenticatedByDesign() throws Exception {
        // Asserted deliberately: this is the behaviour that makes a default
        // Prometheus config work, and it is the same reason docs/OBSERVABILITY.md
        // makes the Nginx deny rule a required production step. If this test ever
        // starts failing because someone "fixed" the 401, the scrape config in
        // ops/observability/prometheus.yml has to gain a bearer token first.
        mockMvc.perform(get("/actuator/prometheus")).andExpect(status().isOk());

        // ...while the rest of the actuator surface stays closed.
        mockMvc.perform(get("/actuator/env")).andExpect(status().is4xxClientError());
    }
}
