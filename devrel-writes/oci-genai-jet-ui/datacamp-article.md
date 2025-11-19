# Hands‑On: Build Agent Memory Tiers with Oracle AI Database

## Learning Goals
- Understand agent memory tiers: short‑term (UI), session/episodic (KV with TTL), and long‑term (vector recall).
- Implement and test session memory using the repository’s KV API from the UI.
- Exercise cURL tests for memory endpoints and add a “Clear Memory” control.
- Plan the path to durable, governed memory using Oracle AI Database vectors.

## Prerequisites
- Tools (no secrets in code)
  - Node.js 18+, npm
  - Java 17+, Gradle wrapper (`backend/gradlew`)
  - Optional: kubectl + a local K8s or OKE for deployment practice
- This repository cloned locally
- Optional (advanced): Oracle Autonomous Database (ADB) for vector recall
- Branding compliance: Use “Oracle AI Database” exactly; avoid legacy names

## Concepts (Quick Explain)
- Short‑term memory: Bounded UI turns to maintain conversational flow; not persisted; cost‑aware.
- Session/Episodic memory: Key‑Value (KV) per conversation, with TTL; store tool outputs, preferences, summaries.
- Long‑term memory: Vectorized knowledge in Oracle AI Database with relational metadata and policies; retrieval‑augmented prompts.
- Guardrails: TTLs, privacy filters, observability, and user‑visible controls (e.g., Clear Memory).

## Architecture (At a Glance)
```mermaid
flowchart LR
  UI[Oracle JET + React (TypeScript)] -->|HTTPS| API[Spring Boot REST + WebSocket]
  API -->|/api/memory/kv| KV[(Session KV with TTL)]
  API -->|SQL/Vector/Select AI| DB[(Oracle AI Database)]
  API -->|Embeddings + Generation| LLM[OCI Generative AI]
```

## Hands‑On Steps

1) Explore the Repository Layout
- UI (Oracle JET + React): `app/`
- Backend (Spring Boot): `backend/`
- Memory helpers (client): `app/src/libs/memory.ts`
- Memory API (backend): `backend/src/main/java/.../controller/MemoryController.java`
- Docs to keep handy: [README.md](../../README.md), [RAG.md](../../RAG.md), [MODELS.md](../../MODELS.md), [SERVICES_GUIDE.md](../../SERVICES_GUIDE.md), [TROUBLESHOOTING.md](../../TROUBLESHOOTING.md)

2) Run Backend and UI Locally
```bash
# Terminal 1
cd backend
./gradlew clean build
./gradlew bootRun

# Terminal 2
cd app
npm ci
npx ojet serve --server-port=8000
```
- Backend health: `curl -s http://localhost:8080/actuator/health | jq`
- Open UI: http://localhost:8000

3) Understand the Session KV API (Backend)
`MemoryController` exposes conversation‑scoped KV with optional TTL:
```java
// POST upsert (optional ?ttlSeconds=...)
@PostMapping("/api/memory/kv/{conversationId}/{key}")

// GET read value
@GetMapping("/api/memory/kv/{conversationId}/{key}")

// DELETE remove value
@DeleteMapping("/api/memory/kv/{conversationId}/{key}")
```
Try basic cURL tests:
```bash
# Upsert episodic memory (1 day TTL)
curl -s -X POST \
  -H 'Content-Type: application/json' \
  'http://localhost:8080/api/memory/kv/demo-conv/prefs?ttlSeconds=86400' \
  -d '{"theme":"light","tz":"UTC"}' -i

# Read it
curl -s 'http://localhost:8080/api/memory/kv/demo-conv/prefs' | jq

# Delete it
curl -s -X DELETE 'http://localhost:8080/api/memory/kv/demo-conv/prefs' -i
```

4) Use the Client KV Helpers in the UI
Open `app/src/libs/memory.ts` — these wrappers call the KV API:
```ts
// app/src/libs/memory.ts (excerpt)
export interface KvValue { [key: string]: any }

const API_BASE = '/api/memory';

export async function setKv(conversationId: string, key: string, value: KvValue, ttlSeconds?: number): Promise<void> { /* ... */ }
export async function getKv(conversationId: string, key: string): Promise<KvValue | null> { /* ... */ }
export async function deleteKv(conversationId: string, key: string): Promise<void> { /* ... */ }
```

In a UI component (example pattern), persist a user preference to episodic memory:
```tsx
// Example: store a user preference with TTL and read it back on mount
import { useEffect, useState } from "preact/hooks";
import { setKv, getKv, deleteKv } from "../../libs/memory";

const conversationId = "demo-conv"; // For real usage, generate/store per session/user

export function MemoryDemo() {
  const [prefs, setPrefs] = useState<any>(null);

  useEffect(() => {
    (async () => {
      const p = await getKv(conversationId, "prefs");
      if (p) setPrefs(p);
    })();
  }, []);

  async function saveLightTheme() {
    await setKv(conversationId, "prefs", { theme: "light", tz: "UTC" }, 86400);
    setPrefs(await getKv(conversationId, "prefs"));
  }

  async function clearMemory() {
    await deleteKv(conversationId, "prefs");
    setPrefs(null);
  }

  return (
    <div>
      <button onClick={saveLightTheme}>Save Light Theme (KV)</button>
      <button onClick={clearMemory}>Clear Memory (KV)</button>
      <pre>{JSON.stringify(prefs, null, 2)}</pre>
    </div>
  );
}
```
Notes
- Keep conversationId stable across page reloads (e.g., `localStorage`).
- Use TTL to avoid stale data. Provide a “Clear memory” button so users can reset.

5) Add Short‑Term Memory (Bounded UI Turns)
Short‑term memory is kept local to the UI. Maintain a bounded window (e.g., last 8–16 turns) to control token cost:
```ts
type ChatTurn = { role: "user" | "assistant"; content: string; ts?: number };
const MAX_TURNS = 16;

function addTurn(history: ChatTurn[], turn: ChatTurn): ChatTurn[] {
  const next = [...history, { ...turn, ts: turn.ts ?? Date.now() }];
  return next.slice(Math.max(0, next.length - MAX_TURNS));
}
```
Use bounded history for rendering and optional summaries. Do not include secrets or PII in client summaries.

6) Read‑Only Long Memory Preview (if present)
The backend exposes a rolling summary endpoint:
```bash
curl -s 'http://localhost:8080/api/memory/long/demo-conv' || echo "No summary yet"
```
Treat this as a read‑only preview. Production‑grade long‑term memory should be backed by Oracle AI Database vectors.

7) Plan Long‑Term Memory with Oracle AI Database (Advanced)
Illustrative schema for semantic recall:
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
Retrieval:
```sql
SELECT id, content
FROM doc_chunks
WHERE tenant_id = :tenant
ORDER BY embedding <-> :query_embedding
FETCH FIRST 8 ROWS ONLY;
```
- Use OCI Generative AI to embed queries/chunks.
- Join with relational metadata (tenant, doc type) for policy filters.
- Optionally use Select AI to reason over retrieved context.

8) Observability, Privacy, and Cost
- Observability: log retrieval hit rate, prompt token counts, latency; correlate with request IDs.
- Privacy: scrub PII from memory writes; avoid logging sensitive content.
- Cost control: keep MAX_TURNS small, apply TTLs, and right‑size top‑k retrieval/chunk sizes.
- UX: give users control (e.g., Clear Memory) and communicate retention clearly.

## Try It Yourself (Exercises)
- Exercise 1 (TTL tuning): Save two KV keys with different TTLs (1h, 24h). Verify expiry behavior by reading after TTL passes.
- Exercise 2 (Pinned facts): Implement a “pinned” KV key with no TTL and a “decayed” key with short TTL; show both in a small UI card.
- Exercise 3 (Memory inspector): List known keys for a conversation (client‑tracked set) and provide delete buttons per key.
- Exercise 4 (Bounded turns): Compare latency and token use with MAX_TURNS=8 vs 32.
- Exercise 5 (Advanced): Sketch a DTO for a backend “episodic summary” write endpoint, then draft a UI call that saves a one‑line summary per N turns.

## Wrap‑Up
You built session memory using the repository’s KV API, implemented a UI control to persist and clear preferences, and explored how to layer short‑term, session, and long‑term memory. With Oracle AI Database, you can evolve to durable, governed memory for enterprise‑grade agents.

---

## References
- Project: [README.md](../../README.md)
- Models: [MODELS.md](../../MODELS.md)
- RAG: [RAG.md](../../RAG.md)
- Services: [SERVICES_GUIDE.md](../../SERVICES_GUIDE.md)
- Troubleshooting & Local: [TROUBLESHOOTING.md](../../TROUBLESHOOTING.md), [LOCAL.md](../../LOCAL.md)
- Authoring & Branding Guardrails:
  - [.clinerules/workflows/devrel-content.md](../../.clinerules/workflows/devrel-content.md)
  - [.clinerules/branding-oracle-ai-database.md](../../.clinerules/branding-oracle-ai-database.md)
  - [.clinerules/secrets-and-credentials-handling.md](../../.clinerules/secrets-and-credentials-handling.md)

---
Branding: Use “Oracle AI Database”. Version details are secondary and should be phrased as “Oracle AI Database (version details for compatibility only)”.
