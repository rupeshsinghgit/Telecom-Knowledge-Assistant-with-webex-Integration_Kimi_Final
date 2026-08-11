package com.rag.webex.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.webex.dto.WebexWebhookPayload;
import com.rag.webex.service.WebexOrchestrationService;
import com.rag.webex.service.WebexService;
import com.rag.webex.util.WebhookSignatureValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Register this URL as the Webex webhook targetUrl, e.g.
 * https://your-domain.com/webex/webhook
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class WebexWebhookController {

    private final WebexService webexService;
    private final WebexOrchestrationService orchestrationService;
    private final WebhookSignatureValidator signatureValidator;
    private final ObjectMapper objectMapper;

    @Value("${webex.webhook-secret}")
    private String webhookSecret;

    @PostMapping("/webex/webhook")
    public ResponseEntity<Void> handleWebhook(
            @RequestBody String rawBody,
            @RequestHeader(value = "X-Spark-Signature", required = false) String signature) {

        if (!signatureValidator.isValid(rawBody, signature, webhookSecret)) {
            log.warn("Rejected Webex webhook call: invalid or missing X-Spark-Signature");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        final WebexWebhookPayload payload;
        try {
            payload = objectMapper.readValue(rawBody, WebexWebhookPayload.class);
        } catch (Exception e) {
            log.error("Failed to parse Webex webhook payload: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        }

        if (payload.getData() == null
                || !"messages".equals(payload.getResource())
                || !"created".equals(payload.getEvent())) {
            return ResponseEntity.ok().build(); // not a new-message event, ignore
        }

        // Prevent the bot from replying to its own messages (infinite loop guard)
        if (webexService.getBotEmail().equalsIgnoreCase(payload.getData().getPersonEmail())) {
            return ResponseEntity.ok().build();
        }

        String messageId = payload.getData().getId();
        String roomId = payload.getData().getRoomId();

        // Fire-and-forget: RAG lookup can take seconds, Webex needs a fast 200
        orchestrationService.handleIncomingMessage(messageId, roomId);

        return ResponseEntity.ok().build();
    }
}
