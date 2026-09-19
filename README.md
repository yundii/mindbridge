# MindBridge

A runnable student wellbeing MVP built with **Java 17, Spring Boot, Spring AI, Reactor, and SQL**. An English web interface connects daily check-ins, transparent screening, local knowledge retrieval, streamed replies, and a local support desk.

**No API key is needed for the default demo.** Demo responses are templates, visibly labeled in the interface. This is a portfolio prototype, not a medical device, therapist, or emergency service. Use synthetic conversations only.

## Run locally

Requirements: Java 17+ and Maven 3.9+.

```bash
mvn spring-boot:run
```

Open <http://localhost:8087>. The app binds to `127.0.0.1`. H2 stores data in `./data/`; it survives restarts and is excluded from Git. Start commands from this repository root.

```bash
mvn verify
# Or run the packaged application:
java -jar target/mindbridge-0.1.0.jar
```

## Try the complete flow

1. Select **Exams feel overwhelming**, or send `I am anxious about my exams`.
2. Watch the reply stream and inspect the support route, response provider, and retrieved references.
3. Refresh the page to restore the conversation; export its JSON report.
4. In a synthetic test conversation, send `I want to hurt myself`.
5. Open **Support desk**, inspect the local alert, and acknowledge it.
6. Browse **Resource library**, or start a new conversation.

High-risk rule matches bypass the model and use a fixed safety response. A local alert is committed together with the user message **before** any reply starts. No email, SMS, real counselor, or emergency responder is contacted. Acknowledgment changes a record; it does not confirm that anyone is safe.

## Model providers

One configured provider per application process. This MVP does not implement automatic cost/latency routing or cloud fallback.

### Ollama

Install Ollama separately and run its service, then:

```bash
ollama pull llama3.2
AI_PROVIDER=ollama OLLAMA_MODEL=llama3.2 mvn spring-boot:run
```

`OLLAMA_BASE_URL` defaults to `http://localhost:11434`. Calls use Spring AI's `OllamaChatModel`. Model streams time out after 45 seconds of inactivity.

### OpenAI

```bash
export OPENAI_API_KEY='your-key'
AI_PROVIDER=openai OPENAI_MODEL=gpt-4o-mini mvn spring-boot:run
```

Calls use Spring AI's `OpenAiChatModel`. This mode sends the current message, up to 12 prior messages, and retrieved reference cards to OpenAI and may incur API charges. Nothing is sent to a model provider in demo mode. Keys are read from environment variables, never from the browser. `.env` files are not automatically loaded by Spring Boot.

## Optional MySQL

Default H2 needs no Docker. To run the supplied MySQL service, install Docker and create a local `.env` from `.env.example`, replacing both database passwords. Then:

```bash
docker compose up -d
export DB_URL='jdbc:mysql://localhost:3307/mindbridge?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC'
export DB_USER=mindbridge
export DB_PASSWORD='the-password-from-your-env-file'
mvn spring-boot:run
```

Wait until MySQL is ready before starting the app. The same SQL schema is initialized on startup. Compose loads `.env` for container configuration; export the app's database values separately. MySQL is mapped to loopback only. MySQL support is configured but was not runtime-tested in the initial development environment, where Docker was unavailable.

## Architecture

```text
English browser UI
  │ POST message → SSE meta / token / done / error
  ▼
ChatController (orchestration + per-session concurrency guard)
  ├── RiskAgent       → keyword screening + specialized response route
  ├── KnowledgeAgent  → query expansion + BM25 over four demo cards
  ├── ConversationStore → transaction: user message + optional local alert
  └── AIClient        → demo / Spring AI Ollama / Spring AI OpenAI
                        └── high-risk match → fixed safety template
  │
  └── completed response → boundedElastic SQL save → done event
```

`RiskAgent`, `KnowledgeAgent`, and `AIClient` are deterministic modules under one orchestrator. `CompanionAgent`, `CopingAgent`, `SupportAgent`, and `SafetyAgent` identify response routes; they are not independently autonomous LLM workers. Completed assistant replies are saved off the streaming scheduler, and `done` is emitted only after that save succeeds. User/alert persistence is synchronous and transactional. On model failure or disconnect, incomplete assistant text is not saved; the accepted user message and local alert remain. Reports are computed on request, not in a durable background job queue.

## What is implemented vs. planned

| Area | This MVP | Follow-up |
|---|---|---|
| Orchestration | Screening, retrieval, specialized prompts / response templates | Independently evaluated specialist agents |
| Risk screening | Four transparent bilingual keyword categories | LoRA classifier + dataset governance + clinical validation |
| RAG | Rule-based query expansion + local BM25; references injected into real model prompts | ChromaDB embeddings, hybrid fusion, reranking |
| AIClient | Configurable demo / Ollama / OpenAI adapter | Per-agent routing, budget policy, telemetry |
| Streaming | SSE + Reactor; real providers emit model chunks, demo emits timed characters | Load testing and backpressure measurements |
| Persistence | H2 default, optional MySQL | Migrations, retention, encrypted storage |
| Reports | Downloadable session JSON with screening counts | Durable asynchronous report jobs |
| Escalation | Persistent local alerts + acknowledgment | Authenticated counselor workflow, MCP tool adapters, verified delivery |

There is **no LoRA training, ChromaDB, embedding search, neural reranker, or MCP implementation yet**. The résumé's **28%, 25%, 40%, and 98%** metrics are not claimed or reproduced. Establish datasets, baselines, and repeatable evaluations before using these figures for this repository.

## API

| Method | Route | Purpose |
|---|---|---|
| GET | `/api/status` | Configured provider and aggregate counts |
| POST | `/api/conversations` | Create a session |
| GET | `/api/conversations/{id}/messages` | Restore messages |
| POST | `/api/conversations/{id}/messages` | Stream reply; JSON `{ "message": "..." }` |
| GET | `/api/conversations/{id}/report` | Session report |
| GET | `/api/knowledge` | Demo knowledge cards |
| GET | `/api/alerts` | Local alerts |
| POST | `/api/alerts/{id}/acknowledge` | Acknowledge local record |

SSE emits `meta` (screening, references, provider, local alert ID), repeated `token` objects with `text`, and `done` with `persisted: true`. An `error` event means the reply did not complete. HTTP 400 rejects blank / >4000-character messages; 404 rejects missing resources; 409 rejects concurrent messages in the same conversation.

## Limits and deployment boundary

- Rule matches are deliberately conservative, including negated and historical safety mentions. Unmatched messages can still involve serious risk. Category labels are neither diagnoses nor confidence scores.
- Demo knowledge cards are original illustrative content, not a reviewed clinical corpus. Retrieval relevance does not establish medical accuracy.
- No authentication, authorization, or counselor role isolation exists. All sessions and alerts are accessible to local API clients. Do not expose this MVP to a network or enter real student health information.
- Conversations are stored in plaintext. New conversation creates a new ID; it does not delete old records. Browser storage holds only the current session ID. There is no retention / deletion workflow yet.
- The UI loads optional Google Fonts; system fonts remain usable offline.
- Provider adapters are compiled; initial automated tests cover demo mode. Real Ollama/OpenAI behavior requires configured services and credentials.
- This is a single-process prototype, with no durable task queue, distributed lock, notification delivery, or production performance claims.

## Verification

`mvn verify` tests all four routes, safety precedence, bilingual rule matching, conservative negation handling, BM25 relevance, input validation, SSE completion, persistence/report generation, and alert creation/acknowledgment. GitHub Actions runs the Java test suite on pushes and pull requests.

Reference APIs: [Spring AI Chat Model](https://docs.spring.io/spring-ai/reference/api/chatmodel.html), [Ollama integration](https://docs.spring.io/spring-ai/reference/api/chat/ollama-chat.html). Dependencies are pinned in `pom.xml`; this prototype is not presented as using the latest releases.
