package ai.neargo.shop.pay.points.recon.impl;

import ai.neargo.shop.pay.points.event.PointsAttentionService;
import ai.neargo.shop.pay.points.model.Bucket;
import ai.neargo.shop.pay.points.model.PointsKeys;
import ai.neargo.shop.pay.points.model.PositionMove;
import ai.neargo.shop.pay.points.recon.PointsReconService;
import ai.neargo.shop.pay.points.store.PointsConfigurationStore;
import ai.neargo.shop.pay.points.store.PointsOperationsStore;
import ai.neargo.shop.pay.points.store.PointsOperationsStore.Kind;
import ai.neargo.shop.pay.points.store.PointsReadStore;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** One type/pool lock gives a coherent read. No difference is repaired by this service. */
@Service
@Transactional("payTxManager")
public class PointsReconServiceImpl implements PointsReconService {
    private final PointsConfigurationStore config;
    private final PointsOperationsStore store;
    private final PointsReadStore read;
    private final PointsAttentionService attention;
    private final ObjectMapper json = new ObjectMapper();

    public PointsReconServiceImpl(
            PointsConfigurationStore config,
            PointsOperationsStore store,
            PointsReadStore read,
            PointsAttentionService attention) {
        this.config = config;
        this.store = store;
        this.read = read;
        this.attention = attention;
    }

    @Override
    public Report scan(String type, long now) {
        if (now < 0) {
            throw new IllegalArgumentException("POINTS_RECON_TIME_INVALID");
        }
        config.lockTypeAndPool(type);
        var errors = new ArrayList<Difference>();
        boolean complete = true;
        try {
            inspect(type, errors);
        } catch (ScanLimit limit) {
            complete = false;
            errors.add(new Difference("SCAN_LIMIT", limit.getMessage(), 10000, 10001));
        }
        var report = new Report(type, now, complete, errors);
        var day = Instant.ofEpochMilli(now).atZone(ZoneId.of("Asia/Shanghai")).toLocalDate();
        store.saveStat(
                type,
                day,
                "RECON",
                report.balanced() ? "BALANCED" : "DIFFERENCE",
                json.writeValueAsString(report));
        for (var difference : errors.stream().limit(20).toList()) {
            attention.raise(
                    type,
                    difference.code(),
                    difference.reference(),
                    difference.expected(),
                    difference.actual(),
                    now);
        }
        return report;
    }

    private void inspect(String type, List<Difference> errors) {
        var definition = read.type(type).orElseThrow();
        var pool = read.pool(type);
        var accounts = rows(type, Kind.ACCOUNT);
        var entries = rows(type, Kind.ENTRY);
        var flows = rows(type, Kind.FLOW);
        var vouchers = rows(type, Kind.VOUCHER);
        var grants = rows(type, Kind.GRANT);
        var redemptions = rows(type, Kind.REDEEM);
        var refunds = rows(type, Kind.REFUND);
        var payouts = rows(type, Kind.PAYOUT);
        var transfers = rows(type, Kind.TRANSFER);
        var reserves = rows(type, Kind.RESERVE);
        var debts = rows(type, Kind.RECEIVABLE);
        var actual = new HashMap<String, Long>();
        long total = 0, issue = 0, circulating = 0, refundReserved = 0;
        for (var a : accounts) {
            long sum = 0;
            for (Bucket bucket : Bucket.values()) {
                long amount = n(a, bucket.column());
                sum = Math.addExact(sum, amount);
                actual.put(
                        cell(text(a, "holder_type"), text(a, "holder_no"), bucket.name()), amount);
                if (!"MINT".equals(a.get("holder_type")) && amount < 0) {
                    errors.add(
                            new Difference(
                                    "NEGATIVE_ACCOUNT",
                                    text(a, "holder_no") + ":" + bucket,
                                    0,
                                    amount));
                }
            }
            total = Math.addExact(total, sum);
            if ("ISSUE".equals(a.get("holder_type"))) {
                issue = Math.addExact(issue, sum);
            }
            if (Set.of("USER", "STORE").contains(a.get("holder_type"))) {
                circulating = Math.addExact(circulating, sum);
            }
            if ("RECEIVE".equals(a.get("holder_type"))) {
                refundReserved += n(a, "refund_reserved_points");
            }
        }
        check(errors, "V1_TOTAL", "ACCOUNTS", 0, total);
        check(errors, "V2_U", "U", definition.rate().toMinor(issue), pool.u());
        check(errors, "V3_C", "C", definition.rate().toMinor(circulating), pool.c());
        check(
                errors,
                "V4_CASH",
                "U+C+D+B",
                store.evidenceTotal(type),
                Math.addExact(
                        Math.addExact(pool.u(), pool.c()), Math.addExact(pool.d(), pool.b())));
        var voucherIds = new HashSet<String>();
        vouchers.forEach(v -> voucherIds.add(text(v, "voucher_no")));
        var ledger = new HashMap<String, Long>();
        var voucherPoints = new HashMap<String, Long>();
        for (var entry : entries) {
            if (!voucherIds.contains(text(entry, "voucher_no"))) {
                errors.add(new Difference("ORPHAN_LEDGER", text(entry, "entry_no"), 1, 0));
            }
            add(
                    ledger,
                    cell(
                            text(entry, "holder_type"),
                            text(entry, "holder_no"),
                            text(entry, "bucket")),
                    n(entry, "delta_points"));
            add(voucherPoints, text(entry, "voucher_no"), n(entry, "delta_points"));
        }
        var cells = new HashSet<>(actual.keySet());
        cells.addAll(ledger.keySet());
        for (String cell : cells) {
            check(
                    errors,
                    "ACCOUNT_LEDGER",
                    cell,
                    ledger.getOrDefault(cell, 0L),
                    actual.getOrDefault(cell, 0L));
        }
        voucherPoints.forEach((voucher, sum) -> check(errors, "V1_VOUCHER", voucher, 0, sum));
        var flowTotals = new HashMap<String, Long>();
        for (var flow : flows) {
            add(flowTotals, text(flow, "bucket"), n(flow, "delta_minor"));
        }
        Map.of("U", pool.u(), "C", pool.c(), "D", pool.d(), "B", pool.b())
                .forEach(
                        (bucket, balance) ->
                                check(
                                        errors,
                                        "POOL_FLOW",
                                        bucket,
                                        flowTotals.getOrDefault(bucket, 0L),
                                        balance));
        var grantIndex = index(grants, "position_no");
        var redeemIndex = index(redemptions, "position_no");
        for (var voucher : vouchers) {
            String ref = text(voucher, "biz_ref");
            if (ref.startsWith("GRANT:")) {
                String no = PointsKeys.position("G", type, ref.substring(6));
                if (!grantIndex.containsKey(no)) {
                    errors.add(new Difference("MISSING_GRANT_POSITION", ref, 1, 0));
                }
            }
        }
        var pendingBySub = new HashMap<String, JsonNode>();
        var expectedHeld = new HashMap<String, Long>();
        for (var row : refunds) {
            if (Set.of("CONFIRMED", "ABORTED").contains(row.get("state"))) {
                continue;
            }
            var snap = body(row);
            var command = snap.path("command");
            var prep = snap.path("preparation");
            pendingBySub.put(command.path("subOrderNo").asText(), snap);
            add(
                    expectedHeld,
                    command.path("userNo").asText(),
                    Math.addExact(
                            prep.path("reclaimedPending").longValue(),
                            prep.path("reclaimedAvailable").longValue()));
        }
        var expectedPending = new HashMap<String, Long>();
        for (var row : grants) {
            var snap = body(row);
            long original = n(row, "original_points");
            long activated = snap.path("activatedPoints").longValue(),
                    reclaimed = snap.path("reclaimedPending").longValue();
            if (activated < 0 || reclaimed < 0 || activated + reclaimed > original) {
                errors.add(
                        new Difference(
                                "V8_GRANT_COUNTER",
                                text(row, "position_no"),
                                original,
                                activated + reclaimed));
            }
            long pending = original - activated - reclaimed;
            var preparation = pendingBySub.get(text(row, "sub_order_no"));
            if (preparation != null) {
                pending -= preparation.path("preparation").path("reclaimedPending").longValue();
            }
            add(expectedPending, snap.path("userNo").asText(), pending);
        }
        long expectedRr = 0, expectedD = 0;
        var expectedStorePending = new HashMap<String, Long>();
        var expectedStoreAvailable = new HashMap<String, Long>();
        var reserveIndex = index(reserves, "position_no");
        for (var row : redemptions) {
            var snap = body(row);
            if (row.get("sub_order_no") == null) {
                for (var share : snap.path("outcome").path("shares")) {
                    if (share.path("points").longValue() > 0
                            && !redeemIndex.containsKey(
                                    PointsKeys.position(
                                            "R", type, share.path("subOrderNo").asText()))) {
                        errors.add(
                                new Difference(
                                        "MISSING_REDEEM_POSITION",
                                        share.path("subOrderNo").asText(),
                                        1,
                                        0));
                    }
                }
                continue;
            }
            long remaining =
                    Math.subtractExact(n(row, "original_points"), n(row, "processed_points"));
            String state = sourceState(row, pendingBySub, errors);
            if (remaining < 0) {
                errors.add(
                        new Difference(
                                "V7_REDEEM_COUNTER", text(row, "position_no"), 0, remaining));
            }
            if (Set.of("PAYABLE", "PAYING", "PAID").contains(state)) {
                expectedRr += remaining;
            }
            if (Set.of("PAYABLE", "PAYING").contains(state)) {
                expectedD += definition.rate().toMinor(remaining);
            }
            if ("HELD".equals(state)) {
                add(expectedHeld, snap.path("userNo").asText(), remaining);
            }
            if ("IN_STORE".equals(state)) {
                add(expectedStorePending, snap.path("share").path("storeNo").asText(), remaining);
            }
            if ("SETTLEABLE".equals(state)) {
                add(expectedStoreAvailable, snap.path("share").path("storeNo").asText(), remaining);
            }
            if (n(row, "original_points") > 0
                    && !reserveIndex.containsKey(text(row, "position_no"))) {
                errors.add(
                        new Difference("MISSING_REFUND_RESERVE", text(row, "position_no"), 1, 0));
            }
        }
        var expectedLocks = new HashMap<String, Long>();
        long lockedU = 0, lockedB = 0;
        for (var row : transfers) {
            var snap = body(row);
            String state = text(row, "state");
            if (snap.hasNonNull("expiryRevision") && !"SUCCEEDED".equals(state)) {
                add(expectedHeld, snap.path("userNo").asText(), snap.path("points").longValue());
            }
            if (!Set.of("SUCCEEDED", "FAILED").contains(state)) {
                if (snap.hasNonNull("receivableNo")) {
                    add(
                            expectedLocks,
                            snap.path("receivableNo").asText(),
                            snap.path("fundsCommand").path("amountMinor").longValue());
                }
                if (snap.hasNonNull("request")) {
                    var request = snap.path("request");
                    long amount = request.path("amountMinor").longValue();
                    if ("WITHDRAW_U".equals(request.path("action").asText())) {
                        lockedU += amount;
                    }
                    if ("WITHDRAW_B".equals(request.path("action").asText())) {
                        lockedB += amount;
                    }
                }
            }
        }
        var payoutIndex = index(payouts, "position_no");
        var normalizedStatementTickets=new HashMap<String,String>();
        for (var row:payouts) {
            var saved=body(row);
            String statement=saved.path("statementNo").asText(null);
            if (statement==null || "FAILED".equals(text(row,"state"))) continue;
            var head=read.position(PositionMove.Kind.STATEMENT.table(),statement);
            if (head==null) continue;
            var snapshot=json.readValue((String)head.get("payload_json"),
                    ai.neargo.shop.pay.points.statement.StoreStatementService.Snapshot.class);
            if (snapshot.sourceBuildNo()!=null && snapshot.current().number()==saved.path("statementRevision").longValue()) {
                normalizedStatementTickets.put(statement,text(row,"position_no"));
            }
        }
        for (var row : payouts) {
            var snap = body(row);
            var ticket = snap.path("ticket");
            String state = text(row, "state");
            long gross = ticket.path("gross").longValue();
            check(
                    errors,
                    "PAYOUT_AMOUNT",
                    text(row, "position_no"),
                    gross,
                    ticket.path("offset").longValue() + ticket.path("cash").longValue());
            check(
                    errors,
                    "PAYOUT_RATE",
                    text(row, "position_no"),
                    definition.rate().toMinor(n(row, "original_points")),
                    gross);
            if (!Set.of("SUCCEEDED", "FAILED").contains(state)) {
                for (var offset : snap.path("offsets")) {
                    add(
                            expectedLocks,
                            offset.path("receivableNo").asText(),
                            offset.path("amountMinor").longValue());
                }
            }
            if (!"FAILED".equals(state)) {
                for (var source : snap.path("command").path("positionIds")) {
                    var redemption = redeemIndex.get(source.asText());
                    if (redemption == null) {
                        errors.add(new Difference("MISSING_PAYOUT_SOURCE", source.asText(), 1, 0));
                        continue;
                    }
                    String actualState = sourceState(redemption, pendingBySub, errors);
                    boolean valid =
                            "SUCCEEDED".equals(state)
                                    ? Set.of("PAID", "CLOSED").contains(actualState)
                                    : "PAYING".equals(actualState);
                    if (!valid) {
                        errors.add(new Difference("PAYOUT_SOURCE_STATE", source.asText(), 1, 0));
                    }
                }
            }
        }
        for (var row : redemptions) {
            String ticket=text(row,"ticket_no");
            String statement=text(row,"statement_no");
            if (ticket.isBlank() && !statement.isBlank()) {
                ticket=normalizedStatementTickets.get(statement);
                // A fully refunded normalized source may close without a cash payout.
                if (ticket==null && n(row,"original_points")==n(row,"processed_points")) {
                    var head=read.position(PositionMove.Kind.STATEMENT.table(),statement);
                    if (head!=null && body(head).hasNonNull("sourceBuildNo")) continue;
                }
            }
            if (Set.of("PAYING", "PAID", "CLOSED").contains(sourceState(row, pendingBySub, errors))
                    && row.get("sub_order_no") != null && !payoutIndex.containsKey(ticket)) {
                errors.add(new Difference("MISSING_PAYOUT_TICKET", text(row, "position_no"), 1, 0));
            }
        }
        check(errors, "V6_REFUND_RESERVED", "RECEIVE", expectedRr, refundReserved);
        check(errors, "V7_UNPAID_D", "D", expectedD, pool.d());
        long totalReserved = 0;
        for (var reserve : reserves) {
            totalReserved += n(reserve, "remaining_minor");
            check(
                    errors,
                    "RESERVE_COUNTER",
                    text(reserve, "reserve_no"),
                    n(reserve, "original_minor"),
                    n(reserve, "remaining_minor")
                            + n(reserve, "consumed_minor")
                            + n(reserve, "released_minor"));
            if (!redeemIndex.containsKey(text(reserve, "position_no"))) {
                errors.add(new Difference("ORPHAN_RESERVE", text(reserve, "reserve_no"), 1, 0));
            }
        }
        check(errors, "RESERVED_B", "B", totalReserved, pool.reservedBMinor());
        if (pool.b() < pool.reservedBMinor() + pool.lockedBMinor()) {
            errors.add(
                    new Difference(
                            "B_CAPACITY",
                            "B",
                            pool.reservedBMinor() + pool.lockedBMinor(),
                            pool.b()));
        }
        check(errors, "LOCKED_U", "U", lockedU, pool.lockedUMinor());
        check(errors, "LOCKED_B", "B", lockedB, pool.lockedBMinor());
        var debtIndex = index(debts, "receivable_no");
        for (var debt : debts) {
            String no = text(debt, "receivable_no");
            check(
                    errors,
                    "R_LOCK",
                    no,
                    expectedLocks.getOrDefault(no, 0L),
                    n(debt, "locked_minor"));
            if (n(debt, "recovered_minor") + n(debt, "locked_minor") > n(debt, "original_minor")) {
                errors.add(
                        new Difference(
                                "R_COUNTER",
                                no,
                                n(debt, "original_minor"),
                                n(debt, "recovered_minor") + n(debt, "locked_minor")));
            }
        }
        expectedLocks.forEach(
                (no, amount) -> {
                    if (!debtIndex.containsKey(no)) {
                        errors.add(new Difference("MISSING_RECEIVABLE", no, amount, 0));
                    }
                });
        verifyBuckets(errors, accounts, "USER", "held_points", expectedHeld, "ORPHAN_USER_HOLD");
        verifyBuckets(errors, accounts, "USER", "pending_points", expectedPending, "V8_PENDING");
        verifyBuckets(
                errors, accounts, "STORE", "pending_points", expectedStorePending, "STORE_PENDING");
        verifyBuckets(
                errors,
                accounts,
                "STORE",
                "available_points",
                expectedStoreAvailable,
                "STORE_AVAILABLE");
    }

    private String sourceState(
            Map<String, Object> row, Map<String, JsonNode> refunds, List<Difference> errors) {
        String state = text(row, "state");
        if (row.get("statement_no")!=null) {
            state=ai.neargo.shop.pay.points.statement.StatementSourceState.effective(row,
                    read.position(PositionMove.Kind.STATEMENT.table(),(String)row.get("statement_no")));
        }
        if (!"REFUND_PREPARED".equals(state)) {
            return state;
        }
        var snapshot = refunds.get(text(row, "sub_order_no"));
        if (snapshot == null) {
            errors.add(new Difference("ORPHAN_REFUND_FREEZE", text(row, "position_no"), 1, 0));
            return state;
        }
        return snapshot.path("redeemState").asText();
    }

    private void verifyBuckets(
            List<Difference> errors,
            List<Map<String, Object>> accounts,
            String holder,
            String bucket,
            Map<String, Long> expected,
            String code) {
        var actual = new HashMap<String, Long>();
        for (var account : accounts) {
            if (holder.equals(account.get("holder_type"))) {
                actual.put(text(account, "holder_no"), n(account, bucket));
            }
        }
        var subjects = new HashSet<>(actual.keySet());
        subjects.addAll(expected.keySet());
        for (String subject : subjects) {
            check(
                    errors,
                    code,
                    subject,
                    expected.getOrDefault(subject, 0L),
                    actual.getOrDefault(subject, 0L));
        }
    }

    private List<Map<String, Object>> rows(String type, Kind kind) {
        var rows = new ArrayList<Map<String, Object>>();
        long after = 0;
        while (true) {
            var page = store.page(type, kind, after, 200);
            if (page.isEmpty()) {
                return rows;
            }
            if (rows.size() + page.size() > 10000) {
                throw new ScanLimit(kind.name());
            }
            rows.addAll(page);
            after = n(page.getLast(), "id");
        }
    }

    private Map<String, Map<String, Object>> index(List<Map<String, Object>> rows, String field) {
        var result = new HashMap<String, Map<String, Object>>();
        rows.forEach(row -> result.put(text(row, field), row));
        return result;
    }

    private JsonNode body(Map<String, Object> row) {
        return json.readTree(text(row, "payload_json"));
    }

    private String cell(String holder, String no, String bucket) {
        return json.writeValueAsString(List.of(holder, no, bucket));
    }

    private String text(Map<String, Object> row, String key) {
        return row.get(key) == null ? "" : row.get(key).toString();
    }

    private long n(Map<String, Object> row, String key) {
        return row.get(key) == null ? 0 : ((Number) row.get(key)).longValue();
    }

    private void add(Map<String, Long> map, String key, long amount) {
        map.merge(key, amount, Math::addExact);
    }

    private void check(
            List<Difference> errors, String code, String ref, long expected, long actual) {
        if (expected != actual) {
            errors.add(new Difference(code, ref, expected, actual));
        }
    }

    private static class ScanLimit extends RuntimeException {
        ScanLimit(String kind) {
            super(kind);
        }
    }
}
