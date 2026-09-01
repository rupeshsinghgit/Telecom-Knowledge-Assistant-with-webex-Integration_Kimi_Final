package com.rag.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Dedicated executor for document ingestion (file upload / URL, and folder ingestion in
 * Phase 1.2). Kept separate from the default @Async executor that WebexConfig's @EnableAsync
 * already uses for Webex message handling, so a large or busy ingestion job can't starve
 * Webex reply latency, and vice versa. @EnableAsync itself is already declared on WebexConfig --
 * no need to declare it again here, Spring only needs one.
 *
 * corePoolSize also doubles as the "how many files ingest concurrently" knob once folder
 * ingestion (Phase 1.2a) reuses this executor -- start conservative and raise only after
 * confirming the LiteLLM Gateway's actual concurrent-request tolerance.
 */
@Slf4j
@Configuration
public class AsyncConfig {

    @Bean(name = "ingestionTaskExecutor")
    public ThreadPoolTaskExecutor ingestionTaskExecutor(
            @Value("${rag.ingestion.executor.core-pool-size:3}") int corePoolSize,
            @Value("${rag.ingestion.executor.max-pool-size:5}") int maxPoolSize,
            @Value("${rag.ingestion.executor.queue-capacity:50}") int queueCapacity) {

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("ingest-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();

        log.info("Ingestion executor initialized: core={}, max={}, queueCapacity={}",
                corePoolSize, maxPoolSize, queueCapacity);
        return executor;
    }
}
