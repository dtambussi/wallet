**Languages:** English (this file) · [Español — CHALLENGE.es.md](es/CHALLENGE.es.md)

# Technical challenge: cross-border multi-currency wallet

## 1. The scenario
You join a **cross-border** money platform. Users hold balances in multiple currencies and transfer funds to each other, sometimes with FX (foreign exchange) conversion.

**Critical context:**
* Real money — mistakes are expensive.
* External providers fail more often than we would like.
* We evaluate: **correctness, resilience under failure, and scalability.**

---

## 2. What to build
A backend service exposing an HTTP API with the following capabilities:

| Capability | Description / outcome |
| :--- | :--- |
| **Create user** | Persist the user and return a `User ID`. |
| **Create deposit** | Simulate an inbound deposit (amount + currency). |
| **Create withdrawal** | Simulate a withdrawal executed via a third-party API (mocked provider). |
| **Get balances** | Return a user’s balances across all currencies. |
| **Transaction history** | List a user’s movements (**must be paginated**). |
| **Quote exchange** | Record a quote with a **short TTL (30s)**. |
| **Execute exchange** | Perform the conversion using the appropriate quote. |
| **Transfer between users** | Debit sender and credit receiver correctly. |

### Additional components
* **Mock FX provider:** a component that emits quotes for supported currencies.
* **Supported currencies:** `USD`, `ARS`, `CLP`, `BOB`, `BRL`.

---

## 3. What we evaluate
We look for a solution that shows understanding of operating real money. Surfacing problems is part of the exercise.

**Main axes:**
1. **State consistency:** balance integrity (prevent double spend, race conditions).
2. **Fault tolerance:** strategy when external providers fail.
3. **Auditability:** a clear trail for every movement.
4. **Senior-level guarantees:** not only the *happy path*, but how the system holds up under abuse or failure.

---

## 4. Mandatory deliverables

### 📄 DESIGN.md (max. 3 pages)
* Guarantees the system provides and how they are achieved.
* Data model and reasoning behind it.
* Failure modes considered vs. deliberate non-goals.
* **Evolution:** how design changes from **10K → 1M → 10M** users.

### 📄 DECISIONS.md (decision log)
* Record each non-obvious decision.
* Rejected alternatives and a one-line “why”.

### 💻 Source code
* Language / framework / database of your choice.
* **README:** setup and how to run tests.
* **Requirement:** must run locally with a single command (e.g. `docker compose up`).

### 🧪 Guarantee tests
* More than trivial unit tests. They must exercise the guarantees in [DESIGN.md](DESIGN.md) under stress or failure conditions.

### 📑 RUNBOOK.md
* Key metrics to export.
* **Top 3 alerts:** thresholds and why they would page an engineer.
* **Playbook:** response guide for the noisiest alert.

---

## 5. Timeline and submission
* **Deadline:** 7 calendar days.
* **Philosophy:** *“We prefer a smaller scope with sharp reasoning over a larger scope with vagueness.”* If you cut a feature to protect quality elsewhere, document it in [DESIGN.md](DESIGN.md).
