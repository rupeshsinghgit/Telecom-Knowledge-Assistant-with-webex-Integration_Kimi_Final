package com.rag.controller;

import com.rag.model.DocumentRecord;
import com.rag.model.IngestionJob;
import com.rag.repository.DocumentRepository;
import com.rag.repository.IngestionJobRepository;
import com.rag.service.IngestionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
@Tag(name = "Document Ingestion", description = "Upload and ingest documents into the knowledge base")
public class IngestionController {

    private final IngestionService ingestionService;
    private final DocumentRepository documentRepository;
    private final IngestionJobRepository jobRepository;

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(
            summary = "Upload a file (PDF, DOCX, XLSX/XLS, TXT) for async ingestion",
            description = "Returns immediately with 202 Accepted and a job id. " +
                    "Poll GET /api/documents/jobs/{jobId} for progress/status."
    )
    public ResponseEntity<IngestionJob> uploadFile(@RequestPart("file") MultipartFile file) {
        try {
            IngestionJob job = ingestionService.submitFileIngestion(file);
            return ResponseEntity.accepted().body(job);
        } catch (IOException e) {
            log.error("Failed to read uploaded file: {}", file.getOriginalFilename(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @PostMapping("/url")
    @Operation(
            summary = "Ingest a web page by URL for async ingestion",
            description = "Returns immediately with 202 Accepted and a job id. " +
                    "Poll GET /api/documents/jobs/{jobId} for progress/status."
    )
    public ResponseEntity<?> ingestUrl(@RequestBody Map<String, String> body) {
        String url = body.get("url");
        if (url == null || url.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "URL is required"));
        }
        try {
            IngestionJob job = ingestionService.submitUrlIngestion(url);
            return ResponseEntity.accepted().body(job);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/jobs/{jobId}")
    @Operation(summary = "Check status/progress of an ingestion job")
    public ResponseEntity<IngestionJob> getJobStatus(@PathVariable String jobId) {
        return jobRepository.findById(jobId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping
    @Operation(summary = "List all ingested documents")
    public ResponseEntity<List<DocumentRecord>> listDocuments() {
        return ResponseEntity.ok(documentRepository.findAll());
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a document record (does not remove embeddings)")
    public ResponseEntity<Void> deleteDocument(@PathVariable String id) {
        documentRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }
}
