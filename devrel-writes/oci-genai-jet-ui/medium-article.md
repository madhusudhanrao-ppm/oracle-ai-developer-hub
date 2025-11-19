# From Short‑Term to Strategic: Agent Memory Patterns on Oracle AI Database

Enterprises love impressive chat demos — until the agent forgets what matters. Durable memory is what turns LLM interactions into compounding value. This article outlines a pragmatic path from lightweight client memory to production‑grade, policy‑aware memory powered by Oracle AI Database and OCI Generative AI, grounded in this repository’s code.

## The Story
Most teams start with a working chat prototype. Then issues surface: the agent repeats questions, loses context across sessions, and can’t recall key facts days later. These aren’t just model problems — they’re memory problems. Memory isn’t a single feature; it’s a layered system that evolves as your app matures.

This repository provides a real, runnable baseline: a JET + React UI, a Spring Boot backend, Kubernetes/Terraform deployment, and concrete APIs for agent memory you can extend over time.

## The Problem
- Stateless agents can’t sustain context beyond a single request.
- “Just add the full transcript to the prompt” is brittle and expensive.
- Scaling memory from demo to production needs governance, TTLs, privacy, observability, and upgrade paths.
- Teams need a minimal‑diff route that reuses what they already run: UI, API, and database.

## Oracle Solution
- Oracle AI Database: a foundation for long‑term, governed agent memory (vectors, relational metadata, Select AI). Use the exact branding “Oracle AI Database”.
- OCI Generative AI: managed embedding and generation for enterprise workloads.
- This repository: a path that starts with short‑term client/session memory and evolves to durable memory with Oracle AI Database — without sweeping refactors.

## A Practical Evolution Path

1) Short‑Term Memory (Client)
- Goal: fast continuity within a conversation window.
- Pattern: bounded local state (few turns), summarized context hints, no secrets.
- Where in repo: chat components under `app/src/components/content` manage the interaction loop with a scroll‑to‑bottom UX.

2) Session/Episodic Memory (Backend KV)
- Goal: keep selected state across requests and devices for a limited time.
- Pattern: key‑value entries with TTL per conversation; store tool outputs, preferences, or ephemeral summaries.
- Where in repo:
  - Client helpers: `app/src/libs/memory.ts`
  - Backend API: `/api/memory/kv/{conversationId}/{key}` (see `MemoryController`)

3) Long‑Term Memory (Oracle AI Database)
- Goal: durable factual recall and semantic search over enterprise knowledge.
- Pattern: vector embeddings of document chunks and events, joined with relational metadata and policies; retrieval‑augmented generation; optional Select AI to reason over results.
- Where in repo: RAG docs and back‑end ingestion services; Terraform + K8s to deploy.

## Architecture

```mermaid
flowchart LR
  subgraph Web ["Frontend (Oracle JET + React)"]
    UI[Chat UI]
    LM[Local short-term memory (bounded)]
  end

  subgraph API [Backend]
    Orchestrator[Prompt Orchestrator + Policy]
    KV[(Session KV with TTL)]
    Summ[Summarization Windowing]
  end

  subgraph DB ["Oracle AI Database"]
    Vec[Vector Index (documents/events)]
    Meta[(Relational Metadata & Policies)]
    SelectAI[Select AI]
  end

  subgraph OCI ["OCI Generative AI"]
    Emb[Embeddings API]
    Gen[Generation API]
  end

  UI --> LM
  UI --> Orchestrator
  Orchestrator --> KV
  Orchestrator -->|embed/query| Emb
  Orchestrator -->|semantic retrieve| Vec
  Vec <-->|filters & joins| Meta
  Orchestrator -->|SQL + AI| SelectAI
  Orchestrator -->|final prompt| Gen
  Gen --> UI
```

## Key Code (Short‑Term + Session KV)

Client KV wrappers (session/episodic memory)
```ts
// app/src/libs/memory.ts
// KV API wrappers for conversation state
export interface KvValue { [key: string]: any }

const API_BASE = '/api/memory'; // If a different base/proxy is used, adjust here.

export async function setKv(
  conversationId: string,
  key: string,
  value: KvValue,
  ttlSeconds?: number
): Promise<void> {
  const url = `${API_BASE}/kv/${encodeURIComponent(conversationId)}/${encodeURIComponent(key)}${
    ttlSeconds ? `?ttlSeconds=${ttlSeconds}` : ''
  }`;
  const response = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(value),
  });
  if (!response.ok) {
    throw new Error(`Failed to set KV (${response.status}): ${await safeText(response)}`);
  }
}

export async function getKv(
  conversationId: string,
  key: string
): Promise<KvValue | null> {
  const url = `${API_BASE}/kv/${encodeURIComponent(conversationId)}/${encodeURIComponent(key)}`;
  const response = await fetch(url);
  if (!response.ok) {
    return null; // 404 or other errors treated as missing
  }
  try {
    return await response.json();
  } catch {
    return null;
  }
}

export async function deleteKv(conversationId: string, key: string): Promise<void> {
  const url = `${API_BASE}/kv/${encodeURIComponent(conversationId)}/${encodeURIComponent(key)}`;
  const response = await fetch(url, { method: 'DELETE' });
  if (!response.ok) {
    throw new Error(`Failed to delete KV (${response.status}): ${await safeText(response)}`);
  }
}

async function safeText(resp: Response): Promise<string> {
  try { return await resp.text(); } catch { return ''; }
}
```

Backend KV endpoints
```java
// backend/src/main/java/.../MemoryController.java
@PostMapping(value = "/api/memory/kv/{conversationId}/{key}", consumes = MediaType.APPLICATION_JSON_VALUE)
public ResponseEntity<Void> upsertKv(@PathVariable("conversationId") String conversationId,
                                     @PathVariable("key") String key,
                                     @RequestParam(value = "ttlSeconds", required = false) Long ttlSeconds,
                                     @RequestBody String valueJson) {
  // Ensure conversation exists (idempotent) then store KV with optional TTL
  memoryService.setKv(conversationId, key, valueJson, ttlSeconds);
  return ResponseEntity.noContent().build();
}

@GetMapping(value = "/api/memory/kv/{conversationId}/{key}", produces = MediaType.APPLICATION_JSON_VALUE)
public ResponseEntity<String> getKv(@PathVariable("conversationId") String conversationId,
                                    @PathVariable("key") String key) {
  return memoryService.getKv(conversationId, key)
      .map(v -> ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(v))
      .orElseGet(() -> ResponseEntity.notFound().build());
}

@DeleteMapping("/api/memory/kv/{conversationId}/{key}")
public ResponseEntity<Void> deleteKv(@PathVariable("conversationId") String conversationId,
                                     @PathVariable("key") String key) {
  memoryService.deleteKv(conversationId, key);
  return ResponseEntity.noContent().build();
}
```

Rolling summary (read‑only long memory preview)
```java
// backend/src/main/java/.../MemoryController.java
@GetMapping(value = "/api/memory/long/{conversationId}", produces = MediaType.TEXT_PLAIN_VALUE)
public ResponseEntity<String> getRollingSummary(@PathVariable("conversationId") String conversationId) {
  var ml = memoryLongRepository.findById(conversationId);
  if (ml.isPresent() && ml.get().getSummaryText() != null) {
    return ResponseEntity.ok(ml.get().getSummaryText());
  }
  return ResponseEntity.notFound().build();
}
```

UX pattern (bounded history + KV)
```tsx
// app/src/components/content/chat.tsx (pattern)
// Keep the list UI reactive and scroll to newest item.
// Combine bounded local history with selective KV writes (e.g., user prefs).
// Pseudocode usage:
// const next = addTurn(history, { role: "user", content: text });
// await setKv(conversationId, "prefs", { theme:"light" }, 86400);
```

## Long‑Term Memory with Oracle AI Database

When you need durable recall across sessions/tenants:
- Chunk and embed documents/events (OCI Generative AI).
- Store vectors + metadata in Oracle AI Database.
- Retrieve top‑k semantically similar items with policy filters; compose into prompts.
- Optionally use Select AI for SQL+LLM workflows over the retrieved context.

Illustrative DDL (shape may vary by environment)
```sql
CREATE TABLE doc_chunks (
  id           NUMBER GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
  tenant_id    VARCHAR2(64),
  doc_type     VARCHAR2(32),
  content      CLOB,
  embedding    VECTOR(1024, FLOAT32)  -- dimension depends on your embed model
);

CREATE VECTOR INDEX doc_chunks_embedding_idx
ON doc_chunks (embedding)
ORGANIZATION NEIGHBOR PARTITIONS
WITH DISTANCE METRIC COSINE;
```

Illustrative retrieval
```sql
SELECT id, content
FROM doc_chunks
WHERE tenant_id = :tenant
ORDER BY embedding <-> :query_embedding
FETCH FIRST 8 ROWS ONLY;
```

## Reliability, Security, and Cost Controls
- Bound context: decay or summarize windows; don’t ship unbounded transcripts.
- TTL and cleanup: expire KV where appropriate; implement “Clear memory”.
- PII/privacy: scrub sensitive content; apply tenant filters at retrieval.
- Observability: log memory hits, prompt token sizes, and latency; correlate request IDs.
- Cost: adjust top‑k, chunk sizes, and embedding cadence; re‑embed only when models change materially.

## What This Enables
- Short‑term continuity for a great chat UX.
- Durable recall of organizational knowledge with governance.
- A layered memory system you can evolve without rewrites.

## Looking Ahead
- Policy‑aware memory (who can read/write which memories).
- Time‑decay and pinning semantics for episodic facts.
- Automated summarization for life‑long conversations.
- End‑to‑end evaluation of retrieval quality over time.

---

## Links and References
- Project: [README.md](../../README.md)
- RAG patterns: [RAG.md](../../RAG.md)
- Models & limits: [MODELS.md](../../MODELS.md)
- Services & endpoints: [SERVICES_GUIDE.md](../../SERVICES_GUIDE.md)
- Troubleshooting: [TROUBLESHOOTING.md](../../TROUBLESHOOTING.md)
- Local setup: [LOCAL.md](../../LOCAL.md)
- Authoring workflow & branding guardrails:
  - [.clinerules/workflows/devrel-content.md](../../.clinerules/workflows/devrel-content.md)
  - [.clinerules/branding-oracle-ai-database.md](../../.clinerules/branding-oracle-ai-database.md)
  - [.clinerules/secrets-and-credentials-handling.md](../../.clinerules/secrets-and-credentials-handling.md)

---

Disclaimer: This article references “Oracle AI Database”. Version details, where noted, are for compatibility only.
