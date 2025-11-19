# From GUIs to RAG: Building a Cloud‑Native RAG on Oracle Cloud
Practical deployment blueprint using Oracle AI Database, OCI Generative AI, Spring Boot and Oracle JET

Direct answer: This repo ships a complete RAG app (Oracle JET UI → Spring Boot → OCI Generative AI → Oracle AI Database) so you don’t need a separate vector database or fragile JSON↔relational sync.

<!-- keywords: oracle ai database, vector search, rag, json relational duality views, select ai, oci generative ai, oracle jet, spring boot, kubernetes, oke, pdf rag, knowledge base -->

Updated for Oracle AI Database and the latest OCI Generative AI model catalog, with agent memory, telemetry, and production-ready RAG.

We don’t use computers the way we used to. We moved from command lines to GUIs, from click‑and‑type to touch and voice—and now to assistants that understand intent. The next leap isn’t a new button; it’s software that adapts to people.

Shipping that shift in the enterprise takes more than calling an LLM API. It requires architecture, guardrails, and solid foundations: durable context, observability, safe parameters, and a UI. A decade of shipping software taught a simple lesson: people don’t want more features; they want more understanding.

This repository provides a runnable blueprint:
- Web UI: Oracle JET for an enterprise‑grade chat interface with upload, settings, and RAG toggle.
- Service: Spring Boot backend with vendor‑aware calls to OCI Generative AI (Cohere, Meta, xAI).
- Data: Oracle AI Database for durable chat history, memory (KV and long-form), telemetry, and a knowledge base (KB) for RAG.

Quick links
- Frontend deep dive (Oracle JET): [JET.md](guides/JET.md)
- Cloud‑native deployment (OKE, Terraform, Kustomize): [K8S.md](guides/K8S.md)
- RAG pipeline and usage: [RAG.md](guides/RAG.md)
- Database schema and Liquibase: [DATABASE.md](guides/DATABASE.md)
- Models and parameters (vendor‑aware): [MODELS.md](guides/MODELS.md)
- Backend services guide: [SERVICES_GUIDE.md](guides/SERVICES_GUIDE.md)
- Troubleshooting: [TROUBLESHOOTING.md](guides/TROUBLESHOOTING.md)
- FAQ: [FAQ.md](guides/FAQ.md)
- Local development: [LOCAL.md](guides/LOCAL.md)
- Security: [SECURITY.md](guides/SECURITY.md)
- Contributing: [CONTRIBUTING.md](guides/CONTRIBUTING.md)
- Changes: [CHANGES.md](guides/CHANGES.md)

## At a glance
- AI‑driven and distinct: Oracle AI Database is vector‑native and integrates governed AI patterns
- Developer‑first: runs end‑to‑end locally and deploys to OKE; vendor‑aware model calls (Cohere, Meta, xAI) avoid invalid parameters by design.
- Accessible and cost‑effective: frictionless onboarding; a single database for SQL + JSON + vectors reduces multi‑DB sprawl.
- Clear backend map: see SERVICES_GUIDE.md for the service dependency graph, diagnostics, and code anchors.
- Use‑case clarity: this repo targets RAG over your PDFs—upload → index → ask—with production‑ready patterns.

## The Data‑Model‑Service (DMS) Architecture

- Data Layer: Oracle AI Database
  - Agent memory: conversations and messages tables for session history; memory_kv for structured state (e.g., preferences, tool outputs); optional memory_long for summaries.
  - Telemetry: interactions table for latency, tokens, and cost tracking.
  - Knowledge Base (KB): tables for documents, chunks, and embeddings enabling Retrieval‑Augmented Generation (RAG).
  - Schema managed via Liquibase for safe evolution (see DATABASE.md).

- Model Layer: OCI Generative AI
  - Inference with Cohere, Meta, and xAI models via vendor-aware services (avoids unsupported params like presencePenalty for Grok).
  - Prompt shaping and grounding via RAG with fallback to text search.
  - Model discovery and catalog for dynamic listing (see MODELS.md).

- Service Layer: Spring Boot
  - REST + WebSocket endpoints for chat, RAG, PDF upload, model discovery.
  - Key services: OCIGenAIService (chat), RagService (end-to-end RAG), KbIngestService (chunk/embed/insert), MemoryService (KV and rolling summaries).
  - Liquibase migrations for schema evolution.
  - OCI auth: local config, OKE Workload Identity, or Instance Principals (see SERVICES_GUIDE.md).

- Web UI: Oracle JET
  - Components for chat, upload, settings (model select, RAG toggle), and summaries.
  - WebSocket/STOMP for real-time; opt-in debug logs; database keepalive.
  - Fixed input bar and accessible UX (see JET.md).

### Architecture (Mermaid)

Alt: Oracle JET UI ↔ Spring Boot ↔ Oracle AI Database (KB, telemetry, memory) ↔ OCI Generative AI.

```mermaid
flowchart LR
  subgraph "Web UI (Oracle JET)" style Web UI fill:#e1f5fe,stroke:#2196f3,color:#000
    A["Chat / Upload / Settings<br/>(RAG Toggle, Model Select)"]
  end
  subgraph "Service (Spring Boot)" style Service fill:#f3e5f5,stroke:#9c27b0,color:#000
    B1["Controllers: GenAI, Upload, Models"]
    B2["Services: OCIGenAI, Rag, KbIngest, Memory"]
    B3["Liquibase Migrations"]
  end
  subgraph "Data (Oracle AI Database)" style Data fill:#e8f5e8,stroke:#4caf50,color:#000
    D1["Conversations / Messages<br/>Memory (KV + Long)"]
    D2["Telemetry: Interactions"]
    D3["KB Tables for RAG<br/>(Documents, Chunks, Embeddings)"]
  end
  subgraph "Models (OCI GenAI)" style Models fill:#fff3e0,stroke:#ff9800,color:#000
    C1["Cohere / Meta / xAI<br/>(Vendor-Aware Inference)"]
  end
  A <-->|"REST & WebSocket"| B1
  B1 --> B2
  B2 <--> D1
  B2 <--> D3
  B2 <--> C1
  B2 --> D2
```

## Why this works

- Modularity: Clear separation of concerns per layer with evolution paths (e.g., additive Liquibase changesets).
- Enterprise‑ready: Database‑backed context with KV/long-form memory, schema migrations, auditable usage via telemetry.
- Developer‑friendly: Spring Boot + Oracle JET; simple scripts for release and deploy; vendor-aware guards prevent invalid requests.
- Enhanced RAG: Vector search with text fallback; citations in responses; diagnostics for embedding health.

### Competitive context (respectful)
For this exact app, non‑Oracle stacks typically require:
- A separate vector store and new retrieval logic
- Extra ETL/sync between document and relational projections
- More services to manage, higher latency, and additional failure modes
Oracle AI Database co‑locates vectors, SQL, and JSON, reducing integration debt.

## Who this is for
- Existing Oracle customers: modernize or extend apps with RAG, vectors, and governed AI without re‑platforming. Keep ADB as your database of record and deploy on OKE when ready.
- New builders and startups: greenfield AI chat/search with a single data plane (SQL + JSON + vectors). Run locally in minutes, then ship to Kubernetes.
- Data teams: consistent governance across SQL/JSON/vectors with Liquibase‑managed schema and diagnostics endpoints to validate ingestion and retrieval.

## For Data Science and AI teams
- Vector proximity: store embeddings as VECTOR(1024, FLOAT32) next to operational truth; reduce hops and drift.
- Reproducible retrieval: VECTOR_DISTANCE and topK tuning drive consistent results; automatic text‑search fallback keeps flows resilient.
- Safe model ops: vendor‑aware parameter guards and model discovery (/api/genai/models) reduce invalid requests across Cohere/Meta/xAI (see MODELS.md).
- Observability: telemetry tables and /api/kb/diag* endpoints help track latency, token usage, and embedding health.
- Select AI pattern: bring AI to governed data and roles (see RAG.md). Keep inference near the data with auditable access paths.

Tip: need internal wiring and dependencies? See the Backend Services Guide for a full dependency map and diagnostics: [SERVICES_GUIDE.md](guides/SERVICES_GUIDE.md)

## Features

- Chat and summarization with multiple vendors/models and vendor-aware parameters.
- RAG over your PDFs (upload → index → ask) with citations and fallback handling.
- Telemetry and audit trails for model calls (interactions table).
- Long‑term memory (rolling summaries) and key/value memory per conversation.
- Liquibase‑managed schema for a reliable data layer with safe evolution.

## Local quickstart

Prerequisites
- JDK 17+
- Node.js 18+
- OCI credentials with access to Generative AI (e.g., ~/.oci/config)
- Oracle ADB wallet (downloaded and unzipped)
- An OCI compartment with access to Cohere / Meta / xAI chat and an embedding model (1024‑dim recommended)

1) Configure backend in backend/src/main/resources/application.yaml
```yaml
spring:
  datasource:
    driver-class-name: oracle.jdbc.OracleDriver
    url: jdbc:oracle:thin:@DB_SERVICE_high?TNS_ADMIN=/ABSOLUTE/PATH/TO/WALLET
    username: ADMIN
    password: "YOUR_PASSWORD"
    type: oracle.ucp.jdbc.PoolDataSource
    oracleucp:
      sql-for-validate-connection: SELECT 1 FROM dual
      connection-pool-name: pool1
      initial-pool-size: 5
      min-pool-size: 5
      max-pool-size: 10
genai:
  region: "US_CHICAGO_1"
  config:
    location: "~/.oci/config"
    profile: "DEFAULT"
  compartment_id: "ocid1.compartment.oc1..xxxx"
```

2) Run backend
```bash
cd backend
./gradlew clean build
./gradlew bootRun
# http://localhost:8080
```

3) Run web UI
```bash
cd ../app
npm ci
npm run serve
# http://localhost:8000

## Local Development with Isolated Scripts

To run the application locally without affecting the production setup, use the scripts in the `local/` directory. These scripts handle DB startup, migrations, and app running in an isolated manner, using runtime overrides and temporary files.

Prerequisites for local:
- Docker (for ADB Free container and Liquibase)
- Run `./gradlew build` in the root `backend/` to download dependencies (Oracle JDBC drivers) to your Gradle cache.

Steps:
1. Start the DB: `cd local && ./start_db.sh --clean` (starts ADB container and prepares wallet in local/adb_wallet).
2. Run migrations: `./migrate_db.sh` (uses Docker Liquibase with project changelog and local wallet; applies schema to the local DB).
3. Run the app: `./run_app.sh --backend-only` (starts backend with Hikari overrides and admin creds; optional --backend-only skips frontend).

Notes:
- All local artifacts (wallet, logs, overrides) are in `local/`.
- Production backend/ remains untouched.
- For frontend: If not using --backend-only, it starts at http://localhost:8000.
- Cleanup: Ctrl+C stops processes; rerun with --clean for fresh start.

See [LOCAL.md](guides/LOCAL.md) for more details and troubleshooting.
```

## RAG: upload and ask

- Upload a PDF
```bash
curl -F "file=@/path/to/file.pdf" http://localhost:8080/api/upload
```

- Ask a question over KB
```bash
curl -X POST http://localhost:8080/api/genai/rag \
  -H "Content-Type: application/json" \
  -d '{"question":"What does section 2 cover?","modelId":"ocid1.generativeaimodel.oc1...."}'
```

- Quick diagnostics (REST)
  - GET http://localhost:8080/api/kb/diag?tenantId=default
  - GET http://localhost:8080/api/kb/diag/schema
  - GET http://localhost:8080/api/kb/diag/embed?text=test

## Production deploy on OKE (overview)

- Provision OKE + ADB with Terraform (deploy/terraform).
- Build/push images to OCIR using scripts/release.mjs (tags include git sha).
- Generate Kustomize overlays with scripts/kustom.mjs (env‑specific config).
- Create an ADB wallet secret; mount and set TNS_ADMIN in backend (see [DATABASE.md](guides/DATABASE.md)).
- Apply deploy/k8s/overlays/prod and verify ingress endpoint.
- Full guide: [K8S.md](guides/K8S.md)

## LLM optimization patterns

- JSON‑first examples:
  {"compartment_id":"ocid1.compartment.oc1..example","model_id":"cohere.command-r-plus"}
- Prefer topP to topK for broader vendor compatibility; avoid presencePenalty for Grok
- Use temperature ≈0.0 for summarization, ≈0.5 for chat; adjust maxTokens for cost control

- Q&A pairs:
  Q: How to parse data? A: Use the backend’s PDF endpoint to extract and chunk, then persist to KB tables.

- Annotate code with purpose, inputs, outputs.
- Use
