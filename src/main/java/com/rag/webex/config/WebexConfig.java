package com.rag.webex.config;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * Wiring for the Webex integration: a RestTemplate dedicated to calling the
 * Webex REST API (https://webexapis.com/v1/...), separate from any client
 * you may already use elsewhere in the app.
 *
 * @EnableAsync lets WebexWebhookController hand off processing to a background
 * thread so it can return HTTP 200 to Webex immediately (Webex expects a fast
 * response and will retry / disable the webhook if it times out).
 */
@Configuration
@EnableAsync
public class WebexConfig {

    @Bean
    public RestTemplate webexRestTemplate(RestTemplateBuilder builder) {
        return builder
                .setConnectTimeout(Duration.ofSeconds(10))
                .setReadTimeout(Duration.ofSeconds(15))
                .build();
    }
}
