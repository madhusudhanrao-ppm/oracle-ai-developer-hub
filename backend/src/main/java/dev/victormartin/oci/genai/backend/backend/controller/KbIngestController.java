package dev.victormartin.oci.genai.backend.backend.controller;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import dev.victormartin.oci.genai.backend.backend.dto.RagTextIngestRequest;
import dev.victormartin.oci.genai.backend.backend.service.KbIngestService;
import dev.victormartin.oci.genai.backend.backend.service.PDFConvertorService;
import jakarta.validation.Valid;

@RestController
public class KbIngestController {

    private static final Logger log = LoggerFactory.getLogger(KbIngestController.class);

    private final KbIngestService kbIngestService;
    private final PDFConvertorService pdfConvertorService;

    public KbIngestController(KbIngestService kbIngestService, PDFConvertorService pdfConvertorService) {
        this.kbIngestService = kbIngestService;
        this.pdfConvertorService = pdfConvertorService;
    }

    /**
     * Ingest a file (PDF or text) into the KB: documents, chunks, and embeddings.
     *
     * Headers (optional):
     * - X-Tenant-Id: tenant identifier (default "default")
     * - Embedding-Model-Id: OCI embedding model OCID or short name (must match 1024-dim schema unless changed)
     * - X-Doc-Id: override document id (if not provided, a hash-based id is generated)
     */
    @PostMapping("/api/kb/ingest")
    public KbIngestService.IngestSummary ingest(
            @RequestParam("file") MultipartFile multipartFile,
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId,
            @RequestHeader(value = "Embedding-Model-Id", required = false) String embeddingModelId,
            @RequestHeader(value = "X-Doc-Id", required = false) String docId
    ) {
        String filename = multipartFile.getOriginalFilename();
        String contentType = multipartFile.getContentType();
        log.info("KB ingest received file={} size={} contentType={}", filename, multipartFile.getSize(), contentType);

        String text;
        try {
            if ("application/pdf".equalsIgnoreCase(contentType)) {
                // Save to a temp file to reuse the existing PDFConvertorService
                File temp = File.createTempFile("kb-ingest-", ".pdf");
                try {
                    multipartFile.transferTo(temp);
                    text = pdfConvertorService.convert(temp.getAbsolutePath());
                } finally {
                    // Best effort cleanup
                    try { temp.delete(); } catch (Exception ignore) {}
                }
            } else if ("text/plain".equalsIgnoreCase(contentType)) {
                text = new String(multipartFile.getBytes(), StandardCharsets.UTF_8);
            } else {
                // Fallback: try to treat as text
                text = new String(multipartFile.getBytes(), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to read/convert file for ingestion: " + e.getMessage(), e);
        }

        String effectiveTenant = (tenantId == null || tenantId.isBlank()) ? "default" : tenantId;
        String title = filename != null ? filename : "uploaded";
        String uri = null; // optional
        String mime = contentType != null ? contentType : "application/octet-stream";
        String tagsJson = "[]";

        KbIngestService.IngestSummary summary = kbIngestService.ingestText(
                effectiveTenant,
                docId,
                title,
                uri,
                mime,
                tagsJson,
                text,
                embeddingModelId
        );
        log.info("KB ingest completed: docId={} chunks={} embeddings={}",
                summary.docId(), summary.chunkCount(), summary.embedCount());
        return summary;
    }

    /**
     * Ingest raw text directly (JSON body) into the KB: documents, chunks, and embeddings.
     * POST /api/kb/ingest-text
     */
    @PostMapping(value = "/api/kb/ingest-text", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public KbIngestService.IngestSummary ingestText(@Valid @RequestBody RagTextIngestRequest req) {
        String tenantId = (req.tenantId() == null || req.tenantId().isBlank()) ? "default" : req.tenantId();
        String title = (req.title() == null || req.title().isBlank()) ? "text" : req.title();
        String uri = (req.uri() == null || req.uri().isBlank()) ? null : req.uri();
        String mime = (req.mime() == null || req.mime().isBlank()) ? "text/plain" : req.mime();
        String embeddingModelId = (req.embeddingModelId() == null || req.embeddingModelId().isBlank()) ? null : req.embeddingModelId();

        // Build tags JSON array safely from list
        String tagsJson = "[]";
        List<String> tags = req.tags();
        if (tags != null && !tags.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            sb.append('[');
            boolean first = true;
            for (String t : tags) {
                if (t == null) continue;
                String cleaned = t.replace("\\", "\\\\").replace("\"", "\\\""); // minimal escape
                if (!first) sb.append(',');
                sb.append('"').append(cleaned).append('"');
                first = false;
            }
            sb.append(']');
            tagsJson = sb.toString();
        }

        // Delegate to service
        KbIngestService.IngestSummary summary = kbIngestService.ingestText(
                tenantId,
                req.docId(),
                title,
                uri,
                mime,
                tagsJson,
                req.text(),
                embeddingModelId
        );
        log.info("KB text ingest completed: docId={} chunks={} embeddings={}",
                summary.docId(), summary.chunkCount(), summary.embedCount());
        return summary;
    }
}
