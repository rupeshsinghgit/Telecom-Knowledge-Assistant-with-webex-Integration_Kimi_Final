package com.rag.webex.service;

import com.rag.service.RagQueryService;
import com.rag.service.RagQueryService.RagResponse;
import com.rag.webex.dto.WebexMessage;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Bridges an incoming Webex message to your existing RagQueryService bean.
 * Runs @Async so the webhook controller can return HTTP 200 to Webex immediately
 * (the actual VectorDB search + LLM generation can take several seconds).
 *
 * Progress UX note: Webex's bot API has no "typing indicator" or "edit
 * message" endpoint — a bot can only create and delete messages. So the
 * closest thing to a live progress indicator is: post a placeholder message
 * right away, and delete it the moment the real answer is ready (the reply
 * then visually "replaces" it). For longer-running queries we also send one
 * follow-up nudge if the answer isn't ready within a few seconds, since
 * that's the only way to signal ongoing progress in a plain chat feed.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebexOrchestrationService {

    private final WebexService webexService;
    private final RagQueryService ragQueryService;

    @Value("${webex.progress.nudge-enabled:true}")
    private boolean nudgeEnabled;

    @Value("${webex.progress.nudge-after-seconds:15}")
    private long nudgeAfterSeconds;

    @Value("${webex.progress.delete-placeholder-on-complete:true}")
    private boolean deletePlaceholderOnComplete;

    // Small dedicated scheduler just for the "still working on it" nudge.
    // Not on the hot path — only ever holds a handful of pending timers.
    private final ScheduledExecutorService nudgeScheduler = Executors.newScheduledThreadPool(2);

    @PreDestroy
    public void shutdown() {
        nudgeScheduler.shutdownNow();
    }

    @Async
    public void handleIncomingMessage(String messageId, String roomId) {
        String placeholderMessageId = null;
        ScheduledFuture<?> nudgeTask = null;

        try {
            WebexMessage message = webexService.fetchMessage(messageId);
            if (message == null) {
                log.warn("Could not fetch Webex message {}", messageId);
                return;
            }

            String question = webexService.stripBotMention(message.getText());
            if (question.isBlank()) {
                webexService.sendReply(roomId, "I didn't catch a question — could you rephrase that?");
                return;
            }

            // #1 — immediate acknowledgment, worded based on the question
            String ackText = resolveAckMessage(question);
            placeholderMessageId = webexService.sendReply(roomId, ackText);

            // #2 — best-effort "in progress" signal: if this takes a while,
            // send one nudge so the room isn't just sitting silent.
            if (nudgeEnabled) {
                nudgeTask = nudgeScheduler.schedule(
                        () -> webexService.sendReply(roomId, "⏳ Still working on it — almost there..."),
                        nudgeAfterSeconds, TimeUnit.SECONDS);
            }

            log.info("Routing Webex question to RagQueryService: {}", question);
            RagResponse ragResponse = ragQueryService.query(question);

            cancelNudge(nudgeTask);
            if (deletePlaceholderOnComplete) {
                webexService.deleteMessage(placeholderMessageId);
            }

            webexService.sendReply(roomId, ragResponse.answer());
        } catch (Exception e) {
            log.error("Error handling Webex message {}: {}", messageId, e.getMessage(), e);
            cancelNudge(nudgeTask);
            if (deletePlaceholderOnComplete) {
                webexService.deleteMessage(placeholderMessageId);
            }
            webexService.sendReply(roomId, "Sorry, something went wrong while looking that up. Please try again shortly.");
        }
    }

    private void cancelNudge(ScheduledFuture<?> nudgeTask) {
        if (nudgeTask != null) {
            nudgeTask.cancel(false);
        }
    }

    // ── Keyword-based acknowledgment text ────────────────────────────────
    // "Looking" for lookup/retrieval-style questions, "thinking" for
    // reasoning/explanation-style ones, a neutral default otherwise.
    private static final List<String> LOOKING_KEYWORDS = List.of(
            "find", "search", "look up", "lookup", "where", "list", "show", "who", "which", "locate");

    private static final List<String> THINKING_KEYWORDS = List.of(
            "why", "how", "explain", "analyze", "analyse", "compare", "troubleshoot",
            "root cause", "recommend", "suggest", "best way", "difference between");

    private String resolveAckMessage(String question) {
        String q = question.toLowerCase(Locale.ROOT);

        if (THINKING_KEYWORDS.stream().anyMatch(q::contains)) {
            return "🤔 I am thinking about that...";
        }
        if (LOOKING_KEYWORDS.stream().anyMatch(q::contains)) {
            return "🔎 I am looking into that...";
        }
        return "⌛ Let me check that for you...";
    }
}
