package dev.victormartin.oci.genai.backend.backend.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import dev.victormartin.oci.genai.backend.backend.data.MemoryKv;
import dev.victormartin.oci.genai.backend.backend.data.MemoryKvRepository;
import dev.victormartin.oci.genai.backend.backend.data.MemoryLong;
import dev.victormartin.oci.genai.backend.backend.data.MemoryLongRepository;
import dev.victormartin.oci.genai.backend.backend.data.Message;
import dev.victormartin.oci.genai.backend.backend.data.MessageRepository;

/**
 * Builds a token-bounded conversational context using:
 * - long-term rolling summary (memory_long)
 * - selected KV entries (memory_kv allowlist)
 * - recent message window (messages)
 *
 * Uses a simple token estimator (chars -> tokens) to stay below model limits.
 */
@Service
public class MemoryContextBuilder {

  private final MessageRepository messageRepository;
  private final MemoryLongRepository memoryLongRepository;
  private final MemoryKvRepository memoryKvRepository;
  private final Environment env;

  // Defaults; tunable via application properties
  private static final int DEFAULT_TOKEN_BUDGET_TOKENS = 80_000; // conservative default
  private static final double DEFAULT_BUDGET_PERCENT = 0.75;     // 75% of model max (when known)
  private static final int DEFAULT_MAX_MSGS = 20;
  private static final int PER_MESSAGE_CHAR_LIMIT = 1_000;
  private static final int SUMMARY_MAX_CHARS_IN_CONTEXT = 40_000;
  private static final int KV_MAX_TOTAL_CHARS = 6_000;

  public MemoryContextBuilder(
      MessageRepository messageRepository,
      MemoryLongRepository memoryLongRepository,
      MemoryKvRepository memoryKvRepository,
      Environment env) {
    this.messageRepository = messageRepository;
    this.memoryLongRepository = memoryLongRepository;
    this.memoryKvRepository = memoryKvRepository;
    this.env = env;
  }

  public String buildContext(String conversationId, String tenantId, String modelId) {
    int tokenBudget = resolveTokenBudgetTokens(modelId);
    int charBudget = approxTokensToChars(tokenBudget);

    StringBuilder out = new StringBuilder(Math.min(charBudget, 64_000));
    int budgetLeft = charBudget;

    // 1) Long-term summary
    String summary = null;
    try {
      summary = memoryLongRepository.findById(conversationId).map(MemoryLong::getSummaryText).orElse(null);
    } catch (Exception ignore) {}
    out.append("[Memory]\n");
    if (summary != null && !summary.isBlank()) {
      String s = truncate(summary.trim(), Math.min(SUMMARY_MAX_CHARS_IN_CONTEXT, Math.max(0, budgetLeft - 2_000)));
      out.append(s).append("\n\n");
      budgetLeft -= s.length() + 2;
    } else {
      out.append("(none)\n\n");
      budgetLeft -= "(none)\n\n".length();
    }
    if (budgetLeft <= 0) return out.toString();

    // 2) KV allowlist (profile/persona/preferences)
    List<String> allowKeys = resolveKvAllowlist();
    if (!allowKeys.isEmpty()) {
      out.append("[Profile]\n");
      int kvBudget = Math.min(KV_MAX_TOTAL_CHARS, Math.max(0, budgetLeft - 4_000)); // leave room for messages
      int kvUsed = 0;
      for (String key : allowKeys) {
        Optional<MemoryKv> kv = memoryKvRepository.findByConversationIdAndKey(conversationId, key);
        if (kv.isPresent()) {
          String line = key + ": " + condenseJson(kv.get().getValueJson()) + "\n";
          int toTake = Math.min(line.length(), Math.max(0, kvBudget - kvUsed));
          if (toTake <= 0) break;
          out.append(line, 0, toTake);
          kvUsed += toTake;
        }
      }
      out.append("\n");
      budgetLeft -= (kvUsed + 2);
      if (budgetLeft <= 0) return out.toString();
    }

    // 3) Recent messages (ascending)
    out.append("[Recent messages]\n");
    List<Message> recentDesc = messageRepository.findTop20ByConversationIdOrderByCreatedAtDesc(conversationId);
    List<Message> recentAsc = new ArrayList<>();
    if (recentDesc != null && !recentDesc.isEmpty()) {
      for (int i = recentDesc.size() - 1; i >= 0; i--) {
        recentAsc.add(recentDesc.get(i));
      }
    }
    int count = 0;
    for (Message m : recentAsc) {
      if (count >= resolveMaxMessages()) break;
      String role = m.getRole() == null ? "unknown" : m.getRole();
      String content = m.getContent() == null ? "" : m.getContent();
      if (content.length() > PER_MESSAGE_CHAR_LIMIT) {
        content = content.substring(0, PER_MESSAGE_CHAR_LIMIT) + "…";
      }
      String line = "- " + role + ": " + content + "\n";
      if (line.length() > budgetLeft) break;
      out.append(line);
      budgetLeft -= line.length();
      count++;
    }
    out.append("\n");
    return out.toString();
  }

  private int resolveTokenBudgetTokens(String modelId) {
    // Allow overriding via config; fall back to default conservative value
    Integer explicit = env.getProperty("memory.context.tokenBudgetTokens", Integer.class);
    if (explicit != null && explicit > 0) return explicit;

    // If a per-model limit is known in config, use a percentage of it
    Integer modelMax = env.getProperty("models." + safe(modelId) + ".maxTokens", Integer.class);
    Double pct = env.getProperty("memory.context.tokenBudgetPercent", Double.class, DEFAULT_BUDGET_PERCENT);
    if (modelMax != null && modelMax > 0) {
      return (int) Math.floor(modelMax * Math.max(0.1, Math.min(0.95, pct)));
    }
    return DEFAULT_TOKEN_BUDGET_TOKENS;
  }

  private int approxTokensToChars(int tokens) {
    // 1 token ~ 4 chars heuristic
    return tokens * 4;
  }

  private String truncate(String s, int max) {
    if (s == null) return "";
    if (max <= 0) return "";
    if (s.length() <= max) return s;
    return s.substring(0, Math.max(0, max - 1)) + "…";
  }

  private List<String> resolveKvAllowlist() {
    String raw = env.getProperty("memory.kv.allowlist", "userPrefs,persona,constraints");
    return Arrays.stream(raw.split(","))
        .map(String::trim)
        .filter(k -> !k.isBlank())
        .collect(Collectors.toList());
  }

  private String safe(String s) {
    if (s == null) return "unknown";
    return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_");
  }

  private String condenseJson(String json) {
    if (json == null) return "{}";
    // Remove unnecessary whitespace to save budget (very light minify)
    return json.replaceAll("\\s+", " ").trim();
  }

  private int resolveMaxMessages() {
    Integer v = env.getProperty("memory.messages.maxCount", Integer.class);
    return (v != null && v > 0) ? v : DEFAULT_MAX_MSGS;
  }
}
