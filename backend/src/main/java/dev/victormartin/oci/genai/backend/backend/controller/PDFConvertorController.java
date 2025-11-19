package dev.victormartin.oci.genai.backend.backend.controller;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.util.HtmlUtils;

import com.oracle.bmc.model.BmcException;

import dev.victormartin.oci.genai.backend.backend.dao.Answer;
import dev.victormartin.oci.genai.backend.backend.data.Interaction;
import dev.victormartin.oci.genai.backend.backend.data.InteractionRepository;
import dev.victormartin.oci.genai.backend.backend.data.InteractionType;
import dev.victormartin.oci.genai.backend.backend.service.KbIngestService;
import dev.victormartin.oci.genai.backend.backend.service.OCIGenAIService;
import dev.victormartin.oci.genai.backend.backend.service.PDFConvertorService;

@RestController
public class PDFConvertorController {
    Logger log = LoggerFactory.getLogger(PDFConvertorController.class);

    @Value("${storage.path}")
    String storagePath;

    @Value("${genai.summarization_model_id}")
    String summarizationModelId;

    @Autowired
    OCIGenAIService ociGenAIService;

    @Autowired
    PDFConvertorService pdfConvertorService;

    @Autowired
    KbIngestService kbIngestService;

    @Autowired
    private InteractionRepository interactionRepository;

    private String summarizeLargeTextFile(File file, String modelId) throws IOException {
        final int CHUNK_SIZE = 16_000;     // characters per chunk
        final int MAX_CHUNKS = 30;         // max number of chunks
        final int MAX_CHARS = 400_000;     // hard cap on processed characters

        List<String> chunkSummaries = new ArrayList<>();
        int processedChars = 0;
        boolean truncated = false;

        try (BufferedReader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            StringBuilder chunk = new StringBuilder(CHUNK_SIZE + 1024);
            String line;
            while ((line = reader.readLine()) != null) {
                int nextLen = line.length() + 1; // include newline
                // Stop if we reached overall cap
                if (processedChars + nextLen > MAX_CHARS) {
                    truncated = true;
                    // flush what we have in the current chunk if non-empty
                    if (chunk.length() > 0) {
                        String summary = ociGenAIService.summaryText(chunk.toString(), modelId, false);
                        chunkSummaries.add(summary);
                        if (chunkSummaries.size() % 5 == 0) {
                            log.info("Large file summarization progress: {} chunks summarized (reached global cap)", chunkSummaries.size());
                        }
                    }
                    break;
                }

                // Accumulate in chunk; if exceeds per-chunk size, flush
                if (chunk.length() + nextLen > CHUNK_SIZE) {
                    // Summarize current chunk
                    String summary = ociGenAIService.summaryText(chunk.toString(), modelId, false);
                    chunkSummaries.add(summary);
                    if (chunkSummaries.size() % 5 == 0) {
                        log.info("Large file summarization progress: {} chunks summarized", chunkSummaries.size());
                    }
                    chunk.setLength(0);

                    // Respect chunk limit
                    if (chunkSummaries.size() >= MAX_CHUNKS) {
                        truncated = true;
                        break;
                    }
                }

                chunk.append(line).append("\n");
                processedChars += nextLen;

                // If we hit chunk limit exactly, flush and break
                if (chunkSummaries.size() >= MAX_CHUNKS) {
                    truncated = true;
                    break;
                }
            }

            // Flush tail if any and within limits
            if (!truncated && chunk.length() > 0) {
                String summary = ociGenAIService.summaryText(chunk.toString(), modelId, false);
                chunkSummaries.add(summary);
            }
        }

        if (chunkSummaries.isEmpty()) {
            return "No content available to summarize.";
        }
        if (chunkSummaries.size() == 1 && !truncated) {
            return chunkSummaries.get(0);
        }

        String combinedSummaries = String.join("\n\n", chunkSummaries);
        String finalSummary = ociGenAIService.summaryText(combinedSummaries, modelId, false);
        if (truncated) {
            finalSummary = finalSummary + "\n\n[Note] Input truncated for summarization limits (processed ~" + processedChars + " characters).";
        }
        return finalSummary;
    }

    @PostMapping("/api/upload")
    public Answer fileUploading(@RequestParam("file") MultipartFile multipartFile,
                                @RequestHeader("conversationID") String conversationId,
                                @RequestHeader(value = "modelId", required = false) String modelId,
                                @RequestHeader(value = "X-RAG-Ingest", required = false) String ragIngestHeader,
                                @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId,
                                @RequestHeader(value = "Embedding-Model-Id", required = false) String embeddingModelId,
                                @RequestHeader(value = "X-Doc-Id", required = false) String docId) {
        String filename = StringUtils.cleanPath(multipartFile.getOriginalFilename());
        log.info("File uploaded {} {} bytes ({})", filename, multipartFile.getSize(), multipartFile.getContentType());
        String contentType = multipartFile.getContentType();// application/pdf
        try {
            if (filename.contains("..")) {
                throw new Exception("Filename contains invalid path sequence");
            }
            if (multipartFile.getSize() > (100L * 1024 * 1024)) {
                throw new Exception("File size exceeds maximum 100MB limit");
            }
            String fileDestinationPath = StringUtils.cleanPath(storagePath);
            File file = new File(fileDestinationPath + File.separator + filename);
            multipartFile.transferTo(file);
            log.info("File destination path: {}", file.getAbsolutePath());
            String convertedText;
            boolean isLargeTextFile = false;
            switch (contentType) {
                case "text/plain":
                    if (multipartFile.getSize() > 50L * 1024 * 1024) {
                        convertedText = summarizeLargeTextFile(file, summarizationModelId);
                        isLargeTextFile = true;
                    } else {
                        convertedText = FileUtils.readFileToString(file, StandardCharsets.UTF_8);
                    }
                    break;
                case "application/pdf":
                    convertedText = pdfConvertorService.convert(file.getAbsolutePath());
                    break;
                default:
                    convertedText= "";
                    break;
            }
            // Optional: also ingest into KB if requested
            boolean doIngest = ragIngestHeader != null && "true".equalsIgnoreCase(ragIngestHeader);
            if (doIngest && !(isLargeTextFile)) {
                String effectiveTenant = (tenantId == null || tenantId.isBlank()) ? "default" : tenantId;
                String mime = contentType != null ? contentType : "application/octet-stream";
                String tagsJson = "[]";
                try {
                    KbIngestService.IngestSummary ing = kbIngestService.ingestText(
                            effectiveTenant,
                            docId,
                            filename,
                            null,      // uri (optional)
                            mime,
                            tagsJson,
                            convertedText,
                            embeddingModelId
                    );
                    log.info("KB ingest via /api/upload completed: docId={} chunks={} embeddings={}",
                            ing.docId(), ing.chunkCount(), ing.embedCount());
                } catch (Exception ex) {
                    log.warn("KB ingest via /api/upload failed (continuing to summary): {}", ex.getMessage());
                }
            }

            String textEscaped = HtmlUtils.htmlEscape(convertedText);
            Interaction interaction = new Interaction();
            interaction.setType(InteractionType.SUMMARY_FILE);
            interaction.setConversationId(conversationId);
            interaction.setDatetimeRequest(new Date());
            interaction.setModelId(summarizationModelId);
            interaction.setRequest(textEscaped);
            Interaction saved = interactionRepository.save(interaction);
            String summaryText;
            if (isLargeTextFile) {
                // already produced via chunked summarization path
                summaryText = convertedText;
            } else {
                // pass raw text to model to avoid token inflation from HTML escaping
                summaryText = ociGenAIService.summaryText(convertedText, summarizationModelId, false);
            }
            saved.setDatetimeResponse(new Date());
            saved.setResponse(summaryText);
            interactionRepository.save(saved);
            log.info("Summary text: {}(...)", summaryText.substring(0, 40));
            Answer answer = new Answer(summaryText, "");
            return answer;
        } catch (MaxUploadSizeExceededException maxUploadSizeExceededException) {
            log.error(maxUploadSizeExceededException.getMessage());
            throw new RuntimeException(maxUploadSizeExceededException);
        } catch (BmcException exception) {
            log.error("Unmodified Message: {}", exception.getUnmodifiedMessage());
            String unmodifiedMessage = exception.getUnmodifiedMessage();
            int statusCode = exception.getStatusCode();
            String errorMessage = statusCode + " " + unmodifiedMessage;
            log.error(errorMessage);
            Answer answer = new Answer("", errorMessage);
            return answer;
        } catch (Exception e) {
            log.error(e.getMessage());
            Answer answer = new Answer("", e.getMessage());
            return answer;
        }
    }
}
