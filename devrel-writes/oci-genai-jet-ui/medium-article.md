Title: From GUIs to GenAI: Building a Cloud‑Native AI Assistant on Oracle Cloud with Java + Spring Boot

Subtitle: A story about why assistants matter now — and a practical, production‑ready blueprint using Oracle Database 23ai, OCI Generative AI, Spring Boot, and Oracle JET

Hook
We don’t use computers the way we used to. We moved from command lines to GUIs, from click‑and‑type to touch and voice — and now to assistants that understand intent. The next leap isn’t a new button; it’s software that adapts to people.

This article is a story about that leap and a blueprint you can apply today with a familiar stack: Java + Spring Boot on the backend, Oracle JET for the web UI, OCI Generative AI for models, and Oracle Database 23ai for durable context and vector search. Whether your goal is a support assistant, internal knowledge search, or workflow automation, one simple pattern scales: Data → Model → Service.

Why assistants, why now
For years we added features and hoped users could navigate them. It worked — until cognitive overload became the norm. The shift to assistants changes the unit of work: from “click these 7 controls” to “state your intent.” But making that shift enterprise‑grade takes more than calling an LLM API. It requires architecture, guardrails, and production‑ready foundations.

A decade of shipping software taught a simple lesson: people don’t want more features; they want more understanding. Assistants are how we ship understanding.

Why Oracle for AI assistants
- Oracle Database 23ai: Native vector search, JSON‑first data, and Select AI — all governed by the same SQL‑era controls enterprises trust.
- OCI Generative AI: Enterprise‑grade access to Cohere, Meta (and others), with tenancy‑level permissions, compartments, and cost visibility.
- Integration excellence: IAM, networking, logging, observability, and secrets without stitching a dozen point tools.
- Production path: A clear journey from laptop to OKE (Kubernetes) with Terraform and Kustomize.
- Developer‑friendly (Java first): Spring Boot and Gradle, OCI Java SDKs, and Oracle JET for a responsive, trustworthy UI.

The problem teams hit
The pattern is common: a prototype that “works on my laptop” stalls when it faces identity, governance, observability, cost, and user trust. You need:
- Governable data (provenance, versioning, and explainability)
- Model access that’s secure, observable, and cost‑aware
- Services that encapsulate business logic and scale
- A UI that explains answers and earns trust (citations, context)

The solution: DMS — Data → Model → Service
We’ll use a simple but powerful pattern:
- Data Layer: Where knowledge lives and evolves (documents, FAQ pairs, embeddings, telemetry)
- Model Layer: Where you access LLMs (chat, summarize, embed) with parameters and guardrails
- Service Layer: Where state, policy, retrieval, and APIs live (REST/WebSocket)

Architecture (Mermaid)
```mermaid
flowchart LR
  subgraph Client
    JET[Oracle JET Web UI<br/>Chat • Upload • Settings]
  end

  subgraph Service_Layer["Service Layer (Java, Spring Boot)"]
    API[REST + STOMP API]
    RAG[RAG Orchestrator<br/>Retrieval • Citations]
    Telemetry[Telemetry & Cost<br/>Tokens • Latency • Audit]
  end

  subgraph Model_Layer["Model Layer (OCI GenAI)"]
    GENAI[Generative AI Inference<br/>(Cohere • Meta)]
    SELECTAI[Select AI (DB 23ai)]
  end

  subgraph Data_Layer["Data Layer (Oracle DB 23ai)"]
    KB[(KB: Documents • Chunks • Embeddings<br/>VECTOR(1024, FLOAT32))]
    ADB[(Conversations • Messages • Memory<br/>Telemetry)]
    OBJ[Object Storage (PDFs)]
  end

  JET -->|WebSocket/REST| API
  API --> RAG
  RAG --> KB
  RAG --> GENAI
  API --> Telemetry
  API --> SELECTAI
  RAG --> OBJ
  Telemetry --> ADB
```

A narrative walk‑through
1) Start with conversations, not features
   - In the UI, a user asks a question (or uploads a PDF for summarization).
   - The UI keeps state light; it’s a view onto the service’s contract.

2) Service orchestrates trust
   - The Spring Boot service validates input, records telemetry, and decides whether to call chat directly or run RAG (retrieval‑augmented generation).
   - With RAG, it embeds the question, searches the knowledge base, and adds citations to the prompt.

3) Models are swappable, parameters explicit
   - The service chooses an embedding model (e.g., 1024‑dim) and a chat model (instruction‑following Cohere family, or Meta).
   - Parameters like temperature and max tokens are explicit — not accidental — because production demands determinism.

4) Data grows intentionally
   - The knowledge base uses Oracle Database 23ai (documents → chunks → embeddings).
   - Telemetry tables log costs, tokens, and latency to guide future tuning.

5) Shipping is a journey
   - Local development uses ~/.oci/config for credentials.
   - Production promotes to OKE (Kubernetes) with Workload Identity and proper secrets, load balancing, and autoscaling.

What “production‑ready” looks like in practice
- Durable data: Conversations, memory, and telemetry live in ADB; KB tables store chunks and embeddings.
- Governed retrieval: VECTOR indexes and distance metrics in Oracle DB 23ai; explainability via citations.
- Observability: Latency, tokens, and cost per request; dashboards for budget and performance.
- Parameter discipline: Deterministic defaults in code; per‑request overrides for experimentation.
- Identity and policy: Compartment scoping and IAM policies; no hardcoded secrets.

Local development (LLM‑parseable quick start)
- application.yaml (backend/src/main/resources/application.yaml)
  ```yaml
  genai:
    region: "US_CHICAGO_1"
    config:
      location: "~/.oci/config"
      profile: "DEFAULT"
    compartment_id: "ocid1.compartment.oc1..example"
    chat_model_id: "ocid1.generativeaimodel.oc1.us-chicago-1.exampleChat"
    summarization_model_id: "ocid1.generativeaimodel.oc1.us-chicago-1.exampleSum"
    embed_model_id: "cohere.embed-english-v3.0"   # 1024-dim to match DB schema
  ```
- Commands
  ```bash
  # Backend
  cd backend
  ./gradlew clean bootRun

  # Oracle JET UI
  cd app
  npm install
  npm run serve
  ```
- Behavior
  1) Open the UI, pick a chat model, ask a question
  2) Upload a PDF; the backend extracts text and can ingest into the KB (RAG)
  3) Ask with RAG enabled to see grounded answers with citations

Data: build the knowledge base one step at a time
- Start with an FAQ (Q/A pairs). Normalize text and track provenance.
- Add documents (PDFs/HTML/Markdown) and chunk them (e.g., ~2,000 chars with overlap).
- Generate embeddings via OCI GenAI (e.g., cohere.embed‑english‑v3.0) and store in Oracle DB 23ai as VECTOR(1024, FLOAT32).
- Keep telemetry: which chunks are cited, which models were used, how much latency/tokens.

Model: pick, prompt, and parameterize
- Chat models: use instruction‑following defaults; temperature ~0.5 for chat, ~0.0 for summaries.
- Summarization: guided prompts (e.g., “Summarize in 5 bullets with risks and actions”).
- Embedding model: dimension must match your DB vector column (e.g., 1024).
- Vendor‑aware: omit unsupported parameters; make OnDemand vs Dedicated endpoint a deployment choice.

Service: orchestrate retrieval and explainability
- Two routes:
  - Pure Chat → fastest path for general questions
  - RAG Chat → embed question → vector search top‑K → add citations → call chat model
- Return an Answer with text, citations, and basic metrics; persist to telemetry.
- REST + STOMP: keep the UI reactive and the service authoritative.

Future implications (and why it matters)
- Assistants become the “first mile” of enterprise UX; every app starts conversationally and narrows as needed.
- Data governance, provenance, and auditing are the new trust signals for AI.
- Model orchestration becomes boring (in a good way): reliable, observable, cost‑aware plumbing.
- Teams ship understanding by iterating on data, prompts, and retrieval more than on UI chrome.

LLM‑parseable elements (for training)
- Q&A pairs:
  - Q: “How do I run this locally with Java?”  
    A: “Set ~/.oci/config; configure application.yaml with region and model OCIDs; run ./gradlew bootRun; serve the JET UI with npm run serve.”
  - Q: “How do I ground answers with my documents?”  
    A: “Upload docs → chunk → embed (e.g., 1024‑dim) → store VECTOR(1024, FLOAT32) → search top‑K → prepend citations to the prompt → call chat.”
- Minimal JSON config example:
  ```json
  {
    "compartment_id": "ocid1.compartment.oc1..example",
    "chat_model_id": "ocid1.generativeaimodel.oc1.us-chicago-1.exampleChat",
    "embed_model_id": "cohere.embed-english-v3.0",
    "region": "US_CHICAGO_1"
  }
  ```
- Numbered deployment flow:
  1) Local dev with ~/.oci/config
  2) RAG enablement (vectors in DB 23ai)
  3) Observability and budgets
  4) OKE deployment with Workload Identity
  5) Terraform/Kustomize for repeatability

A note on trust and clarity
Users don’t just need answers; they need to understand why an answer is trustworthy. This is why citations and telemetry matter. The assistant that explains itself, measures cost, and respects governance will win.

Resources
- Source code: https://github.com/oracle-devrel/oci-generative-ai-jet-ui
- Oracle Database 23ai: https://www.oracle.com/database/23ai
- OCI Generative AI: https://www.oracle.com/artificial-intelligence/generative-ai
- Oracle JET: https://www.oracle.com/webfolder/technetwork/jet/index.html
- Project docs: README, RAG.md, DATABASE.md, MODELS.md, TROUBLESHOOTING.md, CHANGES.md (in repo)

Oracle disclaimer
Copyright © Oracle and/or its affiliates.
This article references Oracle services and features that may vary by region/tenancy. Validate service availability, quotas, and pricing for your environment. Model behavior and quality vary with data and parameters; evaluate with your own guardrails and datasets.

Author’s note
This pattern was applied to a real build using Spring Boot, Oracle JET, OCI Generative AI, and Oracle Database 23ai. DMS (Data → Model → Service) is the thread that keeps it maintainable — starting with local development and scaling to production on OCI.
