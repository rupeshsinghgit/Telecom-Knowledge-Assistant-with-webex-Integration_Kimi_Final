package com.rag.service;

import com.rag.document.DocumentType;
import com.rag.model.IngestionJob;
import com.rag.model.IngestionJobStatus;
import com.rag.repository.DocumentRepository;
import com.rag.repository.IngestionJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * Entry point for ingestion requests. Deliberately thin: creates the IngestionJob row and
 * immediately delegates the actual work to IngestionAsyncExecutor -- a SEPARATE Spring bean.
 *
 * This separation is required, not stylistic. Spring's @Async works via a dynamic proxy
 * wrapped around the bean. If the @Async worker method lived here and were called as
 * `this.processFileAsync(...)` (self-invocation), the call would bypass the proxy entirely
 * and execute synchronously, on the caller's HTTP request thread -- silently defeating async
 * processing while still compiling and looking correct. Keeping the @Async methods on a
 * different injected bean forces the call through the proxy, so it's actually dispatched to
 * the ingestionTaskExecutor thread pool.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IngestionService {

    private final IngestionAsyncExecutor asyncExecutor;
    private final DocumentRepository documentRepository;
    private final IngestionJobRepository jobRepository;

    public IngestionJob submitFileIngestion(MultipartFile file) throws IOException {
        String filename = file.getOriginalFilename();
        byte[] content = file.getBytes();
        DocumentType type = DocumentType.fromFilename(filename);

        IngestionJob job = jobRepository.save(IngestionJob.builder()
                .filename(filename)
                .source(filename)
                .type(type.name())
                .status(IngestionJobStatus.PENDING)
                .build());

        asyncExecutor.processFileAsync(job.getId(), content, filename, type);
        return job;
    }

    public IngestionJob submitUrlIngestion(String url) {
        // Dedup check stays synchronous/upfront so a duplicate is rejected immediately with a
        // 400, rather than only surfacing as a FAILED job later.
        if (documentRepository.existsBySource(url)) {
            log.warn("URL already ingested: {}", url);
            throw new IllegalArgumentException("URL already ingested: " + url);
        }

        IngestionJob job = jobRepository.save(IngestionJob.builder()
                .filename(url)
                .source(url)
                .type("URL")
                .status(IngestionJobStatus.PENDING)
                .build());

        asyncExecutor.processUrlAsync(job.getId(), url);
        return job;
    }
}
