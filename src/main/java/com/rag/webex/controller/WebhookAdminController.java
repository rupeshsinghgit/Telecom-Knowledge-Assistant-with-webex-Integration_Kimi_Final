package com.rag.webex.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * One-time convenience endpoint to register the Webex webhook subscription,
 * so you don't have to hand-write a curl call.
 *
 * Usage (after deploying, run once):
 *   curl -X POST "https://your-domain.com/webex/admin/register-webhook?targetUrl=https://your-domain.com/webex/webhook"
 *
 * Safe to delete this controller after the webhook is registered.
 */
@RestController
public class WebhookAdminController {

    private final RestTemplate webexRestTemplate;

    @Value("${webex.api-base-url}")
    private String apiBaseUrl;

    @Value("${webex.bot-token}")
    private String botToken;

    @Value("${webex.webhook-secret}")
    private String webhookSecret;

    public WebhookAdminController(RestTemplate webexRestTemplate) {
        this.webexRestTemplate = webexRestTemplate;
    }

    @PostMapping("/webex/admin/register-webhook")
    public String registerWebhook(@RequestParam String targetUrl) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(botToken);
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);

        Map<String, String> body = Map.of(
                "name", "Telecom Knowledge Assistant message webhook",
                "targetUrl", targetUrl,
                "resource", "messages",
                "event", "created",
                "secret", webhookSecret
        );

        var response = webexRestTemplate.exchange(
                apiBaseUrl + "/webhooks", HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
        return response.getBody();
    }
}
