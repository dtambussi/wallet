# Cross-border wallet (challenge backend)

Modular monolith: **Java 21**, **Spring Boot 3.4**, **JDBC**, **PostgreSQL**, **hexagonal-lite** (domain + application ports + adapters). User identity is the **`userId` path segment**—no `X-User-Id` header.

## Docs

| Doc | Read it for                                                                                                                                        |
|-----|----------------------------------------------------------------------------------------------------------------------------------------------------|
| **[English — CHALLENGE.md](docs/CHALLENGE.md)** · **[Español — CHALLENGE.es.md](docs/es/CHALLENGE.es.md)** | What the exercise asks for (deliverables, timeline, philosophy).                                                                                   |
| **[DESIGN.md](docs/DESIGN.md)** | **“What did we build, and what does it promise?”** — behavior, invariants, data model, failure handling, known limitations, how the design scales. |
| **[DECISIONS.md](docs/DECISIONS.md)** | **“Why did we choose this over plausible alternatives?”** — stack, ledger/FX choices, tradeoffs vs other reasonable options.                       |
| **[RUNBOOK.md](docs/RUNBOOK.md)** | How to observe and respond — metrics, alerts, playbooks.                                                                                           |
| **[GUARANTEE_COVERAGE.md](docs/GUARANTEE_COVERAGE.md)** | **"Which test proves which guarantee?"** — maps every invariant from DESIGN.md to the test(s) that exercise it under concurrent load, failure injection, or retry conditions. |
| **[V1__baseline.sql](src/main/resources/db/migration/V1__baseline.sql)** | Single baseline Flyway migration (schema as shipped).                                                                                              |

## API (summary)

Use your real host/port where the app listens (Compose exposes **8080** on the loopback interface by default).

*Example:*  
API `http://localhost:8080` · Swagger API Docs UI `http://localhost:8080/swagger-ui/index.html`

| Method | Path | Purpose |
|--------|------|---------|
| `POST` | `/users` | Create user → `{ "userId": "uuid" }` |
| `POST` | `/users/{userId}/deposits` | Deposit (body: `amount`, `currency`) |
| `POST` | `/users/{userId}/withdrawals` | Withdrawal (ledger debit committed immediately; provider payout dispatched async via outbox worker) |
| `GET` | `/users/{userId}/balances` | All supported currencies (zero if empty) |
| `GET` | `/users/{userId}/transactions?limit=&cursor=` | Paginated ledger history (opaque `cursor`) |
| `POST` | `/users/{userId}/fx/quotes` | Create **FX quote** (30s TTL, configurable) |
| `POST` | `/users/{userId}/fx/exchanges` | Execute exchange for `quoteId` |
| `POST` | `/users/{userId}/transfers` | P2P transfer (`toUserId`, `amount`, `currency`, optional `toCurrency`) — omit `toCurrency` for same-currency; supply it for automatic cross-currency conversion at current rate |
| `POST` | `/users/{userId}/batch-transfers` | Batch P2P: one sender debit, N recipient pending credits in one transaction |
| `GET` | `/users/{userId}/pending-balances` | Uncleared inbound amounts (not yet spendable) |
| `POST` | `/users/{userId}/settle` | Move all `pending_amount` to available; returns settled amounts per currency |

**Batch transfers** (`/batch-transfers`): OPTIONAL vs baseline challenge scope; payroll-shaped N→many in one DB transaction. **Only here** do recipients land in **`pending_amount`** until **`POST /settle`**; ordinary **`/transfers`** and **deposits** credit **available** immediately.

**FX:** **`/fx/quotes` → `/fx/exchanges`** = self-conversion with a locked **`quoteId`** (TTL). **`/transfers`** + **`toCurrency`** = P2P cross-currency at the **live** rate at commit time — **no** `quoteId`; that path does not consume `fx_quotes`. Optional product extension alongside same-currency **`/transfers`**.

**Further detail** (package layout, idempotency/fingerprinting, FX TTLs + `runtime_config`, UUID/cursors, security/auth gap, supported currencies, production-style hardening): see **[DESIGN.md](docs/DESIGN.md)** — sections *Known limitations*, *Guarantees*, *Application structure*, and *Data model*. Operational triage: **[RUNBOOK.md](docs/RUNBOOK.md)**.

## Get the code

Clone from **[github.com/dtambussi/wallet](https://github.com/dtambussi/wallet)** and enter the repo:

```bash
git clone https://github.com/dtambussi/wallet.git
cd wallet
```

## Option A: Running without full local setup (just Docker)

Stay in repo root. With **Docker only** (no JDK on the host) you can run the full test suite, then the API — same machine, same folder.

### A.1 Requirements

No JDK or Gradle install on your machine is required — only **Docker** (Engine + Compose v2).

### A.2 Reference environment

*(Verified here; newer compatible releases should work.)*

> **Desktop** 4.35.1 (173168) · **Engine** 27.3.1 · **Compose** v2.29.7-desktop.1  
> *Needs Engine + Compose v2 (`docker compose`). Credential Helper v0.8.2 / Kubernetes v1.30.2 ship with Desktop but are **not** used by this repo.*

### A.3 Run all tests

Passing this suite exercises domain and application logic, HTTP/API behavior, and JDBC against **real Postgres** — confidence before you start the application below. **THE FIRST CONTAINERIZED RUN CAN TAKE A FEW MINUTES** (**Gradle** dependencies and Docker image pulls); later runs reuse **gradle-cache**.

```console
~/wallet $ docker compose --profile test run --rm test
```

The **--rm** flag removes the ephemeral Gradle container when the run finishes — images and the **gradle-cache** volume remain.

To run one test class or pattern only, override the Compose command for that run — same image, volumes, and Testcontainers — by appending **`gradle cleanTest test --no-daemon --tests`** *FullyQualifiedClassName* after the service name (quote **--tests** patterns that use shell wildcards).

### A.4 Start the application

```console
~/wallet $ docker compose up --build
```

API `http://localhost:8080` · Swagger UI `http://localhost:8080/swagger-ui/index.html` · Postgres on host `localhost:5433` (db/user/password `wallet`).

**Connect to the database directly:**

```bash
# psql (standard flags)
psql -h localhost -p 5433 -U wallet -d wallet
# password: wallet

# psql (connection URL)
psql "postgresql://wallet:wallet@localhost:5433/wallet"

# From inside Docker (no port needed)
docker compose exec postgres psql -U wallet -d wallet
```

**Database persistence:** the named volume **`postgres_data`** (and other compose-declared volumes) **survives** `docker compose up` and `docker compose down`. Postgres files are **reused** across restarts — nothing is wiped automatically. To delete that data, run **`docker compose down -v`** or remove the volume with **`docker volume rm`** (see names with **`docker volume ls`**).

Stop containers only: `docker compose down` (volumes stay). **Drop volumes too** (empty Postgres; may also remove **`gradle-cache`** if present): `docker compose down -v`.

### A.5 Live test (main flows, real HTTP + DB)

> **Requires the application to be running first** — start it with `docker compose up --build` (A.4) and wait for the `Started WalletApplication` log line before continuing.

Then, in a second terminal, run the live test against all main flows — deposit, idempotency, P2P transfer, cross-currency transfer, FX quote+exchange, batch transfer+settle, and withdrawal:

```console
~/wallet $ docker compose --profile live-tests run --rm live-tests
```

The script prints each request/response inline, a pass/fail count, and **psql hints** at the end so you can verify the inserted rows directly. Exit code is `0` on full pass.

### A.6 Monitoring

Monitoring starts automatically with the application (`docker compose up --build`). No extra flags needed.

| Tool | URL | Credentials |
|------|-----|-------------|
| Grafana | `http://localhost:3000` | admin / admin |
| Prometheus | `http://localhost:9090` | — |

Grafana opens with a pre-built **Wallet Operations** dashboard showing the three operational alerts (money flow success ratio, internal error rate, provider degradation) and a live log panel. See **[RUNBOOK.md](docs/RUNBOOK.md)** for alert thresholds and playbooks.

## Option B: Local setup for active development — app on the host, Postgres in Docker

**Option B** is only this: the **API runs on your computer** (**JDK 21** on the host; use the repo’s **Gradle wrapper** — no global Gradle install); **Postgres is the only piece started with Docker**. Suited for day-to-day development and debugging.

**Run the app**

```console
~/wallet $ docker compose up postgres -d
```

Then from the repo root, start the API with the Gradle wrapper (**bootRun**):

> ~/wallet $ ./gradlew bootRun

`local` and default Spring profiles use Postgres at **`localhost:5433`** (`application.yml` and `docker-compose.yml` port **5433:5432**). Start the Postgres container **before** the app or Flyway fails with `Connection refused`.

From IntelliJ (alternative): **Run Configuration** → active profiles **local** or empty; run **WalletApplication**. Do **not** use profile **`docker`** on the host — that is for the API container in Compose (hostname **`postgres`**, not **`localhost`**).

**Troubleshooting (rare):** With default **`local`** / **bootRun** via the wrapper you should not need this — the URL is already **`localhost:5433`**. If something still forces **`5432`**, remove an override such as **`SPRING_DATASOURCE_URL`** from the IDE run config or shell (or set it to **`…localhost:5433…`**).

**Run tests** (on the host, same JDK)

With Postgres up as above:

> ~/wallet $ ./gradlew test

One class or pattern: same command with **--tests** *FullyQualifiedClass* or **--tests** *'Pattern'* (quote glob characters). For the **containerized** test suite (no JDK on the machine), use **A.3** instead.

## Architecture

### Layers

![Architecture Layers](docs/images/architecture-layers.png)

*Hexagonal-lite: the domain and application core (command handlers, query services, ports) has zero infrastructure dependencies. Controllers, JDBC repositories, FX provider, and payout worker are adapters wired at the edges.*

### Key Flows

#### Deposit

![Deposit Flow](docs/images/deposit-flow.png)

#### Withdrawal & Async Payout

![Withdrawal & Payout Flow](docs/images/withdrawal-payout-flow.png)

*The ledger debit commits immediately in the same transaction as the outbox entry. The payout worker picks up the row asynchronously, calls the provider, and retries with exponential backoff. On retry exhaustion a `WITHDRAWAL_REVERSAL` ledger entry restores the balance automatically — no manual intervention needed.*

#### P2P Transfer (same-currency and cross-currency)

![Transfer Flow](docs/images/transfer-flow.png)

*`toCurrency` is optional. When supplied, a live FX rate is fetched at commit time and the sender is debited in their currency while the recipient is credited in theirs — in one atomic transaction.*

#### FX Quote & Exchange

![FX Quote & Exchange Flow](docs/images/fx-quote-exchange-flow.png)

*Two-step model: `POST /fx/quotes` locks a rate with a configurable TTL (default 30 s). `POST /fx/exchanges` atomically consumes the quote (FOR UPDATE) and posts the ledger entry. The same key + same body replays safely; a different body on the same key is rejected.*

---

## License

MIT
