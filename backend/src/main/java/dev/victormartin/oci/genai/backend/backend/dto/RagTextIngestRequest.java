package dev.victormartin.oci.genai.backend.backend.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * JSON payload to ingest raw text into the KB (documents/chunks/embeddings).
 */
public record RagTextIngestRequest(
        String tenantId,                 // default: "default"
        String docId,                    // optional override; otherwise derived from content hash
        String title,                    // optional title / filename-equivalent
        String uri,                      // optional source URI
        String mime,                     // default: "text/plain"
        List<String> tags,               // optional tags
        String embeddingModelId,         // optional; falls back to configured/candidates
        @NotBlank
        @Size(max = 400_000)             // guard extremely large bodies; can be tuned by property if needed
        String text                      // required raw text
) {}
