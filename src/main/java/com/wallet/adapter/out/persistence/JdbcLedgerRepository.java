package com.wallet.adapter.out.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wallet.application.port.out.LedgerRepository;
import com.wallet.application.port.out.LedgerRepository.LedgerEntryHistoryItem;
import com.wallet.application.port.out.LedgerRepository.LedgerEntryHistoryLine;
import com.wallet.domain.idempotency.LedgerEntryIdempotency;
import com.wallet.domain.ledger.LedgerLine;
import com.wallet.domain.ledger.LedgerLine.PostingBalanceType;
import com.wallet.domain.ledger.TransactionCursor;
import com.wallet.infrastructure.exception.IdempotencyKeyConflictException;
import com.wallet.infrastructure.exception.LedgerInvariantViolationException;
import com.wallet.infrastructure.exception.InsufficientFundsException;
import com.wallet.infrastructure.idempotency.IdempotencyFingerprintPolicy;
import java.math.BigDecimal;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import com.wallet.infrastructure.id.UuidV7Generator;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcLedgerRepository implements LedgerRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcLedgerRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<LedgerEntryIdempotency> findIdempotencyByScopedKey(String scopedIdempotencyKey) {
        List<LedgerEntryIdempotency> matches = jdbc.query(
            """
            SELECT id, idempotency_fingerprint FROM ledger_entries WHERE idempotency_key = :scopedKey LIMIT 1
            """,
            new MapSqlParameterSource("scopedKey", scopedIdempotencyKey),
            (rs, i) -> new LedgerEntryIdempotency(
                rs.getObject("id", UUID.class),
                rs.getString("idempotency_fingerprint")
            )
        );
        return matches.stream().findFirst();
    }

    /*
     * The single write path for all money movements in this wallet.
     *
     * Each call posts one ledger_entries row + N ledger_lines rows (one per affected
     * user+currency line) and updates every touched balance row (all inside the caller's
     * DB transaction).
     *
     * Example — P2P transfer of 100 USD from Tony Stark to Steve Rogers:
     *
     *   Note: `tonyId` / `steveId` below are readable placeholders for real UUID values.
     *
     *   lines = [
     *     LedgerLine.available(tonyId,  "USD", new BigDecimal("-100")),   // debit  Tony Stark
     *     LedgerLine.available(steveId, "USD", new BigDecimal("+100")),   // credit Steve Rogers
     *   ]
     *
     * Posting balance type concept used below (chosen by the calling use case/service, not by this repository):
     *   - AVAILABLE: a positive line increases spendable `amount` immediately
     *   - PENDING:   a positive line increases `pending_amount` (not spendable until settlement)
     *
     * Typical cases:
     *   - Regular P2P transfer: recipient line is AVAILABLE (funds are immediately usable).
     *   - Batch/payroll-style transfer: recipient line can be PENDING until an explicit settle step.
     *   - Deposits / FX buy lines: credit line is typically AVAILABLE.
     *
     * Step-by-step execution:
     *
     *   Step 1 — Classify each line into one of three buckets:
     *     - amount < 0                          -> debitBuckets
     *     - amount > 0 and postingBalanceType=AVAILABLE -> availableCreditBuckets
     *     - amount > 0 and postingBalanceType=PENDING   -> pendingCreditBuckets
     *     Amounts are summed per (userId, currency) within each bucket.
     *     A key must not appear in both debit and credit buckets (enforced; see guard after loop).
     *     Result: debitBuckets { tonyId|USD → -100 }, availableCreditBuckets { steveId|USD → +100 }
     *
     *   Step 2+3 — Ensure all rows exist, lock all in sorted order, then validate debits (table: balance_projections):
     *     - for every key (debit AND credit, in sorted UUID order), ensure the row exists, then lock
     *       it with SELECT ... FOR UPDATE and read the current balance
     *     - locking both sides together in sorted order prevents the A→B / B→A deadlock cycle:
     *       concurrent opposing transfers both contend for the same first key, so one blocks while
     *       the other proceeds — no cycle can form
     *     - reject immediately if any computed debit result would make balance negative
     *     - e.g. locked/read row before debit validation:
     *       | user_id | currency |  amount | pending_amount | version |
     *       |---------|----------|---------|----------------|---------|
     *       | tonyId  | USD      | 1000.00 | 0.00           | 40      |
     *
     *   Step 3 — (merged into Step 2 above — row creation and locking happen in one pass)
     *     - e.g. row created on first credit for a new user/currency:
     *       | user_id | currency | amount | pending_amount | version |
     *       |---------|----------|--------|----------------|---------|
     *       | steveId | USD      | 0.00   | 0.00           | 0       |
     *
     *   Step 4 — Insert ledger entry (table: ledger_entries):
     *     - INSERT into ledger_entries; UNIQUE idempotency_key is the DB double-spend guard
     *     - e.g. persisted row:
     *       | id | idempotency_key | entry_type |     metadata      | idempotency_fingerprint | correlation_id |      created_at      |
     *       |----|-----------------|------------|-------------------|-------------------------|----------------|----------------------|
     *       | e1 | tonyId|k-123    | TRANSFER   | {"channel":"api"} | fp-9ab...               | req-42         | 2026-04-25T23:01:00Z |
     *
     *   Step 5 — Insert ledger lines (table: ledger_lines):
     *     - one row per line (e.g., Tony -100 USD, Steve +100 USD)
     *     - e.g. persisted rows:
     *       | id | entry_id | user_id | currency |  amount |
     *       |----|----------|---------|----------|---------|
     *       | l1 | e1       | tonyId  | USD      | -100.00 |
     *       | l2 | e1       | steveId | USD      | +100.00 |
     *
     *   Step 6 — Apply debit updates (table: balance_projections):
     *     - UPDATE balance_projections.amount to each pre-validated value from Step 2
     *     - e.g. row after update:
     *       | user_id | currency | amount | pending_amount | version |
     *       |---------|----------|--------|----------------|---------|
     *       | tonyId  | USD      | 900.00 | 0.00           | 41      |
     *
     *   Step 7 — Apply credit increments (table: balance_projections):
     *     - if postingBalanceType=AVAILABLE: UPDATE amount = amount + :delta
     *     - if postingBalanceType=PENDING:   UPDATE pending_amount = pending_amount + :delta
     *     - regular P2P transfers use AVAILABLE for the recipient credit, so they update `amount`
     *       directly in this step (they do not wait for settlement)
     *     - e.g. row after available credit:
     *       | user_id | currency |  amount | pending_amount | version |
     *       |---------|----------|---------|----------------|---------|
     *       | steveId | USD      | 100.00  | 0.00           | 1       |
     *     - alternative e.g. row after pending credit:
     *       | user_id | currency |  amount | pending_amount | version |
     *       |---------|----------|---------|----------------|---------|
     *       | steveId | USD      | 0.00    | 100.00         | 1       |
     *     - atomic single-row updates; no prior read lock is needed (increments cannot overdraft)
     *     - concurrent writers still serialize on the row lock, but lock hold time is shorter than
     *       debit read-validate-write flow
     *
     * Idempotency collision path:
     *   If Step 4 collides on unique idempotency_key, return the existing entry id (safe replay)
     *   or throw IdempotencyKeyConflictException if the body fingerprint changed (→ 409).
     */
    @Override
    public PostLedgerEntryResult postLedgerEntry(
        String scopedIdempotencyKey,
        String entryType,
        List<LedgerLine> lines,
        Map<String, Object> metadata,
        String idempotencyFingerprint,
        String correlationId
    ) {
        // Step 1 — bucket lines directly by sign and credit type.
        // Three separate maps so available and pending credits for the same (user, currency)
        // are never collapsed into one bucket (which would lose the split semantics).
        Map<String, BigDecimal> debitBuckets = new LinkedHashMap<>();
        Map<String, BigDecimal> availableCreditBuckets = new LinkedHashMap<>();
        Map<String, BigDecimal> pendingCreditBuckets = new LinkedHashMap<>();
        // line e.g. LedgerLine.available(steveId, "USD", new BigDecimal("+100.00"))
        for (LedgerLine line : lines) {
            // userCurrencyBucketKey e.g. "f47ac10b-58cc-4372-a567-0e02b2c3d479|USD"
            String userCurrencyBucketKey = userCurrencyKey(line.userId(), line.currency());
            if (line.amount().compareTo(BigDecimal.ZERO) < 0) {
                BigDecimal updatedDebitTotal = debitBuckets.getOrDefault(userCurrencyBucketKey, BigDecimal.ZERO)
                    .add(line.amount());
                debitBuckets.put(userCurrencyBucketKey, updatedDebitTotal);
            } else if (line.amount().compareTo(BigDecimal.ZERO) > 0) {
                if (line.postingBalanceType() == PostingBalanceType.PENDING) {
                    BigDecimal updatedPendingCreditTotal = pendingCreditBuckets
                        .getOrDefault(userCurrencyBucketKey, BigDecimal.ZERO)
                        .add(line.amount());
                    pendingCreditBuckets.put(userCurrencyBucketKey, updatedPendingCreditTotal);
                } else {
                    BigDecimal updatedAvailableCreditTotal = availableCreditBuckets
                        .getOrDefault(userCurrencyBucketKey, BigDecimal.ZERO)
                        .add(line.amount());
                    availableCreditBuckets.put(userCurrencyBucketKey, updatedAvailableCreditTotal);
                }
            }
            // zero-amount lines are no-ops; skipped
        }
        // e.g. after loop: debitBuckets {tonyId|USD=-100.00}, availableCreditBuckets {steveId|USD=+100.00}, pendingCreditBuckets {}

        // Guard: one (userId, currency) key cannot appear in both debit and credit buckets.
        // If it does, this is a server-side programming invariant breach, so we throw LedgerInvariantViolationException.
        for (String userCurrencyBucketKey : debitBuckets.keySet()) {
            if (availableCreditBuckets.containsKey(userCurrencyBucketKey) || pendingCreditBuckets.containsKey(userCurrencyBucketKey)) {
                throw new LedgerInvariantViolationException(
                    "Entry mixes debit and credit for the same user+currency key: " + userCurrencyBucketKey
                );
            }
        }

        // Steps 2+3 — lock ALL affected rows (debit + credit) in a single sorted pass, then validate debits.
        // Locking both sides together in the same sorted order is the deadlock fix:
        // concurrent A→B and B→A both attempt {A,B} in the same UUID-sorted order, so one waits
        // for the other rather than forming a cycle.
        Map<String, BigDecimal> newDebitBalances = lockAllAndValidateDebits(
            debitBuckets, availableCreditBuckets, pendingCreditBuckets);

        UUID entryId = UuidV7Generator.next();
        Map<String, Object> storedMetadata = new LinkedHashMap<>(metadata);
        // correlationId links logs/audit/ledger rows for the same request flow (traceability, not idempotency).
        if (correlationId != null && !correlationId.isEmpty()) {
            storedMetadata.putIfAbsent("correlationId", correlationId);
        }
        String metadataJson = toJson(storedMetadata);
        String storedCorrelationId = correlationId == null ? "" : correlationId;

        // Step 4 — insert the parent ledger entry.
        // ON CONFLICT DO NOTHING avoids aborting the SQL transaction on idempotency races.
        boolean inserted = insertLedgerEntryRow(
            entryId, scopedIdempotencyKey, entryType, metadataJson, idempotencyFingerprint, storedCorrelationId
        );
        if (!inserted) {
            return new PostLedgerEntryResult.Replayed(
                handleDuplicateKey(scopedIdempotencyKey, idempotencyFingerprint)
            );
        }
        // e.g. ledger_entries row: (id=e1, idempotency_key=tonyId|k-123, entry_type=TRANSFER)

        // Step 5 — insert one ledger_lines row per line.
        insertLedgerLines(entryId, lines);
        // e.g. ledger_lines rows: (entry_id=e1, user_id=tonyId, amount=-100.00) + (entry_id=e1, user_id=steveId, amount=+100.00)

        // Step 6 — write pre-validated debit balances.
        applyDebitBalances(newDebitBalances);
        // e.g. balance_projections debit row update: tonyId|USD amount 1000.00 -> 900.00

        // Step 7 — atomically increment credit balances (no prior lock needed).
        applyAvailableCreditIncrements(availableCreditBuckets);
        applyPendingCreditIncrements(pendingCreditBuckets);
        // e.g. balance_projections credit row update: steveId|USD amount +100.00 (AVAILABLE) (or pending_amount +100.00 for (PENDING) flows)

        return new PostLedgerEntryResult.Created(entryId);
    }

    /*
     * Locks ALL affected balance rows (debit + credit) in a single sorted-key pass, then validates
     * that no debit would push any balance below zero. Returns the exact new balance for each debit bucket.
     *
     * Why all rows, not just debits:
     *   Credit UPDATEs also acquire implicit row locks. If TX1 holds a debit lock on A and then
     *   tries to UPDATE B (credit), while TX2 holds a debit lock on B and tries to UPDATE A
     *   (credit), a classic A→B / B→A deadlock forms. Locking both sides in the same UUID-sorted
     *   order means both transactions compete for the same first key — one blocks, the other
     *   completes cleanly. No cycle is possible.
     */
    private Map<String, BigDecimal> lockAllAndValidateDebits(
        Map<String, BigDecimal> debitBuckets,
        Map<String, BigDecimal> availableCreditBuckets,
        Map<String, BigDecimal> pendingCreditBuckets
    ) {
        List<String> sortedAllKeys = Stream.of(
                debitBuckets.keySet(),
                availableCreditBuckets.keySet(),
                pendingCreditBuckets.keySet())
            .flatMap(Collection::stream)
            .distinct()
            .sorted(Comparator.naturalOrder())
            .collect(Collectors.toList());

        if (sortedAllKeys.isEmpty()) {
            return Map.of();
        }

        Map<String, BigDecimal> lockedBalances = new LinkedHashMap<>();
        for (String key : sortedAllKeys) {
            UserCurrency uc = UserCurrency.parse(key);
            createBalanceRowIfNotExists(uc.userId(), uc.currency());
            lockedBalances.put(key, lockAndLoadCurrentBalance(uc));
        }

        Map<String, BigDecimal> newDebitBalances = new LinkedHashMap<>();
        for (String key : debitBuckets.keySet()) {
            BigDecimal newBalance = lockedBalances.get(key).add(debitBuckets.get(key));
            if (newBalance.compareTo(BigDecimal.ZERO) < 0) {
                UserCurrency uc = UserCurrency.parse(key);
                throw new InsufficientFundsException(
                    "Insufficient funds for " + uc.currency() + " (balance would be " + newBalance + ")"
                );
            }
            newDebitBalances.put(key, newBalance);
        }
        return newDebitBalances;
    }

    private BigDecimal lockAndLoadCurrentBalance(UserCurrency userCurrency) {
        return jdbc.queryForObject(
            """
            SELECT amount
            FROM balance_projections
            WHERE user_id = :userId AND currency = :currencyCode
            FOR UPDATE
            """,
            new MapSqlParameterSource(Map.of(
                "userId", userCurrency.userId(),
                "currencyCode", userCurrency.currency()
            )),
            BigDecimal.class
        );
    }

    private boolean insertLedgerEntryRow(
        UUID entryId,
        String scopedIdempotencyKey,
        String entryType,
        String metadataJson,
        String idempotencyFingerprint,
        String storedCorrelationId
    ) {
        SqlParameterSource params = new MapSqlParameterSource(
            Map.of(
                "id", entryId,
                "scopedIdempotencyKey", scopedIdempotencyKey,
                "type", entryType,
                "metadataJson", new SqlParameterValue(Types.OTHER, metadataJson),
                "idempotencyFingerprint", idempotencyFingerprint,
                "correlationId", storedCorrelationId
            )
        );
        int insertedRows = jdbc.update(
            """
            INSERT INTO ledger_entries (id, idempotency_key, entry_type, metadata, idempotency_fingerprint, correlation_id)
            VALUES (
                :id,
                :scopedIdempotencyKey,
                :type,
                CAST(:metadataJson AS jsonb),
                :idempotencyFingerprint,
                :correlationId
            )
            ON CONFLICT (idempotency_key) DO NOTHING
            """,
            params
        );
        return insertedRows == 1;
    }

    // One ledger_lines row per line — e.g. transfer: (Tony Stark, USD, -100) and (Steve Rogers, USD, +100).
    private void insertLedgerLines(UUID entryId, List<LedgerLine> lines) {
        for (LedgerLine line : lines) {
            jdbc.update(
                """
                INSERT INTO ledger_lines (id, entry_id, user_id, currency, amount)
                VALUES (:id, :entryId, :userId, :currencyCode, :amount)
                """,
                new MapSqlParameterSource(Map.of(
                    "id", UuidV7Generator.next(),
                    "entryId", entryId,
                    "userId", line.userId(),
                    "currencyCode", line.currency(),
                    "amount", line.amount()
                ))
            );
        }
    }

    // Writes pre-validated new amounts for debit rows (exact value, already validated no-negative).
    private void applyDebitBalances(Map<String, BigDecimal> newDebitBalances) {
        for (Map.Entry<String, BigDecimal> entry : newDebitBalances.entrySet()) {
            UserCurrency userCurrency = UserCurrency.parse(entry.getKey());
            jdbc.update(
                """
                UPDATE balance_projections SET amount = :amount, version = version + 1
                WHERE user_id = :userId AND currency = :currencyCode
                """,
                new MapSqlParameterSource(Map.of(
                    "amount", entry.getValue(),
                    "userId", userCurrency.userId(),
                    "currencyCode", userCurrency.currency()
                ))
            );
        }
    }

    // Atomically increments available balance — no prior read-lock needed because credits cannot overdraft.
    private void applyAvailableCreditIncrements(Map<String, BigDecimal> creditBuckets) {
        for (Map.Entry<String, BigDecimal> entry : creditBuckets.entrySet()) {
            UserCurrency userCurrency = UserCurrency.parse(entry.getKey());
            jdbc.update(
                """
                UPDATE balance_projections SET amount = amount + :delta, version = version + 1
                WHERE user_id = :userId AND currency = :currencyCode
                """,
                new MapSqlParameterSource(Map.of(
                    "delta", entry.getValue(),
                    "userId", userCurrency.userId(),
                    "currencyCode", userCurrency.currency()
                ))
            );
        }
    }

    // Atomically increments pending balance — funds are not spendable until settled via SettlementCommandHandler.
    private void applyPendingCreditIncrements(Map<String, BigDecimal> pendingBuckets) {
        for (Map.Entry<String, BigDecimal> entry : pendingBuckets.entrySet()) {
            UserCurrency userCurrency = UserCurrency.parse(entry.getKey());
            jdbc.update(
                """
                UPDATE balance_projections SET pending_amount = pending_amount + :delta, version = version + 1
                WHERE user_id = :userId AND currency = :currencyCode
                """,
                new MapSqlParameterSource(Map.of(
                    "delta", entry.getValue(),
                    "userId", userCurrency.userId(),
                    "currencyCode", userCurrency.currency()
                ))
            );
        }
    }

    // Called when the idempotency_key unique constraint fires.
    // Same key + same fingerprint → safe replay. Same key + different fingerprint → 409.
    private UUID handleDuplicateKey(String scopedIdempotencyKey, String idempotencyFingerprint) {
        LedgerEntryIdempotency existing = findIdempotencyByScopedKey(scopedIdempotencyKey).orElseThrow();
        if (!IdempotencyFingerprintPolicy.storedMatchesRequest(existing.idempotencyFingerprint(), idempotencyFingerprint)) {
            throw new IdempotencyKeyConflictException();
        }
        return existing.ledgerEntryId();
    }

    // Patches the metadata JSON of an existing entry — used by WithdrawalCommandHandler after the provider call.
    @Override
    public void mergeEntryMetadata(UUID ledgerEntryId, Map<String, Object> metadataPatch) {
        jdbc.update(
            """
            UPDATE ledger_entries
            SET metadata = COALESCE(metadata, '{}'::jsonb) || CAST(:metadataPatch AS jsonb)
            WHERE id = :ledgerEntryId
            """,
            Map.of("metadataPatch", toJson(metadataPatch), "ledgerEntryId", ledgerEntryId)
        );
    }

    // Inserts a zero-balance row if this is the first time this user touches this currency.
    private void createBalanceRowIfNotExists(UUID userId, String currency) {
        jdbc.update(
            """
            INSERT INTO balance_projections (user_id, currency, amount, pending_amount, version)
            VALUES (:userId, :currencyCode, 0, 0, 0)
            ON CONFLICT (user_id, currency) DO NOTHING
            """,
            new MapSqlParameterSource(Map.of("userId", userId, "currencyCode", currency))
        );
    }

    private String toJson(Map<String, Object> metadata) {
        try {
            if (metadata == null || metadata.isEmpty()) return "{}";
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException jsonProcessingException) {
            throw new IllegalStateException(jsonProcessingException);
        }
    }

    @Override
    public List<LedgerEntryHistoryItem> listTransactionsForUser(UUID userId, int limit, @Nullable TransactionCursor cursor) {
        MapSqlParameterSource params = new MapSqlParameterSource("userId", userId)
            .addValue("limit", limit);

        // Cursor-based pagination with UUIDv7: fetch entries with a smaller entry id.
        String afterCursorFilter = "";
        if (cursor != null) {
            afterCursorFilter = """
                AND e.id < :cutoffEntryId
                """;
            params.addValue("cutoffEntryId", cursor.entryId());
        }

        // First page the ledger_entries that belong to this user; then re-join ledger_lines only
        // for those entries so we never pull more lines than needed.
        List<TransactionPageItemRow> rows = jdbc.query(
            """
            WITH selected_entries AS (
              SELECT e.id, e.entry_type, e.created_at
              FROM ledger_entries e
              INNER JOIN ledger_lines l ON l.entry_id = e.id
              WHERE l.user_id = :userId
            """
                + afterCursorFilter
                + """
              GROUP BY e.id, e.entry_type, e.created_at
              ORDER BY e.id DESC
              LIMIT :limit
            )
            SELECT
              se.id AS entry_id,
              se.entry_type,
              se.created_at,
              l.currency,
              l.amount
            FROM selected_entries se
            INNER JOIN ledger_lines l
              ON l.entry_id = se.id
             AND l.user_id = :userId
            ORDER BY se.id DESC, l.currency ASC
            """,
            params,
            (rs, i) -> new TransactionPageItemRow(
                rs.getObject("entry_id", UUID.class),
                rs.getString("entry_type"),
                rs.getTimestamp("created_at").toInstant().toString(),
                rs.getString("currency"),
                rs.getBigDecimal("amount")
            )
        );

        return groupRowsIntoLedgerEntryHistory(rows);
    }

    // Each DB row is one (entry, currency, amount) tuple; groups into one LedgerEntryHistoryItem per entry.
    private List<LedgerEntryHistoryItem> groupRowsIntoLedgerEntryHistory(List<TransactionPageItemRow> rows) {
        Map<UUID, LedgerEntryHistoryGroup> groupsByEntryId = new LinkedHashMap<>();
        for (TransactionPageItemRow row : rows) {
            LedgerEntryHistoryGroup group = groupsByEntryId.computeIfAbsent(
                row.entryId(),
                ignored -> new LedgerEntryHistoryGroup(row.entryType(), row.createdAtUtc(), new ArrayList<>())
            );
            group.lines().add(new LedgerEntryHistoryLine(row.currency(), row.amount()));
        }

        List<LedgerEntryHistoryItem> result = new ArrayList<>();
        for (Map.Entry<UUID, LedgerEntryHistoryGroup> e : groupsByEntryId.entrySet()) {
            LedgerEntryHistoryGroup group = e.getValue();
            result.add(new LedgerEntryHistoryItem(e.getKey(), group.entryType(), group.createdAtUtc(), group.lines()));
        }
        return result;
    }

    @Override
    public Map<String, BigDecimal> loadBalances(UUID userId) {
        return jdbc.query(
            "SELECT currency, amount FROM balance_projections WHERE user_id = :userId AND amount != 0",
            new MapSqlParameterSource("userId", userId),
            (rs, i) -> Map.entry(rs.getString("currency"), rs.getBigDecimal("amount"))
        ).stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    @Override
    public Map<String, BigDecimal> loadPendingBalances(UUID userId) {
        return jdbc.query(
            "SELECT currency, pending_amount FROM balance_projections WHERE user_id = :userId AND pending_amount != 0",
            new MapSqlParameterSource("userId", userId),
            (rs, i) -> Map.entry(rs.getString("currency"), rs.getBigDecimal("pending_amount"))
        ).stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    @Override
    public Map<String, BigDecimal> settleAllPending(UUID userId) {
        // CTE locks the rows and captures pending_amount values before the UPDATE fires,
        // so RETURNING reports exactly what was moved — no separate read, no race window.
        List<Map.Entry<String, BigDecimal>> settled = jdbc.query(
            """
            WITH to_settle AS (
              SELECT currency, pending_amount
              FROM balance_projections
              WHERE user_id = :userId AND pending_amount > 0
              FOR UPDATE
            )
            UPDATE balance_projections
            SET amount        = balance_projections.amount + to_settle.pending_amount,
                pending_amount = 0,
                version        = balance_projections.version + 1
            FROM to_settle
            WHERE balance_projections.user_id = :userId
              AND balance_projections.currency = to_settle.currency
            RETURNING to_settle.currency, to_settle.pending_amount AS settled_amount
            """,
            new MapSqlParameterSource("userId", userId),
            (rs, i) -> Map.entry(rs.getString("currency"), rs.getBigDecimal("settled_amount"))
        );
        return settled.stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    // In-memory map key: "f47ac10b-...-0e02|USD" — used only for ordering locks and grouping deltas.
    private static String userCurrencyKey(UUID userId, String currency) {
        return userId + "|" + currency;
    }

    // Inverse of userCurrencyKey: splits on the first "|".
    private record UserCurrency(UUID userId, String currency) {
        static UserCurrency parse(String userCurrencyBucketKey) {
            String[] userCurrencyParts = userCurrencyBucketKey.split("\\|", 2);
            return new UserCurrency(UUID.fromString(userCurrencyParts[0]), userCurrencyParts[1]);
        }
    }

    // Holds the type, timestamp, and accumulated lines for one ledger entry while building the page result.
    private record LedgerEntryHistoryGroup(String entryType, String createdAtUtc, List<LedgerEntryHistoryLine> lines) {}

    // One flat DB result row: a single (entry, currency, amount) tuple from the SQL join.
    private record TransactionPageItemRow(UUID entryId, String entryType, String createdAtUtc, String currency, BigDecimal amount) {}
}
