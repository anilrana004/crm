package com.securetravels.crm.communications;

import com.securetravels.crm.common.config.AppProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * HTTP client for Interakt, created only in live mode.
 *
 * <p>The timeouts are not cosmetic. Without a read timeout a provider stall
 * holds a dispatch thread and, in the inline path, blocks the caller — and with
 * a default 10-minute Interakt quota window, "slow" is a realistic case, not a
 * hypothetical one.
 */
@Configuration
@ConditionalOnProperty(prefix = "app.whatsapp", name = "mode", havingValue = "INTERAKT")
public class InteraktClientConfig {

    @Bean
    public RestClient interaktRestClient(RestClient.Builder builder, AppProperties props) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(props.getWhatsApp().getConnectTimeout());
        factory.setReadTimeout(props.getWhatsApp().getReadTimeout());
        return builder.requestFactory(factory).build();
    }
}
