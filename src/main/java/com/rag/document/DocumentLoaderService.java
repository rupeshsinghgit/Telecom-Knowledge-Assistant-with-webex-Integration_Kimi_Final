package com.rag.document;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.parser.apache.pdfbox.ApachePdfBoxDocumentParser;
import dev.langchain4j.data.document.parser.apache.poi.ApachePoiDocumentParser;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.StringJoiner;

/**
 * All parsing methods now take plain byte[] + filename at their core, with MultipartFile
 * overloads kept as thin wrappers for backward compatibility.
 *
 * This matters because ingestion now happens on an @Async background thread
 * (see IngestionService) -- Spring/Tomcat can recycle a MultipartFile's backing temp file as
 * soon as the HTTP request completes, which happens *before* the async method runs. Reading
 * bytes once on the request thread and passing them onward avoids that class of bug entirely.
 * It also gives Phase 1.2 (folder ingestion) a natural common entry point, since files read
 * from disk are bytes too, not MultipartFiles.
 */
@Slf4j
@Service
public class DocumentLoaderService {

    private final ApachePdfBoxDocumentParser pdfParser = new ApachePdfBoxDocumentParser();
    private final ApachePoiDocumentParser poiParser     = new ApachePoiDocumentParser();

    // ── Dispatcher (byte[] core) ─────────────────────────────────────────────
    public Document loadFromBytes(byte[] content, String filename, DocumentType type) throws IOException {
        return switch (type) {
            case PDF   -> loadPdf(content, filename);
            case DOCX  -> loadDocx(content, filename);
            case EXCEL -> loadExcel(content, filename);
            default    -> loadText(content, filename);
        };
    }

    // ── PDF ──────────────────────────────────────────────────────────────────
    // Whole-document parse, kept for any caller that wants a single Document object.
    // The actual upload/folder ingestion path does NOT use this for PDFs -- it uses
    // IngestionService#ingestPdfStreaming, which extracts + chunks + embeds + stores
    // page-group by page-group instead of building one massive in-memory string first.
    // See AGENT.md / Enhancement-Plan-Phase1-3.md §1.1 for why that distinction matters.
    public Document loadPdf(byte[] content, String filename) throws IOException {
        log.info("Loading PDF (whole-document parse): {}", filename);
        try (InputStream is = new ByteArrayInputStream(content)) {
            Document doc = pdfParser.parse(is);
            return Document.from(doc.text(),
                    Metadata.from("source", filename).put("type", "PDF"));
        }
    }

    // ── DOCX ─────────────────────────────────────────────────────────────────
    public Document loadDocx(byte[] content, String filename) throws IOException {
        log.info("Loading DOCX: {}", filename);
        try (InputStream is = new ByteArrayInputStream(content)) {
            Document doc = poiParser.parse(is);
            return Document.from(doc.text(),
                    Metadata.from("source", filename).put("type", "DOCX"));
        }
    }

    // ── Excel (XLSX / XLS) ───────────────────────────────────────────────────
    // NOTE: still one-shot (whole workbook built into one StringBuilder before returning),
    // same limitation the PDF path used to have. Left as-is for this pass -- POI's streaming
    // SXSSF/event API is a bigger change than PDF's page-based approach and wasn't in scope
    // here. Flag it if a specific large XLSX is actually causing problems and we'll do it
    // as a follow-up the same way we did PDF.
    public Document loadExcel(byte[] content, String filename) throws IOException {
        log.info("Loading Excel: {}", filename);
        String lower = filename != null ? filename.toLowerCase() : "";

        try (InputStream is = new ByteArrayInputStream(content)) {
            Workbook workbook = lower.endsWith(".xls")
                    ? new HSSFWorkbook(is)
                    : new XSSFWorkbook(is);

            StringBuilder sb = new StringBuilder();
            for (Sheet sheet : workbook) {
                sb.append("=== Sheet: ").append(sheet.getSheetName()).append(" ===\n");
                for (Row row : sheet) {
                    StringJoiner rowJoiner = new StringJoiner(" | ");
                    for (Cell cell : row) {
                        rowJoiner.add(getCellValue(cell));
                    }
                    sb.append(rowJoiner).append("\n");
                }
                sb.append("\n");
            }
            workbook.close();

            return Document.from(sb.toString(),
                    Metadata.from("source", filename).put("type", "EXCEL"));
        }
    }

    // ── Web URL ──────────────────────────────────────────────────────────────
    public Document loadUrl(String url) throws IOException {
        log.info("Loading URL: {}", url);
        org.jsoup.nodes.Document jsoupDoc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0")
                .timeout(15_000)
                .get();

        String text = jsoupDoc.body().text();
        return Document.from(text,
                Metadata.from("source", url)
                        .put("type", "URL")
                        .put("title", jsoupDoc.title()));
    }

    // ── TXT / Plain text ─────────────────────────────────────────────────────
    public Document loadText(byte[] content, String filename) {
        log.info("Loading text file: {}", filename);
        return Document.from(new String(content),
                Metadata.from("source", filename).put("type", "TXT"));
    }

    // ── MultipartFile overloads (thin wrappers) ─────────────────────────────
    public Document load(MultipartFile file) throws IOException {
        DocumentType type = DocumentType.fromFilename(file.getOriginalFilename());
        return loadFromBytes(file.getBytes(), file.getOriginalFilename(), type);
    }

    public Document loadPdf(MultipartFile file) throws IOException {
        return loadPdf(file.getBytes(), file.getOriginalFilename());
    }

    public Document loadDocx(MultipartFile file) throws IOException {
        return loadDocx(file.getBytes(), file.getOriginalFilename());
    }

    public Document loadExcel(MultipartFile file) throws IOException {
        return loadExcel(file.getBytes(), file.getOriginalFilename());
    }

    public Document loadText(MultipartFile file) throws IOException {
        return loadText(file.getBytes(), file.getOriginalFilename());
    }

    // ── Helpers ──────────────────────────────────────────────────────────────
    private String getCellValue(Cell cell) {
        return switch (cell.getCellType()) {
            case STRING  -> cell.getStringCellValue();
            case NUMERIC -> DateUtil.isCellDateFormatted(cell)
                    ? cell.getDateCellValue().toString()
                    : String.valueOf(cell.getNumericCellValue());
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> cell.getCellFormula();
            default      -> "";
        };
    }
}
