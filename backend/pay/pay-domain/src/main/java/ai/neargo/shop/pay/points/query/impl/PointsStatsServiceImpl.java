package ai.neargo.shop.pay.points.query.impl;

import ai.neargo.shop.pay.points.model.PointsKeys;
import ai.neargo.shop.pay.points.model.PositionMove;
import ai.neargo.shop.pay.points.query.PointsStatsService;
import ai.neargo.shop.pay.points.store.PointsConfigurationStore;
import ai.neargo.shop.pay.points.store.PointsFactStore;
import ai.neargo.shop.pay.points.store.PointsOperationsStore;
import ai.neargo.shop.pay.points.store.PointsOperationsStore.Kind;
import ai.neargo.shop.pay.points.store.PointsReadStore;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Replace each dirty day once per batch, then acknowledge its posting events in the same
 * transaction.
 */
@Service
@Transactional("payTxManager")
public class PointsStatsServiceImpl implements PointsStatsService {
    private final PointsConfigurationStore config;
    private final PointsOperationsStore store;
    private final PointsReadStore read;
    private final PointsFactStore facts;
    private final ObjectMapper json = new ObjectMapper();

    public PointsStatsServiceImpl(
            PointsConfigurationStore config,
            PointsOperationsStore store,
            PointsReadStore read,
            PointsFactStore facts) {
        this.config = config;
        this.store = store;
        this.read = read;
        this.facts = facts;
    }

    @Override
    public Snapshot rebuild(String type, LocalDate day) {
        if (day == null) {
            throw new IllegalArgumentException("POINTS_STAT_DAY_REQUIRED");
        }
        config.lockTypeAndPool(type);
        var vouchers = dayRows(type, day, null);
        var metrics = new LinkedHashMap<String, Map<String, Long>>();
        var reasons = new HashMap<String, Map<String, Long>>();
        metrics.put("TOTAL", new HashMap<>());
        long through = 0;
        var voucherIndex = new HashMap<String, Map<String, Object>>();
        for (var voucher : vouchers) {
            through = Math.max(through, num(voucher, "id"));
            voucherIndex.put(text(voucher, "voucher_no"), voucher);
            String ref = text(voucher, "biz_ref");
            var result = json.readTree(text(voucher, "result_json"));
            var contribution = new HashMap<String, Long>();
            String user = null, storeNo = null, reason = null;
            if (ref.startsWith("GRANT:")) {
                contribution.put("grant_events", 1L);
                contribution.put("granted_points", result.path("grantedPoints").longValue());
                contribution.put("expected_points", result.path("expectedPoints").longValue());
                if ("SKIPPED".equals(result.path("status").asText())) {
                    contribution.put("skipped_events", 1L);
                    reason = result.path("reason").asText("UNKNOWN");
                }
                var head =
                        read.position(
                                PositionMove.Kind.GRANT.table(),
                                PointsKeys.position("G", type, ref.substring(6)));
                if (head != null) {
                    var command = body(head).path("command");
                    user = command.path("userNo").asText(null);
                    storeNo = command.path("storeNo").asText(null);
                } else {
                    contribution.put("unattributed_events", 1L);
                }
            } else if (ref.startsWith("REFUND_CONFIRM:")) {
                contribution.put("refund_events", 1L);
                for (String field :
                        List.of(
                                "returnedPoints",
                                "reclaimedPoints",
                                "collectedMinor",
                                "waivedPoints",
                                "receivableMinor")) {
                    contribution.put("refund_" + field, result.path(field).longValue());
                }
                var head =
                        read.position(
                                PositionMove.Kind.REFUND.table(),
                                result.path("preparationNo").asText());
                if (head != null) {
                    var command = body(head).path("command");
                    user = command.path("userNo").asText(null);
                    storeNo = command.path("payment").path("storeNo").asText(null);
                }
            } else if (ref.startsWith("PAYOUT_RESULT:")
                    && "SUCCEEDED".equals(result.path("status").asText())) {
                var head =
                        read.position(
                                PositionMove.Kind.PAYOUT.table(), result.path("ticketNo").asText());
                if (head == null) {
                    throw new IllegalStateException("POINTS_STATS_PAYOUT_MISSING");
                }
                var saved = body(head);
                var ticket = saved.path("ticket");
                contribution.put("payout_events", 1L);
                contribution.put("payout_gross_minor", ticket.path("gross").longValue());
                contribution.put("payout_cash_minor", ticket.path("cash").longValue());
                contribution.put("payout_offset_minor", ticket.path("offset").longValue());
                storeNo = saved.path("command").path("context").path("storeNo").asText(null);
            } else if (ref.startsWith("EXPIRE:")
                    && "SUCCEEDED".equals(result.path("result").path("status").asText())) {
                contribution.put("expired_points", result.path("points").longValue());
                contribution.put(
                        "expiry_income_minor",
                        result.path("fundsCommand").path("amountMinor").longValue());
                user = result.path("userNo").asText(null);
            } else if (ref.startsWith("REFUND_CLOSE:")) {
                contribution.put(
                        "closed_liability_points", result.path("releasedPoints").longValue());
                contribution.put("released_b_minor", result.path("releasedMinor").longValue());
                var head =
                        read.position(
                                PositionMove.Kind.REDEEM.table(),
                                PointsKeys.position("R", type, result.path("subOrderNo").asText()));
                if (head != null) {
                    var saved = body(head);
                    user = saved.path("userNo").asText(null);
                    storeNo = saved.path("share").path("storeNo").asText(null);
                }
            }
            contribute(metrics, reasons, "TOTAL", contribution, reason);
            if (user != null) {
                contribute(metrics, reasons, userScope(type, user), contribution, reason);
            }
            if (storeNo != null) {
                contribute(metrics, reasons, storeScope(type, storeNo), contribution, reason);
            }
        }
        for (var entry : dayRows(type, day, Kind.ENTRY)) {
            var voucher = voucherIndex.get(text(entry, "voucher_no"));
            if (voucher == null
                    || !text(voucher, "biz_ref").startsWith("PAID:")
                    || !"USER".equals(entry.get("holder_type"))
                    || !"HELD".equals(entry.get("bucket"))
                    || num(entry, "delta_points") >= 0) {
                continue;
            }
            var count = Map.of("redeemed_points", Math.negateExact(num(entry, "delta_points")));
            contribute(metrics, reasons, "TOTAL", count, null);
            contribute(metrics, reasons, userScope(type, text(entry, "holder_no")), count, null);
            String event = text(voucher, "biz_ref").substring(5);
            var fact = facts.inbox(event);
            if (fact != null) {
                String sub = body(fact).path("subOrderNo").asText();
                var head =
                        read.position(
                                PositionMove.Kind.REDEEM.table(),
                                PointsKeys.position("R", type, sub));
                if (head != null) {
                    contribute(
                            metrics,
                            reasons,
                            storeScope(type, body(head).path("share").path("storeNo").asText()),
                            count,
                            null);
                }
            }
        }
        for (var flow : dayRows(type, day, Kind.FLOW)) {
            metrics.get("TOTAL")
                    .merge(
                            "flow_" + text(flow, "bucket"),
                            num(flow, "delta_minor"),
                            Math::addExact);
        }
        store.clearStats(type, day);
        long at = System.currentTimeMillis();
        for (var scope : metrics.entrySet()) {
            var snapshot =
                    new Snapshot(
                            type,
                            day,
                            scope.getKey(),
                            scope.getValue(),
                            reasons.getOrDefault(scope.getKey(), Map.of()),
                            through,
                            at);
            store.saveStat(type, day, scope.getKey(), "STATS", json.writeValueAsString(snapshot));
        }
        return read(type, day, "TOTAL");
    }

    @Override
    public Snapshot read(String type, LocalDate day, String scope) {
        if (day == null
                || scope == null
                || !(scope.equals("TOTAL")
                        || scope.startsWith("STORE:")
                        || scope.startsWith("USER:"))) {
            throw new IllegalArgumentException("POINTS_STATS_SCOPE_INVALID");
        }
        var row = store.stat(type, day, scope);
        return row == null ? null : json.readValue(text(row, "payload_json"), Snapshot.class);
    }

    @Override
    public int project(String type, int limit) {
        config.lockTypeAndPool(type);
        var events = store.postingEvents(type, limit);
        var days = new HashSet<LocalDate>();
        for (var event : events) {
            days.add(date(event.get("voucher_at")));
        }
        for (var day : days) {
            rebuild(type, day);
        }
        events.forEach(event -> store.projected(num(event, "id"), num(event, "version")));
        return events.size();
    }

    public static String storeScope(String type, String store) {
        return "STORE:" + PointsKeys.position("S", type, store);
    }

    public static String userScope(String type, String user) {
        return "USER:" + PointsKeys.position("U", type, user);
    }

    private List<Map<String, Object>> dayRows(String type, LocalDate day, Kind kind) {
        var all = new ArrayList<Map<String, Object>>();
        long after = 0;
        while (true) {
            var page =
                    kind == null
                            ? store.vouchers(type, day, after, 200)
                            : store.dayRows(type, kind, day, after, 200);
            if (page.isEmpty()) {
                return all;
            }
            if (all.size() + page.size() > 10000) {
                throw new IllegalStateException("POINTS_STATS_SCAN_LIMIT");
            }
            all.addAll(page);
            after = num(page.getLast(), "id");
        }
    }

    private void contribute(
            Map<String, Map<String, Long>> metrics,
            Map<String, Map<String, Long>> reasons,
            String scope,
            Map<String, Long> contribution,
            String reason) {
        var target = metrics.computeIfAbsent(scope, k -> new HashMap<>());
        contribution.forEach((key, value) -> target.merge(key, value, Math::addExact));
        if (reason != null) {
            reasons.computeIfAbsent(scope, k -> new HashMap<>()).merge(reason, 1L, Math::addExact);
        }
    }

    private LocalDate date(Object value) {
        if (value instanceof LocalDateTime local) {
            return local.toLocalDate();
        }
        if (value instanceof Timestamp stamp) {
            return stamp.toLocalDateTime().toLocalDate();
        }
        throw new IllegalArgumentException("POINTS_STATS_DATE_INVALID");
    }

    private JsonNode body(Map<String, Object> row) {
        return json.readTree(text(row, "payload_json"));
    }

    private String text(Map<String, Object> row, String key) {
        return row.get(key) == null ? "" : row.get(key).toString();
    }

    private long num(Map<String, Object> row, String key) {
        return row.get(key) == null ? 0 : ((Number) row.get(key)).longValue();
    }
}
