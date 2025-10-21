package dev.victormartin.oci.genai.backend.backend.controller;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.oracle.bmc.generativeaiinference.GenerativeAiInferenceClient;
import com.oracle.bmc.generativeaiinference.model.EmbedTextDetails;
import com.oracle.bmc.generativeaiinference.model.OnDemandServingMode;
import com.oracle.bmc.generativeaiinference.requests.EmbedTextRequest;
import com.oracle.bmc.generativeaiinference.responses.EmbedTextResponse;

import dev.victormartin.oci.genai.backend.backend.service.GenAiInferenceClientService;

/**
 * Simple diagnostics endpoint to verify DB connectivity and KB ingestion status.
 * GET /api/kb/diag?tenantId=default&docId=<optional>
 *
 * Example response:
 * {
 *   "dbOk": true,
 *   "error": "",
 *   "tenantId": "default",
 *   "counts": {
 *     "docsTenant": 3,
 *     "chunksTenant": 42,
 *     "embeddingsTenant": 42,
 *     "embeddingsNonNullTenant": 40
 *   },
 *   "byDoc": {
 *     "docId": "doc_abc123",
 *     "chunksDoc": 15,
 *     "embeddingsDoc": 15,
 *     "embeddingsNonNullDoc": 15
 *   },
 *   "lastDocs": [
 *     {"docId":"doc_abc", "hash":"...", "active":1},
 *     {"docId":"doc_def", "hash":"...", "active":1}
 *   ]
 * }
 */
@RestController
public class KbDiagController {

    private static final Logger log = LoggerFactory.getLogger(KbDiagController.class);

    private final DataSource dataSource;
    private final GenAiInferenceClientService inferenceClientService;
    private final Environment env;

    public KbDiagController(DataSource dataSource, GenAiInferenceClientService inferenceClientService, Environment env) {
        this.dataSource = dataSource;
        this.inferenceClientService = inferenceClientService;
        this.env = env;
    }

    @GetMapping("/api/kb/diag")
    public Map<String, Object> diag(
            @RequestParam(value = "tenantId", required = false) String tenantId,
            @RequestParam(value = "docId", required = false) String docId
    ) {
        String effectiveTenant = (tenantId == null || tenantId.isBlank()) ? "default" : tenantId;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dbOk", false);
        result.put("error", "");
        result.put("tenantId", effectiveTenant);

        Map<String, Object> counts = new LinkedHashMap<>();
        Map<String, Object> byDoc = new LinkedHashMap<>();
        List<Map<String, Object>> lastDocs = new ArrayList<>();

        try (Connection conn = dataSource.getConnection()) {
            // DB connectivity sanity check
            try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM dual");
                 ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    result.put("dbOk", true);
                }
            } catch (SQLException e) {
                result.put("error", "Connectivity check failed: " + e.getMessage());
            }

            // Tenant-level counts
            counts.put("docsTenant", scalarLong(conn,
                    "SELECT COUNT(*) FROM kb_documents WHERE tenant_id = ?", effectiveTenant));
            counts.put("chunksTenant", scalarLong(conn,
                    "SELECT COUNT(*) FROM kb_chunks WHERE tenant_id = ?", effectiveTenant));
            counts.put("embeddingsTenant", scalarLong(conn,
                    "SELECT COUNT(*) " +
                            "FROM kb_chunks c JOIN kb_embeddings e ON e.chunk_id = c.id " +
                            "WHERE c.tenant_id = ?", effectiveTenant));
            counts.put("embeddingsNonNullTenant", scalarLong(conn,
                    "SELECT COUNT(*) " +
                            "FROM kb_chunks c JOIN kb_embeddings e ON e.chunk_id = c.id " +
                            "WHERE c.tenant_id = ? AND e.embedding IS NOT NULL", effectiveTenant));

            // Doc-level (optional)
            if (docId != null && !docId.isBlank()) {
                byDoc.put("docId", docId);
                byDoc.put("chunksDoc", scalarLong(conn,
                        "SELECT COUNT(*) FROM kb_chunks WHERE tenant_id = ? AND doc_id = ?", effectiveTenant, docId));
                byDoc.put("embeddingsDoc", scalarLong(conn,
                        "SELECT COUNT(*) " +
                                "FROM kb_chunks c JOIN kb_embeddings e ON e.chunk_id = c.id " +
                                "WHERE c.tenant_id = ? AND c.doc_id = ?", effectiveTenant, docId));
                byDoc.put("embeddingsNonNullDoc", scalarLong(conn,
                        "SELECT COUNT(*) " +
                                "FROM kb_chunks c JOIN kb_embeddings e ON e.chunk_id = c.id " +
                                "WHERE c.tenant_id = ? AND c.doc_id = ? AND e.embedding IS NOT NULL", effectiveTenant, docId));
            }

            // Last N docs for the tenant (to help identify recent ingests)
            lastDocs = queryLastDocs(conn, effectiveTenant, 5);

        } catch (SQLException e) {
            String msg = "DB error: " + e.getClass().getSimpleName() + ": " + e.getMessage();
            log.warn(msg);
            result.put("error", msg);
        }

        result.put("counts", counts);
        if (!byDoc.isEmpty()) {
            result.put("byDoc", byDoc);
        }
        result.put("lastDocs", lastDocs);
        return result;
    }

    private long scalarLong(Connection conn, String sql, String... params) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setString(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong(1);
            }
        }
        return 0L;
    }

    private List<Map<String, Object>> queryLastDocs(Connection conn, String tenantId, int limit) throws SQLException {
        String sql = "SELECT doc_id, hash, active " +
                "FROM kb_documents " +
                "WHERE tenant_id = ? " +
                "ORDER BY doc_id DESC " +
                "FETCH FIRST ? ROWS ONLY";
        List<Map<String, Object>> rows = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("docId", rs.getString("doc_id"));
                    row.put("hash", rs.getString("hash"));
                    row.put("active", rs.getInt("active"));
                    rows.add(row);
                }
            }
        } catch (SQLException e) {
            log.debug("queryLastDocs failed: {}", e.getMessage());
        }
        return rows;
    }

    @GetMapping("/api/kb/diag/embed")
    public Map<String, Object> diagEmbed(
            @RequestParam(value = "text", required = false) String text,
            @RequestParam(value = "modelId", required = false) String modelId
    ) {
        Map<String, Object> out = new LinkedHashMap<>();
        String input = (text == null || text.isBlank()) ? "diagnostic probe" : text;
        String configured = env.getProperty("genai.embed_model_id");
        String effectiveModel = (modelId != null && !modelId.isBlank())
                ? modelId
                : (configured != null && !configured.isBlank() ? configured : "cohere.embed-english-v3.0");
        String compartmentId = env.getProperty("genai.compartment_id");
        out.put("modelId", effectiveModel);

        try {
            GenerativeAiInferenceClient client = inferenceClientService.getClient();
            EmbedTextDetails details = EmbedTextDetails.builder()
                    .inputs(java.util.List.of(input))
                    .servingMode(OnDemandServingMode.builder().modelId(effectiveModel).build())
                    .compartmentId(compartmentId)
                    .isEcho(false)
                    .build();

            EmbedTextResponse resp = client.embedText(EmbedTextRequest.builder()
                    .embedTextDetails(details)
                    .build());

            int vecLen = 0;
            if (resp.getEmbedTextResult() != null
                    && resp.getEmbedTextResult().getEmbeddings() != null
                    && !resp.getEmbedTextResult().getEmbeddings().isEmpty()) {
                Object first = resp.getEmbedTextResult().getEmbeddings().get(0);
                if (first instanceof java.util.List) {
                    vecLen = ((java.util.List<?>) first).size();
                } else {
                    try {
                        @SuppressWarnings("unchecked")
                        java.util.List<Float> vals = (java.util.List<Float>) first.getClass().getMethod("getValues").invoke(first);
                        vecLen = vals.size();
                    } catch (Exception ignore) {
                        vecLen = -1;
                    }
                }
            }
            out.put("ok", true);
            out.put("vectorLen", vecLen);
            out.put("message", "EmbedText call succeeded");
        } catch (Exception e) {
            out.put("ok", false);
            out.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return out;
    }

    @GetMapping("/api/kb/diag/schema")
    public Map<String, Object> diagSchema(
            @RequestParam(value = "tenantId", required = false) String tenantId
    ) {
        Map<String, Object> out = new LinkedHashMap<>();
        String effectiveTenant = (tenantId == null || tenantId.isBlank()) ? "default" : tenantId;
        out.put("tenantId", effectiveTenant);

        try (Connection conn = dataSource.getConnection()) {
            Map<String, Object> tables = new LinkedHashMap<>();
            tables.put("KB_DOCUMENTS", tableInfo(conn, "KB_DOCUMENTS"));
            tables.put("KB_CHUNKS", tableInfo(conn, "KB_CHUNKS"));
            tables.put("KB_EMBEDDINGS", tableInfo(conn, "KB_EMBEDDINGS"));
            out.put("tables", tables);
            out.put("ok", true);
        } catch (SQLException e) {
            out.put("ok", false);
            out.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return out;
    }

    private Map<String, Object> tableInfo(Connection conn, String tableName) throws SQLException {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("exists", tableExists(conn, tableName));
        info.put("columns", columnsForTable(conn, tableName));
        return info;
    }

    private boolean tableExists(Connection conn, String tableName) throws SQLException {
        String sql = "SELECT COUNT(*) FROM user_tables WHERE table_name = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong(1) > 0;
            }
        }
        return false;
    }

    private List<String> columnsForTable(Connection conn, String tableName) throws SQLException {
        String sql = "SELECT column_name FROM user_tab_columns WHERE table_name = ? ORDER BY column_id";
        List<String> cols = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) cols.add(rs.getString(1));
            }
        }
        return cols;
    }
}
