# Telecom Knowledge Assistant — Webex Bot Runbook

End-to-end reference for running the RAG pipeline + Webex bot integration, from a cold start.

---

## Prerequisites (one-time, per machine)

- Docker Desktop installed and running
- Ollama installed, with `llama3` and `nomic-embed-text` models pulled
- A Webex bot created at https://developer.webex.com/my-apps (bot token, bot email)
- An ngrok account + authtoken (https://dashboard.ngrok.com/get-started/your-authtoken)
- `.env` file created from `.env.example`, filled in with real values:
  ```
  WEBEX_BOT_TOKEN=...
  WEBEX_BOT_EMAIL=...
  WEBEX_WEBHOOK_SECRET=...
  NGROK_AUTHTOKEN=...
  ```

---

## Every time you start working (cold start sequence)

### 0. Make sure Rancher Desktop is running

Open the app and wait for its engine status to show running — `docker` commands will fail with a connection error otherwise.

### 1. Start Ollama with the correct network binding

Ollama must listen on `0.0.0.0`, not just `127.0.0.1`, so the Docker containers can reach it.

```powershell
Get-Process ollama -ErrorAction SilentlyContinue    # check nothing already running
# if something is running on 127.0.0.1:11434 only, stop it first:
# Stop-Process -Id <PID> -Force

$env:OLLAMA_HOST = "0.0.0.0:11434"
ollama serve
```

Leave this terminal open. Verify in another terminal:
```powershell
netstat -ano | findstr 11434
```
Look for `0.0.0.0:11434 ... LISTENING`.

> This machine's environment/security policy doesn't reliably persist `OLLAMA_HOST` for background-launched processes (tray app, services), so this manual step is needed each session. If the Ollama tray app auto-starts and re-binds to loopback-only, quit it from the system tray so it doesn't fight with the manual one.

### 2. Build the Spring Boot jar

```powershell
cd C:\Personal\Study\AI_Related\NC_Write_Own_LLM\Project\RAG_Pipe_Line_Local\Telecom-Knowledge-Assistant-with-webex-Integration
mvn clean package -DskipTests
```

Re-run this any time you change Java code.

### 3. Start the full stack (Postgres + app + ngrok)

```powershell
docker compose up -d --build
```

Confirm all three containers are healthy:
```powershell
docker compose ps
```
Expect `rag-postgres`, `rag-app`, `rag-ngrok` all `Up`.

### 4. Register the Webex webhook

```powershell
powershell -ExecutionPolicy Bypass -File .\register-webhook.ps1
```

This reads the current ngrok public URL automatically and registers/refreshes the webhook (safe to re-run any time — it replaces any existing webhook for the same URL rather than erroring).

---

## First-time-only: load your knowledge base

The Postgres/pgvector volume persists across restarts, so you only need to do this once per document (or whenever you have new documents to add).

**Upload a file (PDF, DOCX, XLSX/XLS, TXT):**
```powershell
Invoke-RestMethod -Method Post -Uri "http://localhost:8084/api/documents/upload" -Form @{ file = Get-Item "C:\path\to\document.pdf" }
```

**Or ingest a web page:**
```powershell
Invoke-RestMethod -Method Post -Uri "http://localhost:8084/api/documents/url" -ContentType "application/json" -Body '{"url":"https://example.com/page"}'
```

**Confirm ingestion:**
```powershell
Invoke-RestMethod -Uri "http://localhost:8084/api/documents"
docker compose exec postgres psql -U raguser -d ragdb -c "select count(*) from document_embeddings;"
```

> ⚠️ Only run `docker compose down -v` (with `-v`) if you genuinely want to wipe this data — that deletes the Postgres volume and every ingested document along with it.

---

## Test before touching Webex

Bypass Webex entirely to confirm the RAG pipeline itself is healthy:

```powershell
Invoke-RestMethod -Method Post -Uri "http://localhost:8084/api/query" -ContentType "application/json" -Body '{"question":"your test question"}'
```

If this returns a real answer, the whole backend is working — anything left to debug is Webex/tunnel-specific.

---

## Use it

Open Webex, message your bot directly (or `@mention` it in a group space) with a question. Behind the scenes:

```
Webex message
  → webhook POST to /webex/webhook
  → app fetches full message text from Webex API
  → strips @mention if present
  → calls RagQueryService (VectorDB search + Ollama LLM)
  → posts the answer back to the same Webex room
```

---

## Shutting down / restarting

**Stop everything, keep data:**
```powershell
docker compose down
```

**Stop everything AND wipe the knowledge base:**
```powershell
docker compose down -v
```

**Next session:** repeat the cold start sequence above (steps 1–4). Since free-tier ngrok URLs can change on restart, always re-run `register-webhook.ps1` after step 3.

---

## What's actually running

Only two independent things to manage — everything else lives inside one of them:

| Component | Where it runs | Managed by |
|---|---|---|
| **Ollama** (LLM + embedding model) | Directly on Windows, outside Docker | Manual `ollama serve` |
| **Rancher Desktop** | Your container engine (Docker Desktop alternative) | Open/quit the app |
| `rag-postgres`, `rag-app`, `rag-ngrok` | Containers *inside* Rancher Desktop | `docker compose up` / `down` — all three start and stop together as one unit |

So there's no separate "start ngrok" or "start the Spring Boot app" step — both are containers started by `docker compose up -d`.

### Start sequence

```powershell
# 1. Open Rancher Desktop, wait for the engine status to show running

# 2. Start Ollama (leave this terminal open)
$env:OLLAMA_HOST = "0.0.0.0:11434"
ollama serve

# 3. Start the containers (new terminal)
docker compose up -d --build

# 4. Register the webhook
powershell -ExecutionPolicy Bypass -File .\register-webhook.ps1
```

### Stop sequence (reverse order)

```powershell
# 1. Stop the containers (keeps data; add -v only to wipe the knowledge base)
docker compose down

# 2. Stop Ollama
Stop-Process -Id (Get-Process ollama).Id -Force
# (or Ctrl+C in its terminal)

# 3. Quit Rancher Desktop -- optional, only if done with Docker entirely
```

### Check what's currently running, any time

```powershell
docker compose ps
Get-Process ollama -ErrorAction SilentlyContinue
```

---

## Volume stability — `COMPOSE_PROJECT_NAME`

Docker Compose derives the project name (and therefore the Postgres volume name) from the **folder path** you run `docker compose` from, by default. Moving the project to a different folder — e.g. extracting a new zip somewhere new — silently creates a brand-new, empty volume instead of reattaching to your existing ingested data, even though the container names and schema look identical.

**Fix (already in `.env.example`):**
```
COMPOSE_PROJECT_NAME=telecom-rag
```

Add this to your real `.env` too. Once set, the volume name is pinned regardless of folder path, so this can't happen again.

> ⚠️ The *first* time you add this to an already-running setup, it will itself create one more new (empty) volume, since it changes the current project-name prefix to `telecom-rag`. Re-ingest your documents once after making this change, and it'll be stable from then on.

---


| Symptom | Cause | Fix |
|---|---|---|
| `401` registering webhook | `WEBEX_BOT_TOKEN` isn't the real token | Re-check `.env` |
| `500` "Duplicate webhooks..." | Old webhook still active for same URL | Already handled automatically by `WebhookAdminController` — just re-run the script |
| `404` on `/webex/admin/register-webhook` | Running an old jar without the Webex code | `mvn clean package` then `docker compose up -d --build` |
| Bot never replies in Webex | Webhook not registered, or app not receiving the POST | Check `http://localhost:4040` (ngrok inspector) to see if the request arrived at all |
| `ConnectException` to `host.docker.internal:11434` | Ollama bound to `127.0.0.1` only, or Windows Firewall blocking Docker → host | Steps 1 above + `New-NetFirewallRule -DisplayName "Ollama - Allow Docker" -Direction Inbound -Protocol TCP -LocalPort 11434 -Action Allow -Profile Any` (as Administrator) |
| "I don't have enough information..." | Knowledge base is empty, or question doesn't match ingested content (score < 0.5) | Check `select count(*) from document_embeddings;` — ingest documents if 0 |
| `docker compose up --build` fails downloading dependencies | Corporate proxy interfering with Maven inside Docker | Build the jar on the host first (`mvn clean package`); Dockerfile just copies the pre-built jar, no in-container downloads |
| PowerShell won't run `.ps1` | Execution policy | `powershell -ExecutionPolicy Bypass -File .\register-webhook.ps1` |
