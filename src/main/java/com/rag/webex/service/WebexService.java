package com.rag.webex.service;

import com.rag.webex.dto.WebexMessage;
import com.rag.webex.dto.WebexOutboundMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Slf4j
@Service
public class WebexService {

    private final RestTemplate webexRestTemplate;

    @Value("${webex.api-base-url}")
    private String apiBaseUrl;

    @Value("${webex.bot-token}")
    private String botToken;

    @Value("${webex.bot-email}")
    private String botEmail;

    public WebexService(RestTemplate webexRestTemplate) {
        this.webexRestTemplate = webexRestTemplate;
    }

    private HttpHeaders authHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(botToken);
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        return headers;
    }

    /**
     * Webex webhooks only send the message ID, never the text.
     * Fetch the full message (GET /v1/messages/{id}) using the bot token.
     */
    public WebexMessage fetchMessage(String messageId) {
        String url = apiBaseUrl + "/messages/" + messageId;
        var response = webexRestTemplate.exchange(
                url, HttpMethod.GET, new HttpEntity<>(authHeaders()), WebexMessage.class);
        return response.getBody();
    }

    /**
     * Post a message into the given room. Returns the created message's id
     * (needed if the caller wants to delete it later, e.g. a progress
     * placeholder that gets replaced once the real answer is ready).
     * Returns null if the send failed — caller should treat that as
     * "no progress message to clean up" rather than throwing.
     */
    public String sendReply(String roomId, String answerMarkdown) {
        WebexOutboundMessage outbound = WebexOutboundMessage.builder()
                .roomId(roomId)
                .markdown(answerMarkdown)
                .build();

        String url = apiBaseUrl + "/messages";
        try {
            var response = webexRestTemplate.exchange(
                    url, HttpMethod.POST, new HttpEntity<>(outbound, authHeaders()), WebexMessage.class);
            return response.getBody() != null ? response.getBody().getId() : null;
        } catch (Exception e) {
            log.error("Failed to send reply to Webex room {}: {}", roomId, e.getMessage());
            return null;
        }
    }

    /**
     * Delete a message the bot previously posted (e.g. a "thinking..."
     * placeholder once the real answer is ready to replace it). Safe to call
     * with a null/blank id or on an already-deleted message — failures are
     * logged and swallowed since this is a best-effort cleanup step, not
     * something that should ever fail the user-facing reply.
     */
    public void deleteMessage(String messageId) {
        if (messageId == null || messageId.isBlank()) {
            return;
        }
        String url = apiBaseUrl + "/messages/" + messageId;
        try {
            webexRestTemplate.exchange(
                    url, HttpMethod.DELETE, new HttpEntity<>(authHeaders()), Void.class);
        } catch (Exception e) {
            log.warn("Failed to delete Webex message {}: {}", messageId, e.getMessage());
        }
    }

    /**
     * In group spaces Webex includes the bot's @mention as plain text
     * (e.g. "@TelecomBot what is our roaming policy?"). Strip it before
     * sending the remainder to RagQueryService. 1:1 messages have no mention.
     */
    public String stripBotMention(String text) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
        if (trimmed.startsWith("@")) {
            int firstSpace = trimmed.indexOf(' ');
            if (firstSpace > 0) {
                return trimmed.substring(firstSpace + 1).trim();
            }
        }
        return trimmed;
    }

    public String getBotEmail() {
        return botEmail;
    }
}
