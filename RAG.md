# RAG (Retrieval-Augmented Generation) Guide

This app supports Retrieval-Augmented Generation over your own PDFs. You upload documents, the backend indexes them into Oracle ADB (via Liquibase KB tables), and questions are answered by augmenting prompts with retrieved content.

## How it works (high level)

- Upload: a PDF is uploaded to the backend.
- Ingest: the backend extracts text and persists it (and related metadata) into ADB KB tables.
- Retrieve: when you ask a question, the backend retrieves relevant chunks from the KB.
- Generate: the backend composes a prompt with retrieved context and calls OCI Generative AI (Cohere/Meta/xAI) to generate the final answer.

## Endpoints

- POST /api/upload
  - Multipart form-data with key "file"
  - Stores PDF content into the knowledge base for retrieval

- POST /api/genai/rag
  - JSON body with question and modelId
  - Executes the RAG pipeline and returns an answer string

## Example requests (curl)

- Upload a document:
```
curl -F "file=@/absolute/path/to/document.pdf" \
  http://localhost:8080/api/upload
```

- Ask a question:
```
curl -X POST http://localhost:8080/api/genai/rag \
  -H "Content-Type: application/json" \
  -d '{
    "question": "Summarize section 2 for me.",
    "modelId": "ocid1.generativeaimodel.oc1...."
  }'
```

Notes:
- Use GET /api/genai/models to list supported models in your compartment and pick a modelId.
- The backend adapts parameters per vendor to avoid invalid-argument errors (e.g., presencePenalty is not sent to xAI Grok).

## UI flow

- Open the web UI.
- Use the upload panel to upload PDFs.
- In Chat, select a model and ask questions; the backend will use your KB to enhance prompts.

## Where data is stored

- Liquibase migrations create core tables (conversations, messages, memory, telemetry) and KB tables for RAG.
- See DATABASE.md for the exact schema overview and Liquibase details.

## Tips

- Large PDFs: ingestion can take longer; watch backend logs.
- Models: if you see 400 invalid parameter errors, switch to another model or vendor. The backend already omits unsupported parameters for xAI Grok.
- Validation: after uploading, ask targeted questions about the document content to verify KB ingestion.
