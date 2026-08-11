package com.rag.webex.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * Envelope Webex POSTs to your webhook URL when the subscribed event fires
 * (resource=messages, event=created). Webex does NOT include the message text
 * here -- only IDs. WebexService.fetchMessage() makes a follow-up GET call to
 * retrieve the actual text.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class WebexWebhookPayload {
    private String id;
    private String name;
    private String resource;  // "messages"
    private String event;    // "created"
    private String orgId;
    private WebhookData data;

    @lombok.Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class WebhookData {
        private String id;          // message id -> fetch full message with this
        private String roomId;
        private String roomType;
        private String personId;
        private String personEmail;
    }
}
