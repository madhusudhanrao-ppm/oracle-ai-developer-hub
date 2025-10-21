package dev.victormartin.oci.genai.backend.backend.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import com.oracle.bmc.generativeaiinference.GenerativeAiInferenceClient;
import com.oracle.bmc.generativeaiinference.model.EmbedTextDetails;
import com.oracle.bmc.generativeaiinference.model.OnDemandServingMode;
import com.oracle.bmc.generativeaiinference.requests.EmbedTextRequest;
import com.oracle.bmc.generativeaiinference.responses.EmbedTextResponse;
import com.oracle.bmc.model.BmcException;

/**
 * Service that ingests plain text into the KB tables:
 * - kb_documents (one row per document)
 * - kb_chunks (one row per chunk)
 * - kb_embeddings (one row per chunk containing the embedding VECTOR)
 *
 * Notes:
 * - VECTOR column in kb_embeddings is defined as VECTOR(1024, FLOAT32) by Liquibase migration.
 * - This service targets a 1024-dim embedding model (e.g., cohere.embed-english-v3.0).
 * - If VECTOR function binding is unavailable, it falls back to inserting NULL embedding,
 *   which still allows simple joins to produce snippets (though not ordered by similarity).
 */
@Service
public class KbIngestService {

    private static final Logger log = LoggerFactory.getLogger(KbIngestService.class);

    private final DataSource dataSource;
    private final GenAiInferenceClientService inferenceClientService;
    private final Environment env;

    public KbIngestService(DataSource dataSource,
                           GenAiInferenceClientService inferenceClientService,
                           Environment env) {
        this.dataSource = dataSource;
        this.inferenceClientService = inferenceClientService;
        this.env = env;
    }

    /**
     * Summary returned after ingestion.
     */
    public static record IngestSummary(String docId, int chunkCount, int embedCount, String hash) {}

    /**
     * Simple chunk container.
     */
    private static record Chunk(int ix, String text, String sourceMeta) {}

    /**
     * Ingest a document's text into the KB.
     *
     * @param tenantId           tenant identifier (use same value as retrieval; e.g. "default" or "public")
     * @param docId              document id; if null or blank, a hash-based id will be generated
     * @param title              optional title to store (may be ignored if column not present)
     * @param uri                optional uri to store (may be ignored if column not present)
     * @param mime               mime type (e.g. application/pdf, text/plain)
     * @param tagsJson           JSON string for tags (e.g., "[]", or "{\"keywords\":[\"x\",\"y\"]}")
     * @param text               full extracted plain text
     * @param embeddingModelId   model id for embeddings; if null, falls back to configured/candidates
     * @return summary of ingestion
     */
    public IngestSummary ingestText(String tenantId,
                                    String docId,
                                    String title,
                                    String uri,
                                    String mime,
                                    String tagsJson,
                                    String text,
                                    String embeddingModelId) {

        String effectiveTenant = Objects.requireNonNullElse(tenantId, "default");
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Empty text - nothing to ingest");
        }

        // Generate docId if needed (hash of content)
        String contentHash = sha256Base64(trimmed);
        String effectiveDocId = (docId != null && !docId.isBlank()) ? docId : "doc_" + contentHash.substring(0, 16);

        // Upsert document
        upsertDocument(effectiveDocId, effectiveTenant, title, uri, mime, tagsJson, contentHash);

        // Chunk the text
        List<Chunk> chunks = chunkText(trimmed, 2000, 300);
        log.info("KB ingest: chunking produced {} chunks for docId={} tenant={}", chunks.size(), effectiveDocId, effectiveTenant);

        int embedCount = 0;
        GenerativeAiInferenceClient client = inferenceClientService.getClient();
        String compartmentId = env.getProperty("genai.compartment_id");
        String modelId = resolveEmbeddingModelId(embeddingModelId);
        log.info("KB ingest: embedding model resolved to {} (expected dimension ~1024)", modelId);

        // Insert chunks and embeddings
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                for (Chunk c : chunks) {
                    long chunkId;
                    try {
                        chunkId = insertChunk(conn, effectiveDocId, effectiveTenant, c);
                    } catch (SQLException ex) {
                        log.warn("KB ingest: insertChunk failed for docId={} ix={} ({}) {}", effectiveDocId, c.ix(), ex.getClass().getSimpleName(), ex.getMessage());
                        throw ex;
                    }
                    List<Float> emb = null;
                    try {
                        emb = embedWithModel(client, compartmentId, modelId, c.text());
                    } catch (BmcException e) {
                        // Try next candidate if 404 or rethrow; the caller can adjust model/config
                        log.warn("Embedding failed for chunk {} (status {}): {}", c.ix(), e.getStatusCode(), e.getMessage());
                    } catch (RuntimeException e) {
                        log.warn("Embedding runtime failure for chunk {}: {}", c.ix(), e.getMessage());
                    }

                    if (emb != null && !emb.isEmpty()) {
                        log.info("KB ingest: got embedding for chunk {} (len={})", c.ix(), emb.size());
                        boolean ok = insertEmbeddingVector(conn, chunkId, emb);
                        if (ok) {
                            embedCount++;
                        } else {
                            log.warn("KB ingest: insertEmbeddingVector returned false; inserting NULL embedding for chunk {}", c.ix());
                            insertEmbeddingNull(conn, chunkId);
                        }
                    } else {
                        // Fallback: insert row with NULL embedding so joins still produce snippets
                        insertEmbeddingNull(conn, chunkId);
                    }
                }
                conn.commit();
            } catch (SQLException ex) {
                conn.rollback();
                throw ex;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new RuntimeException("KB ingest failed: " + e.getMessage(), e);
        }

        if (embedCount == 0) {
            log.warn("Ingest produced ZERO embeddings for docId={} tenant={}. Retrieval may be weak until VECTOR is available or model config fixed.", effectiveDocId, effectiveTenant);
        }
        log.info("Ingested docId={} tenant={} chunks={} embeddings={}", effectiveDocId, effectiveTenant, chunks.size(), embedCount);
        return new IngestSummary(effectiveDocId, chunks.size(), embedCount, contentHash);
    }

    private String resolveEmbeddingModelId(String provided) {
        if (provided != null && !provided.isBlank()) return provided;
        String configured = env.getProperty("genai.embed_model_id");
        if (configured != null && !configured.isBlank()) return configured;
        // Fall back to known 1024-dim models (match schema)
        return "cohere.embed-english-v3.0";
    }

    private String sha256Base64(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest(text.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(dig);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private List<Chunk> chunkText(String text, int size, int overlap) {
        List<Chunk> out = new ArrayList<>();
        int n = text.length();
        int ix = 0;
        int chunkIx = 0;
        while (ix < n) {
            int end = Math.min(n, ix + size);
            String slice = text.substring(ix, end);
            String meta = "{\"range\":{" +
                    "\"start\":" + ix + "," +
                    "\"end\":" + end +
                    "}}";
            out.add(new Chunk(chunkIx, slice, meta));
            chunkIx++;
            if (end == n) break;
            ix = Math.max(ix + size - overlap, end); // ensure progress
        }
        return out;
    }

    private void upsertDocument(String docId,
                                String tenantId,
                                String title,
                                String uri,
                                String mime,
                                String tagsJson,
                                String hash) {
        // Insert minimal required columns to be safe across possible schema variants.
        // Prefer columns confirmed in migrations: doc_id, tenant_id, tags_json, hash, active
        String sql = "MERGE INTO kb_documents d " +
                "USING (SELECT ? AS doc_id, ? AS tenant_id FROM dual) s " +
                "ON (d.doc_id = s.doc_id) " +
                "WHEN MATCHED THEN UPDATE SET d.tenant_id = s.tenant_id, d.active = 1, d.hash = ? " +
                "WHEN NOT MATCHED THEN INSERT (doc_id, tenant_id, tags_json, hash, active) VALUES (?, ?, ?, ?, 1)";

        String tags = (tagsJson == null || tagsJson.isBlank()) ? "[]" : tagsJson;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, docId);
            ps.setString(2, tenantId);
            ps.setString(3, hash);
            ps.setString(4, docId);
            ps.setString(5, tenantId);
            ps.setString(6, tags);
            ps.setString(7, hash);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("Document upsert failed for docId={} ({}). Continuing: {}", docId, e.getClass().getSimpleName(), e.getMessage());
        }
    }

    private long insertChunk(Connection conn, String docId, String tenantId, Chunk c) throws SQLException {
        String sql = "INSERT INTO kb_chunks (doc_id, tenant_id, chunk_ix, text, source_meta) VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            // Log sizes and snippet for observability
            int textLen = (c.text() == null ? 0 : c.text().length());
            int metaLen = (c.sourceMeta() == null ? 0 : c.sourceMeta().length());
            String metaSnippet = (c.sourceMeta() == null ? "" : c.sourceMeta().substring(0, Math.min(128, c.sourceMeta().length())));
            log.info("insertChunk: docId={} ix={} textLen={} metaLen={} metaSnippet={}", docId, c.ix(), textLen, metaLen, metaSnippet);

            ps.setString(1, docId);
            ps.setString(2, tenantId);
            ps.setInt(3, c.ix());
            ps.setString(4, c.text());
            ps.setString(5, c.sourceMeta());
            ps.executeUpdate();
            log.info("insertChunk: executed insert for docId={} ix={}", docId, c.ix());

            // Try to retrieve generated key, but don't abort if driver/env doesn't support it
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs != null && rs.next()) {
                    return rs.getLong(1);
                }
            } catch (SQLException gke) {
                log.warn("insertChunk: getGeneratedKeys failed ({}): {}. Falling back to SELECT id ...",
                        gke.getClass().getSimpleName(), gke.getMessage());
            }
        }
        // Fallback: query sequence via SELECT if identity retrieval not enabled
        try (PreparedStatement q = conn.prepareStatement("SELECT id FROM kb_chunks WHERE doc_id = ? AND tenant_id = ? AND chunk_ix = ?")) {
            q.setString(1, docId);
            q.setString(2, tenantId);
            q.setInt(3, c.ix());
            try (ResultSet rs = q.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        }
        throw new SQLException("Failed to retrieve chunk id for docId=" + docId + " ix=" + c.ix());
    }

    private List<Float> embedWithModel(GenerativeAiInferenceClient client,
                                         String compartmentId,
                                         String modelId,
                                         String input) {
        EmbedTextDetails details = EmbedTextDetails.builder()
                .inputs(List.of(input))
                .servingMode(OnDemandServingMode.builder().modelId(modelId).build())
                .compartmentId(compartmentId)
                .isEcho(false)
                .build();

        EmbedTextResponse resp = client.embedText(EmbedTextRequest.builder()
                .embedTextDetails(details)
                .build());

        if (resp.getEmbedTextResult() == null
                || resp.getEmbedTextResult().getEmbeddings() == null
                || resp.getEmbedTextResult().getEmbeddings().isEmpty()) {
            throw new IllegalStateException("No embedding returned for input");
        }

        Object first = resp.getEmbedTextResult().getEmbeddings().get(0);
        if (first instanceof List) {
            @SuppressWarnings("unchecked")
            List<Float> vals = (List<Float>) first;
            return vals;
        }
        try {
            @SuppressWarnings("unchecked")
            List<Float> vals = (List<Float>) first.getClass().getMethod("getValues").invoke(first);
            return vals;
        } catch (Exception e) {
            throw new IllegalStateException("Unsupported embedding element type: " + first.getClass(), e);
        }
    }

    private boolean insertEmbeddingVector(Connection conn, long chunkId, List<Float> embedding) {
        // Build JSON array string of floats: [0.1, -0.2, ...]
        String json = buildJsonArray(embedding);

        // Try TO_VECTOR(?)
        String sql1 = "INSERT INTO kb_embeddings (chunk_id, embedding) VALUES (?, to_vector(?))";
        try (PreparedStatement ps = conn.prepareStatement(sql1)) {
            ps.setLong(1, chunkId);
            ps.setString(2, json);
            ps.executeUpdate();
            return true;
        } catch (SQLException e1) {
            log.debug("to_vector insert failed ({}). Trying VECTOR(?) syntax: {}", e1.getClass().getSimpleName(), e1.getMessage());
            // Try VECTOR(?)
            String sql2 = "INSERT INTO kb_embeddings (chunk_id, embedding) VALUES (?, VECTOR(?))";
            try (PreparedStatement ps = conn.prepareStatement(sql2)) {
                ps.setLong(1, chunkId);
                ps.setString(2, json);
                ps.executeUpdate();
                return true;
            } catch (SQLException e2) {
                log.warn("VECTOR insert failed; falling back to NULL embedding for chunk {}: {}", chunkId, e2.getMessage());
                return false;
            }
        }
    }

    private void insertEmbeddingNull(Connection conn, long chunkId) throws SQLException {
        String sql = "INSERT INTO kb_embeddings (chunk_id, embedding) VALUES (?, NULL)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, chunkId);
            ps.executeUpdate();
        }
    }

    private String buildJsonArray(List<Float> values) {
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append(',');
            Float v = values.get(i);
            if (v == null || v.isNaN() || v.isInfinite()) {
                sb.append('0');
            } else {
                // Ensure decimal representation
                sb.append(v.toString());
            }
        }
        sb.append(']');
        return sb.toString();
    }
}
