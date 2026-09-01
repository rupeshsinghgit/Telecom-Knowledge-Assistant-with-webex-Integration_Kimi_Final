package com.rag.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Tracks a single async ingestion run (one file upload, or one URL) so the caller can
 * poll GET /api/documents/jobs/{id} instead of blocking on the HTTP request thread.
 *
 * totalChunks / processedChunks semantics differ slightly by source type:
 * - For DOCX/Excel/TXT/URL, the whole document is split upfront, so totalChunks is known
 *   before any embedding happens, and processedChunks climbs toward it batch by batch.
 * - For PDF (streamed page-group by page-group, see IngestionService#ingestPdfStreaming),
 *   the total isn't known upfront -- totalChunks and processedChunks are kept equal to each
 *   other as chunks are discovered/processed, so they represent "chunks handled so far", not
 *   "processed out of a known total", until the job reaches COMPLETED.
 */
@Entity
@Table(name = "ingestion_jobs")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IngestionJob {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String filename;

    @Column(length = 2048)
    private String source;

    private String type; // PDF, DOCX, EXCEL, TXT, URL

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private IngestionJobStatus status;

    @Builder.Default
    private int totalChunks = 0;

    @Builder.Default
    private int processedChunks = 0;

    @Column(length = 2000)
    private String errorMessage;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @PrePersist
    public void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.status == null) {
            this.status = IngestionJobStatus.PENDING;
        }
    }

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
