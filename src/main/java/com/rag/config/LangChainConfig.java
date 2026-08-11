package com.rag.config;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import dev.langchain4j.data.segment.TextSegment;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class LangChainConfig {

    @Value("${llm.chat.base-url}")
    private String chatBaseUrl;

    @Value("${llm.chat.model}")
    private String chatModel;

    @Value("${llm.chat.api-key}")
    private String chatApiKey;

    @Value("${llm.embedding.base-url}")
    private String embeddingBaseUrl;

    @Value("${llm.embedding.model}")
    private String embeddingModel;

    @Value("${llm.embedding.api-key}")
    private String embeddingApiKey;

    @Value("${spring.datasource.url}")
    private String datasourceUrl;

    @Value("${spring.datasource.username}")
    private String datasourceUsername;

    @Value("${spring.datasource.password}")
    private String datasourcePassword;

    @Value("${pgvector.dimension}")
    private int vectorDimension;

    @Value("${pgvector.table-name}")
    private String tableName;

    // ── Chat Model (kimi-k2.6, self-hosted via vLLM/TGI OpenAI-compatible API) ──
    @Bean
    public ChatLanguageModel chatLanguageModel() {
        return OpenAiChatModel.builder()
                .baseUrl(chatBaseUrl)          // e.g. http://localhost:8000/v1
                .apiKey(chatApiKey)             // vLLM/TGI ignore this unless you set --api-key; keep a placeholder
                .modelName(chatModel)           // e.g. kimi-k2.6
                .timeout(Duration.ofSeconds(180))
                .temperature(0.7)
                .build();
    }

    // ── Embedding Model (qwen3-vl-embedding-8b, self-hosted via vLLM/TGI) ───────
    @Bean
    public EmbeddingModel embeddingModel() {
        return OpenAiEmbeddingModel.builder()
                .baseUrl(embeddingBaseUrl)      // e.g. http://localhost:8001/v1
                .apiKey(embeddingApiKey)
                .modelName(embeddingModel)      // e.g. qwen3-vl-embedding-8b
                .timeout(Duration.ofSeconds(180))
                .build();
    }


    // ── PgVector Embedding Store ─────────────────────────────────────────────
    @Bean
    public EmbeddingStore<TextSegment> embeddingStore() {
        // Parse jdbc URL → host/port/db for PgVector builder
        // jdbc:postgresql://localhost:5432/ragdb
        String stripped = datasourceUrl.replace("jdbc:postgresql://", "");
        String[] hostAndDb = stripped.split("/");
        String[] hostPort = hostAndDb[0].split(":");
        String host = hostPort[0];
        int port = hostPort.length > 1 ? Integer.parseInt(hostPort[1]) : 5432;
        String database = hostAndDb[1];

        return PgVectorEmbeddingStore.builder()
                .host(host)
                .port(port)
                .database(database)
                .user(datasourceUsername)
                .password(datasourcePassword)
                .table(tableName)
                .dimension(vectorDimension)
                .createTable(true)
                .build();
    }
}
