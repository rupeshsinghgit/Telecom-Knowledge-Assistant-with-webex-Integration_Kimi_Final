package com.rag.webex.util;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Webex signs every webhook POST with header "X-Spark-Signature":
 * HMAC-SHA1(secret, rawRequestBody), hex encoded.
 * Verifying this stops randoms on the internet from POSTing fake "messages"
 * to your public endpoint and triggering RAG queries / replies.
 */
@Component
public class WebhookSignatureValidator {

    private static final String HMAC_SHA1 = "HmacSHA1";

    public boolean isValid(String rawBody, String signatureHeader, String secret) {
        if (signatureHeader == null || rawBody == null || secret == null || secret.isBlank()) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance(HMAC_SHA1);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA1));
            byte[] rawHmac = mac.doFinal(rawBody.getBytes(StandardCharsets.UTF_8));
            String computed = bytesToHex(rawHmac);
            return MessageDigest.isEqual(
                    computed.getBytes(StandardCharsets.UTF_8),
                    signatureHeader.trim().getBytes(StandardCharsets.UTF_8)
            );
        } catch (Exception e) {
            return false;
        }
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
