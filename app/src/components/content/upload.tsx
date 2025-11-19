import "preact";
import { useContext, useRef, useState, useEffect } from "preact/hooks";
import "ojs/ojtoolbar";
import "oj-c/file-picker";
import "oj-c/message-toast";
import "oj-c/progress-bar";
import "oj-c/button";
import MutableArrayDataProvider = require("ojs/ojmutablearraydataprovider");
import { CFilePickerElement } from "oj-c/file-picker";
import { CButtonElement } from "oj-c/button";
import { ConvoCtx } from "../app";
import { debugLog } from "../../libs/debug";
import { setKv, deleteKv } from "../../libs/memory";

type Props = {
  backendType: "java";
  modelId: string | null;
};

const acceptArr: string[] = ["application/pdf", "*.pdf"];
// Client-side limit aligned with backend: 100 MB
const FILE_SIZE = 100 * 1024 * 1024;

export const Upload = ({ backendType, modelId }: Props) => {
  const conversationId = useContext(ConvoCtx);

  const [fileNames, setFileNames] = useState<string[] | null>(null);
  const [file, setFile] = useState<File | null>(null);
  const [loading, setLoading] = useState<boolean>(false);

  // Text ingest state
  const [textInput, setTextInput] = useState<string>("");
  const [textTitle, setTextTitle] = useState<string>("");
  const [textDocId, setTextDocId] = useState<string>("");
  const [textTags, setTextTags] = useState<string>(""); // comma-separated
  const [ingestLoading, setIngestLoading] = useState<boolean>(false);

  const [messages, setMessages] = useState<
    { id: number; severity: "Info" | "Error" | "Warning" | "Confirmation"; summary: string }[]
  >([]);
  const messagesDP = new MutableArrayDataProvider<number, { id: number; severity: string; summary: string }>(
    messages,
    { keyAttributes: "id" }
  );

  const closeMessage = () => setMessages([]);

  const beforeSelectListener = (event: CFilePickerElement.ojBeforeSelect) => {
    const accept: (acceptPromise: Promise<void>) => void = event.detail.accept;
    const files: FileList = event.detail.files;
    // Enforce type and size early
    const f = files[0];
    if (!f) {
      accept(Promise.reject());
      return;
    }
    if (!(f.type === "application/pdf" || f.name.toLowerCase().endsWith(".pdf"))) {
      setMessages([{ id: 1, severity: "Error", summary: "Only PDF files are supported." }]);
      accept(Promise.reject());
      return;
    }
    if (f.size > FILE_SIZE) {
      setMessages([
        {
          id: 2,
          severity: "Error",
          summary: `File "${f.name}" is too big. Maximum size is ${Math.round(FILE_SIZE / (1024 * 1024))}MB.`,
        },
      ]);
      accept(Promise.reject());
      return;
    }
    accept(Promise.resolve());
  };

  const invalidListener = (event: CFilePickerElement.ojInvalidSelect) => {
    const until = event.detail.until;
    if (until) until.then(() => void 0);
  };

  const selectListener = async (event: CFilePickerElement.ojSelect) => {
    const files: FileList = event.detail.files;
    if (!files || files.length === 0) {
      setFile(null);
      setFileNames(null);
      return;
    }
    setFile(files[0]);
    setFileNames([files[0].name]);
    setMessages([]);
    try {
      await setKv(conversationId, 'pendingUpload', { name: files[0].name, size: files[0].size, lastModified: files[0].lastModified }, 300);
    } catch (e) {
      // non-blocking
    }
  };

  const doUpload = async (_ev: CButtonElement.ojAction) => {
    if (!file) {
      setMessages([{ id: 4, severity: "Error", summary: "Please select a PDF file to upload." }]);
      return;
    }

    setLoading(true);
    try {
      const formData = new FormData();
      formData.append("file", file);

      const headers: Record<string, string> = {
        conversationID: conversationId,
        // Enable KB ingestion during upload for RAG
        "X-RAG-Ingest": "true",
        // Use same tenant as RAG queries (defaults to "default")
        "X-Tenant-Id": "default"
        // Optionally set "Embedding-Model-Id" if you want to override server default (1024-dim)
      };
      if (modelId) headers["modelId"] = modelId;

      const res = await fetch("/api/upload", {
        method: "POST",
        body: formData,
        headers
      });

      if (!res.ok) {
        // Try to read JSON error if provided by Spring
        let detail = `${res.status} ${res.statusText}`;
        try {
          const json = await res.json();
          if (json?.error) detail = `${res.status} ${json.error}`;
        } catch {
          // ignore
        }
        throw new Error(`Upload failed: ${detail}`);
      }

      const json = await res.json();
      // Controller returns Answer { content, errorMessage }
      const content = json?.content ?? "";
      const errorMessage = json?.errorMessage ?? "";
      if (errorMessage) {
        setMessages([{ id: 6, severity: "Error", summary: `Server error: ${errorMessage}` }]);
      } else {
        setMessages([{ id: 7, severity: "Confirmation", summary: "Upload successful. You can now use RAG to ask about this document." }]);
        try {
          await deleteKv(conversationId, 'pendingUpload');
        } catch (e) {
          // non-blocking
        }
        // Auto-run KB diagnostics after a successful upload and log to console
        try {
          const diagRes = await fetch("/api/kb/diag?tenantId=default");
          if (diagRes.ok) {
            const diag = await diagRes.json();
            debugLog("KB DIAG after upload:", diag);
          } else {
            console.warn("KB DIAG after upload failed:", diagRes.status, diagRes.statusText);
          }
        } catch (e) {
          console.warn("KB DIAG after upload errored:", e);
        }
      }
    } catch (e: any) {
      setMessages([{ id: 8, severity: "Error", summary: e?.message || "Upload failed." }]);
    } finally {
      setLoading(false);
    }
  };

  const clearSelection = async (_ev: CButtonElement.ojAction) => {
    setFile(null);
    setFileNames(null);
    setMessages([]);
    setLoading(false);
    try {
      await deleteKv(conversationId, 'pendingUpload');
    } catch (e) {
      // non-blocking
    }
  };

  const clearText = (_ev: CButtonElement.ojAction) => {
    setTextInput("");
    setTextTitle("");
    setTextDocId("");
    setTextTags("");
    setIngestLoading(false);
    setMessages([]);
  };

  const doIngestText = async (_ev: CButtonElement.ojAction) => {
    if (!textInput || !textInput.trim()) {
      setMessages([{ id: 21, severity: "Error", summary: "Please paste some text to ingest." }]);
      return;
    }
    if (textInput.length > 400000) {
      setMessages([{ id: 22, severity: "Error", summary: "Text too large. Limit is 400,000 characters." }]);
      return;
    }

    setIngestLoading(true);
    try {
      const tags =
        textTags
          .split(",")
          .map((t) => t.trim())
          .filter((t) => t.length > 0);

      const body = {
        tenantId: "default",
        docId: textDocId || null,
        title: textTitle || "text",
        uri: null,
        mime: "text/plain",
        tags,
        embeddingModelId: null,
        text: textInput
      };

      const res = await fetch("/api/kb/ingest-text", {
        method: "POST",
        headers: {
          "Content-Type": "application/json"
        },
        body: JSON.stringify(body)
      });

      if (!res.ok) {
        let detail = `${res.status} ${res.statusText}`;
        try {
          const j = await res.json();
          if (j?.error) detail = `${res.status} ${j.error}`;
        } catch {}
        throw new Error(`Text ingest failed: ${detail}`);
      }

      const json = await res.json(); // { docId, chunkCount, embedCount, hash }
      setMessages([
        {
          id: 23,
          severity: "Confirmation",
          summary: `Text ingested. docId=${json?.docId ?? "n/a"}, chunks=${json?.chunkCount ?? "?"}, embeddings=${json?.embedCount ?? "?"}`
        }
      ]);

      // Optional: run KB diagnostics
      try {
        const diagRes = await fetch("/api/kb/diag?tenantId=default");
        if (diagRes.ok) {
          const diag = await diagRes.json();
          debugLog("KB DIAG after text ingest:", diag);
        }
      } catch (e) {
        // non-blocking
      }
    } catch (e: any) {
      setMessages([{ id: 24, severity: "Error", summary: e?.message || "Text ingest failed." }]);
    } finally {
      setIngestLoading(false);
    }
  };

  useEffect(() => {
    // no-op for now
  }, []);

  return (
    <>
      <oj-c-message-toast data={messagesDP} onojClose={closeMessage}></oj-c-message-toast>

      <div class="oj-flex-item oj-sm-margin-4x">
        <h1>RAG Knowledge Base</h1>
        <div class="oj-typography-body-md oj-sm-padding-1x-bottom">
          Upload a PDF to add it to your knowledge base. The backend will ingest and index it for RAG.
        </div>

        <oj-c-file-picker
          id="filepickerUpload"
          accept={acceptArr}
          selectionMode="single"
          onojBeforeSelect={beforeSelectListener}
          onojInvalidSelect={invalidListener}
          onojSelect={selectListener}
          secondaryText={`Maximum file size is ${Math.round(FILE_SIZE / (1024 * 1024))}MB.`}
        ></oj-c-file-picker>

        {fileNames && (
          <>
            <div class="oj-sm-margin-4x-top">
              <span class="oj-typography-bold">File: </span>
              {fileNames.join(", ")}
            </div>
            <oj-toolbar class="oj-sm-margin-6x-top" aria-label="upload toolbar" aria-controls="uploadContent">
              <oj-c-button label="Upload" disabled={!file || loading} onojAction={doUpload}></oj-c-button>
              <oj-c-button label="Clear" disabled={loading} onojAction={clearSelection}></oj-c-button>
            </oj-toolbar>
          </>
        )}

        {loading && (
          <>
            <div class="oj-sm-margin-4x oj-typography-subheading-md">Uploading...</div>
            <oj-c-progress-bar class="oj-sm-margin-4x oj-sm-width-full oj-md-width-1/2" value={-1}></oj-c-progress-bar>
          </>
        )}

        <div class="oj-sm-margin-8x-top">
          <h2>Ingest Text</h2>
          <div class="oj-typography-body-sm oj-text-color-secondary oj-sm-margin-2x-bottom">
            Paste plain text to add it directly to your knowledge base for RAG. Optionally provide a title, a custom doc id, and tags (comma-separated).
          </div>

          <div class="oj-sm-margin-2x-bottom">
            <label class="oj-typography-body-sm oj-sm-margin-1x-right">Title:</label>
            <input
              class="oj-sm-width-1/1 oj-md-width-1/2"
              type="text"
              value={textTitle}
              onInput={(e: any) => setTextTitle((e.target as HTMLInputElement).value)}
              placeholder="Optional title"
            />
          </div>
          <div class="oj-sm-margin-2x-bottom">
            <label class="oj-typography-body-sm oj-sm-margin-1x-right">Doc ID:</label>
            <input
              class="oj-sm-width-1/1 oj-md-width-1/2"
              type="text"
              value={textDocId}
              onInput={(e: any) => setTextDocId((e.target as HTMLInputElement).value)}
              placeholder="Optional custom doc id"
            />
          </div>
          <div class="oj-sm-margin-2x-bottom">
            <label class="oj-typography-body-sm oj-sm-margin-1x-right">Tags:</label>
            <input
              class="oj-sm-width-1/1 oj-md-width-1/2"
              type="text"
              value={textTags}
              onInput={(e: any) => setTextTags((e.target as HTMLInputElement).value)}
              placeholder="Comma-separated (e.g., finance,policy)"
            />
          </div>

          <textarea
            class="oj-sm-width-full oj-sm-margin-2x-bottom"
            rows={10}
            value={textInput}
            onInput={(e: any) => setTextInput((e.target as HTMLTextAreaElement).value)}
            placeholder="Paste text here (max 400,000 characters)."
          ></textarea>

          <oj-toolbar class="oj-sm-margin-2x-top" aria-label="text ingest toolbar">
            <oj-c-button label="Ingest Text" disabled={ingestLoading || !textInput} onojAction={doIngestText}></oj-c-button>
            <oj-c-button label="Clear" disabled={ingestLoading} onojAction={clearText}></oj-c-button>
          </oj-toolbar>

          {ingestLoading && (
            <>
              <div class="oj-sm-margin-4x oj-typography-subheading-md">Ingesting...</div>
              <oj-c-progress-bar class="oj-sm-margin-4x oj-sm-width-full oj-md-width-1/2" value={-1}></oj-c-progress-bar>
            </>
          )}
        </div>

        <div id="uploadContent" class="oj-sm-margin-6x-top oj-typography-body-sm oj-text-color-secondary">
          Tip: After a successful upload or text ingest, enable RAG in Settings and ask a question about your document in Chat.
        </div>
      </div>
    </>
  );
};
