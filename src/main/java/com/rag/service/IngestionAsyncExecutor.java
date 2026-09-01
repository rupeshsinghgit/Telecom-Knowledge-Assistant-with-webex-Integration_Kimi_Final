package com.rag.service;

import com.rag.document.DocumentLoaderService;
import com.rag.document.DocumentType;
import com.rag.model.DocumentRecord;
import com.rag.model.IngestionJobStatus;
import com.rag.repository.DocumentRepository;
import com.rag.repository.IngestionJobRepository;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Does the actual parse -> split -> embed -> store work, off the HTTP thread.
 *
 * Kept as a bean SEPARATE from IngestionService so @Async is honored -- see the note on
 * IngestionService for why. Every public method here is expected to be called only via the
 * injected IngestionAsyncExecutor reference in IngestionService, never via `this.` from
 * inside this same class (that would reintroduce the same self-invocation bug).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IngestionAsyncExecutor {

    private final DocumentLoaderService loaderService;
    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final DocumentRepository documentRepository;
    private final IngestionJobRepository jobRepository;

    @Value("${rag.chunk-size}")
    private int chunkSize;

    @Value("${rag.chunk-overlap}")
    private int chunkOverlap;

    // Chunks per embedAll()/addAll() call. If the embedding backend struggles under a batch
    // this size (timeouts, connection errors under load), lower this via
    // RAG_EMBED_BATCH_SIZE without touching code -- e.g. try 25.
    @Value("${rag.ingestion.embed-batch-size:75}")
    private int embedBatchSize;

    // PDF pages extracted together before splitting/embedding, in the streaming path.
    @Value("${rag.ingestion.pdf-pages-per-batch:5}")
    private int pdfPagesPerBatch;

    // ── Async entry points ──────────────────────────────────────────────────────────────

    @Async("ingestionTaskExecutor")
    public void processFileAsync(String jobId, byte[] content, String filename, DocumentType type) {
        try {
            markProcessing(jobId);

            if (type == DocumentType.PDF) {
                ingestPdfStreaming(jobId, content, filename);
            } else {
                Document document = loaderService.loadFromBytes(content, filename, type);
                ingestDocumentBatched(jobId, document, filename, type.name());
            }

            markCompleted(jobId);
        } catch (Exception e) {
            // This is now the ONLY place an embedding/backend failure surfaces -- as a FAILED
            // job with errorMessage set, not as a 500 back to the HTTP caller. If you still see
            // errors on the request thread after this change, @Async isn't taking effect and
            // something upstream is still calling this synchronously.
            log.error("Ingestion job {} failed for file '{}': {}", jobId, filename, e.getMessage(), e);
            markFailed(jobId, e.getMessage());
        }
    }

    @Async("ingestionTaskExecutor")
    public void processUrlAsync(String jobId, String url) {
        try {
            markProcessing(jobId);
            Document document = loaderService.loadUrl(url);
            ingestDocumentBatched(jobId, document, url, "URL");
            markCompleted(jobId);
        } catch (Exception e) {
            log.error("Ingestion job {} failed for URL '{}': {}", jobId, url, e.getMessage(), e);
            markFailed(jobId, e.getMessage());
        }
    }

    // ── Batched path: whole Document already in memory (DOCX / Excel / TXT / URL) ──────────
    private void ingestDocumentBatched(String jobId, Document document, String source, String type) {
        DocumentSplitter splitter = DocumentSplitters.recursive(chunkSize, chunkOverlap);
        List<TextSegment> segments = splitter.split(document);
        log.info("Split '{}' into {} chunks", source, segments.size());

        updateProgress(jobId, segments.size(), 0);

        int processed = 0;
        for (int i = 0; i < segments.size(); i += embedBatchSize) {
            List<TextSegment> batch = segments.subList(i, Math.min(i + embedBatchSize, segments.size()));
            processed += flushBatch(batch);
            updateProgress(jobId, segments.size(), processed);
        }

        saveDocumentRecord(source, source, type, segments.size());
    }

    // ── Streaming path: PDF, page-group by page-group ───────────────────────────────────
    private void ingestPdfStreaming(String jobId, byte[] pdfBytes, String filename) throws IOException {
        DocumentSplitter splitter = DocumentSplitters.recursive(chunkSize, chunkOverlap);
        List<TextSegment> buffer = new ArrayList<>();
        int totalChunksSoFar = 0;

        try (PDDocument pdDocument = PDDocument.load(new ByteArrayInputStream(pdfBytes))) {
            int pageCount = pdDocument.getNumberOfPages();
            log.info("PDF '{}' has {} pages -- streaming in groups of {}", filename, pageCount, pdfPagesPerBatch);

            for (int startPage = 1; startPage <= pageCount; startPage += pdfPagesPerBatch) {
                int endPage = Math.min(startPage + pdfPagesPerBatch - 1, pageCount);

                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setStartPage(startPage);
                stripper.setEndPage(endPage);
                String pageGroupText = stripper.getText(pdDocument);

                if (pageGroupText == null || pageGroupText.isBlank()) {
                    continue;
                }

                Document pageDocument = Document.from(pageGroupText,
                        Metadata.from("source", filename)
                                .put("type", "PDF")
                                .put("pages", startPage + "-" + endPage));

                List<TextSegment> pageSegments = splitter.split(pageDocument);
                buffer.addAll(pageSegments);

                if (buffer.size() >= embedBatchSize) {
                    totalChunksSoFar += flushBatch(buffer);
                    updateProgress(jobId, totalChunksSoFar, totalChunksSoFar);
                    buffer.clear();
                }
            }
        }

        if (!buffer.isEmpty()) {
            totalChunksSoFar += flushBatch(buffer);
            updateProgress(jobId, totalChunksSoFar, totalChunksSoFar);
            buffer.clear();
        }

        saveDocumentRecord(filename, filename, "PDF", totalChunksSoFar);
    }

    // ── Shared batch flush ───────────────────────────────────────────────────────────────
    private int flushBatch(List<TextSegment> segments) {
        if (segments.isEmpty()) {
            return 0;
        }
        List<Embedding> embeddings = embeddingModel.embedAll(segments).content();
        embeddingStore.addAll(embeddings, segments);
        log.debug("Flushed batch of {} chunks", segments.size());
        return segments.size();
    }

    private void saveDocumentRecord(String name, String source, String type, int chunkCount) {
        DocumentRecord record = DocumentRecord.builder()
                .name(name)
                .type(type)
                .source(source)
                .chunkCount(chunkCount)
                .build();
        documentRepository.save(record);
        log.info("Saved DocumentRecord for '{}' ({} chunks)", source, chunkCount);
    }

    // ── Job status helpers ───────────────────────────────────────────────────────────────
    private void markProcessing(String jobId) {
        jobRepository.findById(jobId).ifPresent(job -> {
            job.setStatus(IngestionJobStatus.PROCESSING);
            jobRepository.save(job);
        });
    }

    private void updateProgress(String jobId, int totalChunks, int processedChunks) {
        jobRepository.findById(jobId).ifPresent(job -> {
            job.setTotalChunks(totalChunks);
            job.setProcessedChunks(processedChunks);
            jobRepository.save(job);
        });
    }

    private void markCompleted(String jobId) {
        jobRepository.findById(jobId).ifPresent(job -> {
            job.setStatus(IngestionJobStatus.COMPLETED);
            jobRepository.save(job);
        });
    }

    private void markFailed(String jobId, String errorMessage) {
        jobRepository.findById(jobId).ifPresent(job -> {
            job.setStatus(IngestionJobStatus.FAILED);
            job.setErrorMessage(errorMessage != null ? errorMessage : "Unknown error");
            jobRepository.save(job);
        });
    }
}
