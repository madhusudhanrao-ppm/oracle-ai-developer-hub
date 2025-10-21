# OCI Generative AI JET UI – Conversational App with RAG and Oracle ADB

[![License: UPL](https://img.shields.io/badge/license-UPL-green)](https://img.shields.io/badge/license-UPL-green) [![Quality gate](https://sonarcloud.io/api/project_badges/quality_gate?project=oracle-devrel_oci-generative-ai-jet-ui)](https://sonarcloud.io/dashboard?id=oracle-devrel_oci-generative-ai-jet-ui)

## What's New

See CHANGES.md for the latest updates to RAG ingestion, diagnostics, UI improvements, and logging behavior.

## Overview

A production-ready, end-to-end template for building Generative AI applications on OCI:
- Oracle JET web UI for chat, RAG Q&A, and settings
- Spring Boot backend with vendor-aware model calls (Cohere, Meta, xAI Grok)
- Retrieval-Augmented Generation (RAG) over your documents
- Oracle Autonomous Database (ADB) for durable chat history, memory, KB, and telemetry (Liquibase-managed)

Related guides:
- Enhance engagement using OCI Generative AI: [JET.md](JET.md)
- Cloud-native deployment patterns (OKE, Terraform, Kustomize): [K8S.md](K8S.md)

## Architecture

```mermaid
flowchart LR
  subgraph Web UI (Oracle JET)
    A[Chat UI / Settings / Upload]
  end

  subgraph Spring Boot Backend
    B1[Controllers
      - GenAIController (/api/genai/*)
      - PDFConvertorController (/api/upload)
      - SummaryController
      - ModelsController]
    B2[Services
      - OCIGenAIService (chat/summarize)
      - RagService (RAG pipeline)
      - GenAIModelsService (list models)
      - GenAiClientService (GenerativeAiClient)
      - GenAiInferenceClientService (GenerativeAiInferenceClient)]
    B3[Liquibase at startup
      - V1: Conversations/Messages/Memory/Telemetry
      - V2: KB (RAG) tables]
  end

  subgraph Oracle ADB
    D1[(Schema via Liquibase)]
    D2[(UCP Pool via JDBC/Wallet)]
  end

  subgraph OCI Services
    C1[Generative AI Inference
      - Cohere, Meta, xAI Grok]
  end

  A <-- WebSocket/REST --> B1
  B1 --> B2
  B2 -->|Chat/Summary| C1
  B2 -->|Store/Read RAG, telemetry| D1
  B1 -->|File Upload (PDF)| B2
  B2 --> C1
  B2 <---> D2
```

Key backend enhancements:
- Vendor-aware parameters: avoids sending unsupported params (e.g., no presencePenalty for xAI Grok) to prevent 400 errors
- Liquibase schema:
  - V1: conversations, messages, memory_kv, memory_long, interactions (telemetry)
  - V2: knowledge base (KB) tables for RAG
- Transparent OCI auth modes: local config (~/.oci/config), OKE Workload Identity, Instance Principals

## Features

- Chat and Summarization with multiple vendors/models
- RAG over your PDF documents (upload → index → ask)
- Telemetry and audit trails for model calls (latency, tokens, costs)
- Long-term memory and key/value memory per conversation
- ADB + Liquibase for reliable, evolvable data layer

## Quickstart (Local)

Prereqs:
- JDK 17+
- Node.js 18+
- OCI credentials (local ~/.oci/config) with access to Generative AI
- Oracle ADB Wallet (download from your ADB)

1) Configure backend DB and OCI in `backend/src/main/resources/application.yaml`:
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

2) Run backend:
```bash
cd backend
./gradlew clean build
./gradlew bootRun
# Server on http://localhost:8080
```

3) Run web UI:
```bash
cd app
npm ci
npm run serve
# UI on http://localhost:8000 (or as printed)
```

## RAG: Upload and Ask

- Upload a PDF
```bash
curl -F "file=@/path/to/file.pdf" http://localhost:8080/api/upload
```

- Ask a question over your KB (RAG)
```bash
curl -X POST http://localhost:8080/api/genai/rag \
  -H "Content-Type: application/json" \
  -d '{"question": "What does section 2 cover?", "modelId": "ocid1.generativeaimodel.oc1...."}'
```

UI flow:
- Open the app, upload PDFs in the Upload panel, then use Chat with your selected model to RAG over indexed content.

See detailed guide: [RAG.md](RAG.md)

## Database and Liquibase

- On backend startup, Liquibase applies schema migrations:
  - V1 (core): conversations, messages, memory_kv, memory_long, interactions
  - V2 (kb): KB tables that enable RAG
- Benefits:
  - Durable conversation and memory
  - Telemetry and audit for observability and cost
  - Structured KB for accurate retrieval

Details and tips (including PL/SQL + DDL delimiter notes): [DATABASE.md](DATABASE.md)

## Models and Parameters

- Supported vendors: Cohere, Meta, xAI Grok (via OCI Generative AI Inference)
- The backend adapts parameters per vendor to avoid invalid-argument errors (e.g., xAI Grok does not receive presencePenalty)
- Discover available models:
```bash
curl http://localhost:8080/api/genai/models
```
More info: [MODELS.md](MODELS.md)

## Deploy to OKE (ADB with Wallet)

- Build and push images, create K8S resources (Terraform + Kustomize)
- Create a secret from your ADB Wallet, mount in the backend pod, set `TNS_ADMIN` to the mount path
- Use the `_high` service in JDBC URL with that `TNS_ADMIN` path

Full steps: [K8S.md](K8S.md)

## Local, Troubleshooting, and Extras

- Local recipes and environment setup: [LOCAL.md](LOCAL.md)
- Troubleshooting common issues (param validation, Liquibase delimiter, UnknownHost, wallet paths): [TROUBLESHOOTING.md](TROUBLESHOOTING.md)
- FAQ: [FAQ.md](FAQ.md)

## Contributing

This project is open source. Submit contributions by forking this repository and opening a pull request.

## License

Copyright (c) 2024 Oracle and/or its affiliates.

Licensed under the Universal Permissive License (UPL), Version 1.0. See [LICENSE](LICENSE).

ORACLE AND ITS AFFILIATES DO NOT PROVIDE ANY WARRANTY WHATSOEVER, EXPRESS OR IMPLIED, FOR ANY SOFTWARE, MATERIAL OR CONTENT OF ANY KIND CONTAINED OR PRODUCED WITHIN THIS REPOSITORY, AND IN PARTICULAR SPECIFICALLY DISCLAIM ANY AND ALL IMPLIED WARRANTIES OF TITLE, NON-INFRINGEMENT, MERCHANTABILITY, AND FITNESS FOR A PARTICULAR PURPOSE.  FURTHERMORE, ORACLE AND ITS AFFILIATES DO NOT REPRESENT THAT ANY CUSTOMARY SECURITY REVIEW HAS BEEN PERFORMED WITH RESPECT TO ANY SOFTWARE, MATERIAL OR CONTENT CONTAINED OR PRODUCED WITHIN THIS REPOSITORY. IN ADDITION, AND WITHOUT LIMITING THE FOREGOING, THIRD PARTIES MAY HAVE POSTED SOFTWARE, MATERIAL OR CONTENT TO THIS REPOSITORY WITHOUT ANY REVIEW. USE AT YOUR OWN RISK.
