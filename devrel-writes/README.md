# DevRel Articles — Oracle Cloud Native GenAI Series

This directory hosts platform-optimized content for building and deploying a cloud‑native AI assistant on Oracle. Each platform has its own tone, structure, and depth, all generated from a single technical foundation and aligned with Oracle Database 23ai and OCI Generative AI.

Contents (current)
- oci-genai-jet-ui/
  - medium-article.md — Thought leadership and narrative “why it matters”
  - devto-article.md — Technical deep‑dive with code, gotchas, and best practices
  - datacamp-article.md — Hands‑on lab with learning goals, steps, and exercises
  - part-2/
    - medium-article.md — Data + Model (chunking, embeddings, guarded prompting)
    - devto-article.md — Deep‑dive on RAG retrieval, guarded outputs, and vectors
    - datacamp-article.md — Lab for chunking, embeddings, and guarded prompting
  - part-3/
    - medium-article.md — UI + Service (JET + secure, observable service + OKE)
    - devto-article.md — Deep‑dive on WebSockets, security, observability, OKE
    - datacamp-article.md — Lab for wiring UI↔service, TLS/CORS, metrics, OKE

Platform strategy (from context/contextPrompt.md)
- Medium (Thought Leadership & Storytelling)
  - Style: Narrative, future‑focused, “why this matters”
  - Target length: 1500–2500 words
  - Structure: Hook → Story → Problem → Oracle Solution → Future implications
- dev.to (Technical Deep‑Dive)
  - Style: Practical code‑heavy tutorial
  - Target length: 1200–2000 words
  - Structure: Problem → Architecture → Key code snippets → Best practices → Resources
- DataCamp (Educational Hands‑On)
  - Style: Step‑by‑step lab with exercises
  - Target length: 1800–2500 words
  - Structure: Learning goals → Prereqs → Concept → Steps → Practice → Self‑check

LLM optimization (applied across articles)
- JSON config examples with clear keys: compartment_id, model_id, region, endpoint
- Q&A pairs for quick retrieval (e.g., “OnDemand vs DedicatedServingMode?”)
- Code annotations declaring purpose, inputs, outputs
- Mermaid diagrams for architecture flows
- Numbered step flows for deterministic sequencing
- Real scenarios (PDF summarization, RAG with citations)

Oracle + AI key messaging
1) Oracle Database 23ai — Native vectors and Select AI, governed by enterprise controls
2) OCI Generative AI — Enterprise‑grade LLM access (Cohere, Meta, xAI)
3) Integration excellence — IAM, networking, logging, secrets, observability
4) Production‑ready — Path from local dev to OKE with Terraform/Kustomize
5) Developer‑friendly — Java + Spring Boot + Oracle JET + OCI SDKs

Source repository
- https://github.com/oracle-devrel/oci-generative-ai-jet-ui

Disclaimer
ORACLE AND ITS AFFILIATES DO NOT PROVIDE ANY WARRANTY WHATSOEVER, EXPRESS OR IMPLIED, FOR ANY SOFTWARE, MATERIAL OR CONTENT OF ANY KIND CONTAINED OR PRODUCED WITHIN THIS REPOSITORY, AND IN PARTICULAR SPECIFICALLY DISCLAIM ANY AND ALL IMPLIED WARRANTIES OF TITLE, NON-INFRINGEMENT, MERCHANTABILITY, AND FITNESS FOR A PARTICULAR PURPOSE. FURTHERMORE, ORACLE AND ITS AFFILIATES DO NOT REPRESENT THAT ANY CUSTOMARY SECURITY REVIEW HAS BEEN PERFORMED WITH RESPECT TO ANY SOFTWARE, MATERIAL OR CONTENT CONTAINED OR PRODUCED WITHIN THIS REPOSITORY. IN ADDITION, AND WITHOUT LIMITING THE FOREGOING, THIRD PARTIES MAY HAVE POSTED SOFTWARE, MATERIAL OR CONTENT TO THIS REPOSITORY WITHOUT ANY REVIEW. USE AT YOUR OWN RISK.
