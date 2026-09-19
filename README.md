# MindBridge · V2

A runnable student wellbeing prototype: **Java 17, Spring Boot, Spring Security, Spring AI, Reactor SSE, JDBC, H2 / MySQL, and Apache POI**. The English UI separates a student's conversations from an administrator's knowledge library, screening reports, Excel ledger, and safety alerts.

Default **demo mode needs no API key**. Responses and screening are illustrative, not clinically validated. Use synthetic data. Local notification records do not contact counselors or emergency services.

## Start

Requirements: Java 17+ and Maven 3.9+.

```bash
./scripts/run-dev.sh
# equivalent:
mvn spring-boot:run
```

Open <http://localhost:8087>. The server binds to `127.0.0.1`.

| Local account | Default password | Role |
|---|---|---|
| `student` | `student-demo` | Student |
| `student2` | `student2-demo` | Second student, for isolation checks |
| `admin` | `admin-demo` | Administrator |

Override with `STUDENT_PASSWORD`, `SECOND_STUDENT_PASSWORD`, and `ADMIN_PASSWORD`. Accounts are provisioned in memory at startup; passwords use BCrypt via Spring Security's delegating encoder. This is a three-account demo, not a registration or identity management system. Login uses a server session with CSRF protection; it does not store passwords or bearer tokens in browser storage.

```bash
mvn verify
java -jar target/mindbridge-0.2.0.jar
```

Run from the repository root. Data is stored under `./data/` and excluded from Git. `.env` is not automatically loaded by Spring Boot; export environment variables explicitly.

## Business flow implemented

```text
Sign in → student / admin role

Student message
  → owner check + per-session concurrency guard
  → MemoryAgent: latest 12 SQL messages
  → SupervisorAgent: CHAT / CONSULT / RISK
      CHAT → CompanionAgent → AIClient → SSE
      CONSULT / RISK
           → KnowledgeAgent: query expansion + BM25
           → RiskGuardianAgent: conservative screening
           → CounselorAgent → AIClient → SSE
  → commit input + screening report (CONSULT/RISK only)
    + safety alert and WAITING_RESPONSE job (RISK only)
  → generate reply; save completed assistant response
  → release high-risk tool job after response completes/errors/disconnects

Persistent worker
  → Excel snapshot succeeds
  → local notification record
  → completion / failure checkpoints + tool-event audit
```

The runtime has a hard cap of **8 stages**; current routes execute 3 or 5. Stages run deterministically within `AgentRuntimeService`, not as autonomous multi-process agents. AIClient receives the selected support role, recent history, and references. `CHAT` skips retrieval and creates no screening report. Current risk rules and explicit help/advice terms select `CONSULT`; safety language or an unresolved safety alert selects `RISK`. A high-risk response bypasses the LLM and uses a fixed safety template. Retrieved text cannot downgrade that safety decision.

Screening reports are nonclinical summaries containing a quoted student statement, rule label, rationale, and response status. They are committed before generation to survive model failure. The UI hides internal screening labels from students; student APIs expose their own transcript, not admin reports.

## Try all three paths

1. Sign in as `student`.
2. Send `Hello, I had a good day`. This takes `CHAT`; it creates no screening report.
3. Send `I am anxious about exams`. This takes `CONSULT`, retrieves references, and creates an admin screening report.
4. In a synthetic test, send `I want to hurt myself`. This takes `RISK`, saves a local alert immediately, and queues Excel → local notification after the reply.
5. Sign out and sign in as `admin`. The support desk shows reports, response status, tool checkpoints, retry counts, audit history, and alerts. Click **Refresh** after a few seconds to see worker progress. Download the actual `.xlsx` ledger and acknowledge the alert.
6. Open **Resource library** as admin to add, edit, or delete custom text cards; built-in demo cards are read-only. Custom cards persist and participate in retrieval immediately.
7. Sign in as `student2`. The other student's sessions are absent; requesting their transcript or posting to their session returns 404. Student access to admin APIs returns 403.

An open safety alert keeps subsequent messages in the same conversation on the safety route until an administrator acknowledges the record. Acknowledgment is an administrative action, not confirmation that a person is safe. Starting another conversation is not a clinical reset; this prototype does not perform cross-conversation clinical risk assessment.

## Tool ordering and retries

- Reports, jobs, local notification records, and tool events live in SQL.
- Jobs wait for the response to end. A 90-second overall generation deadline complements the real provider's 45-second inactivity timeout. Failed or canceled streams do not save partial assistant replies.
- On restart, interrupted jobs become retryable and pending response statuses become incomplete. A worker also recovers high-risk jobs stuck waiting for more than two minutes.
- Polling defaults to every two seconds. Failed jobs retry with backoff, up to three attempts per cycle; admins can retry `FAILED` or `RETRY` jobs. Completed jobs cannot be retried through the API.
- Excel is rebuilt from stored high-risk reports, using stable report IDs and atomic file replacement. Retrying rebuilds the same snapshot rather than appending duplicate rows. Student statements are written as string cells, never Excel formulas.
- Notification processing only runs after a successful Excel write. The local notification table has a unique report ID, so a retry after a checkpoint failure does not add a second record.
- The final notification status is **`LOCAL_RECORDED`**, not `DELIVERED`. There is no SMTP, external HTTP, or MCP notification adapter in this version.
- This worker is designed for **one application instance**, not clustered deployment. Its startup recovery assumes the previous instance has stopped.

`EXCEL_PATH` defaults to `./data/mindbridge-reports.xlsx`. An unwritable path blocks the notification step and exposes the failure to admins. After correcting the path/permissions, use **Retry tools**. Error records intentionally omit raw student text and provider error bodies.

## Model providers

One configured provider per process; no automatic cloud fallback or per-agent cost routing.

### Ollama

Install and start Ollama separately:

```bash
ollama pull llama3.2
AI_PROVIDER=ollama OLLAMA_MODEL=llama3.2 ./scripts/run-dev.sh
```

`OLLAMA_BASE_URL` defaults to `http://localhost:11434`. Uses Spring AI `OllamaChatModel`.

### OpenAI

```bash
export OPENAI_API_KEY='your-key'
AI_PROVIDER=openai OPENAI_MODEL=gpt-4o-mini ./scripts/run-dev.sh
```

Uses Spring AI `OpenAiChatModel`. This sends the current message, up to 12 prior messages, and retrieved cards to OpenAI and may incur API charges. Demo mode sends no model requests. Keys remain server-side. The UI uses optional Google Fonts and falls back to system fonts offline.

## Optional MySQL

Default H2 requires no Docker. For MySQL, install Docker, copy `.env.example` to `.env`, replace the database passwords, and run:

```bash
docker compose up -d
export DB_URL='jdbc:mysql://localhost:3307/mindbridge?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC'
export DB_USER=mindbridge
export DB_PASSWORD='the-password-from-your-env-file'
./scripts/run-dev.sh
```

Wait for MySQL to become ready. Compose reads `.env`; Spring Boot needs exported variables. The container maps to loopback port 3307. MySQL configuration is included but has not been runtime-tested in the local development environment, which has no Docker.

## Upgrade from V1

V2 uses additive tables in `schema.sql`; existing conversations/messages/alerts are preserved. **Legacy anonymous conversations have no owner and are not assigned to either student.** Admins can still see legacy alerts. New conversations receive an explicit owner. Browser session IDs are stored per username. No legacy data is pushed to GitHub.

This is an additive prototype schema, not a production migration system. Use a versioned migration tool and tested backups before operating on real data.

## API and access

| Method | Route | Access / purpose |
|---|---|---|
| GET | `/api/csrf` | Public; obtains a session CSRF token |
| POST | `/login`, `/logout` | Form login / session logout, CSRF required |
| GET | `/api/profile`, `/api/status` | Authenticated; role and scoped counts |
| GET / POST | `/api/conversations` | Student; list own / create owned session |
| GET | `/api/conversations/{id}/messages` | Student owner; transcript |
| POST | `/api/conversations/{id}/messages` | Student owner; SSE reply |
| GET | `/api/conversations/{id}/report` | Student owner; downloadable transcript (legacy route name) |
| GET | `/api/knowledge` | Authenticated; reference cards |
| POST | `/api/admin/knowledge` | Admin; create custom card |
| PUT / DELETE | `/api/admin/knowledge/{id}` | Admin; edit / delete custom card |
| GET | `/api/admin/reports`, `/api/admin/reports/{id}` | Admin; screening reports / tool audit |
| POST | `/api/admin/reports/{id}/retry` | Admin; retry failed tool job |
| GET | `/api/admin/excel` | Admin; generated XLSX ledger |
| GET | `/api/alerts` | Admin; local safety alerts |
| POST | `/api/alerts/{id}/acknowledge` | Admin; acknowledge record |

All state-changing requests require the CSRF header returned by `/api/csrf`. Fetch a fresh token after login. SSE uses `meta` (intent, references, provider), `token` (`text`), `done` (`persisted: true`), and `error` events. `done` follows completed-response persistence. The HTTP API rejects missing authentication (401), forbidden roles / missing CSRF (403), unknown or unowned sessions (404), and concurrent messages in one session (409).

## Verification and boundaries

Automated tests cover authentication, CSRF, role restrictions, cross-student access, three route traces, report suppression for CHAT, hidden student labels, persistence/SSE, knowledge CRUD, model errors, cancellation, Excel-before-notification ordering, retry exhaustion, restart recovery, local notification deduplication, and XLSX string-cell safety. CI runs `mvn verify`.

Still deferred from the target business-flow diagram:

- Redis memory cache; SQL is the current memory source.
- JPA / reactive WebFlux server; this version retains JDBC + Spring MVC with Reactor SSE.
- PDF/Markdown ingestion, chunking, embeddings, ChromaDB hybrid retrieval, reranking.
- Fine-tuned Qwen/Llama, LoRA training/merging, GGUF packaging.
- SMTP/HTTP/MCP tool adapters and verified external delivery.
- RAGAS / retrieval evaluation datasets, performance benchmarks.
- Production account management, rate limiting, retention/deletion policies, encrypted health-data storage, and clinical validation.

Keyword screening can miss or misread risk, including negated or historical statements. Knowledge cards are illustrative, not a reviewed clinical corpus. Database files, reports, and the Excel ledger are plaintext. Keep the prototype local and use synthetic data. No clinical accuracy, throughput improvement, relevance improvement, or delivery-rate percentage is claimed.

Implementation references: [Spring Security CSRF](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html), [Spring AI chat models](https://docs.spring.io/spring-ai/reference/api/chatmodel.html), [Apache POI workbook API](https://poi.apache.org/apidocs/dev/org/apache/poi/ss/usermodel/Workbook.html). Versions are pinned in `pom.xml`.
