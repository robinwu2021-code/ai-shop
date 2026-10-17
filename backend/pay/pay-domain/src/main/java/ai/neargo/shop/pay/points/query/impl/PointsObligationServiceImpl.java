package ai.neargo.shop.pay.points.query.impl;

import ai.neargo.shop.pay.points.model.PointsKeys;
import ai.neargo.shop.pay.points.model.PositionMove;
import ai.neargo.shop.pay.points.query.PointsObligationService;
import ai.neargo.shop.pay.points.store.PointsOperationsStore;
import ai.neargo.shop.pay.points.store.PointsOperationsStore.Kind;
import ai.neargo.shop.pay.points.store.PointsReadStore;
import ai.neargo.shop.spi.points.PointsObligationPort.Obligations;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.*;

/** Bounded read-only clearance proof; unattributed pending work is UNKNOWN, never zero. */
@Service
@Transactional(value = "payTxManager", readOnly = true)
public class PointsObligationServiceImpl implements PointsObligationService {
    private final PointsReadStore read;
    private final PointsOperationsStore store;
    private final ObjectMapper json = new ObjectMapper();
    private static final int MAX_ROWS = 10000;
    private static final List<Kind> KINDS =
            List.of(
                    Kind.GRANT,
                    Kind.REDEEM,
                    Kind.REFUND,
                    Kind.PAYOUT,
                    Kind.TRANSFER,
                    Kind.RECEIVABLE,
                    Kind.STATEMENT,
                    Kind.OUTBOX);

    public PointsObligationServiceImpl(PointsReadStore read, PointsOperationsStore store) {
        this.read = read;
        this.store = store;
    }

    @Override
    public Obligations inspect(String storeNo, String subOrderNo) {
        if ((blank(storeNo) && blank(subOrderNo)) || (!blank(storeNo) && !blank(subOrderNo)))
            throw new IllegalArgumentException("POINTS_OBLIGATION_SCOPE_INVALID");
        var types = read.types();
        if (types.size() > 100) return Obligations.unknown();
        long[] count = new long[6];
        int seen = 0;
        boolean known = true, terminalSource = false;
        Set<String> countedRedemptions = new HashSet<>();
        for (var type : types) {
            if (!blank(subOrderNo) && closed(type.typeCode(), subOrderNo)) terminalSource = true;
            for (var kind : KINDS) {
                long after = 0;
                while (true) {
                    var rows = store.page(type.typeCode(), kind, after, 200);
                    for (var row : rows) {
                        if (++seen > MAX_ROWS) return result(false, count);
                        if (terminal(kind, row)) continue;
                        String payload = (String) row.get("payload_json");
                        if (payload == null || payload.length() > 1000000) {
                            known = false;
                            continue;
                        }
                        JsonNode body;
                        try {
                            body = json.readTree(payload);
                            if (kind == Kind.OUTBOX && body.path("payload").isTextual())
                                body = json.readTree(body.path("payload").asText());
                        } catch (RuntimeException invalid) {
                            known = false;
                            continue;
                        }
                        if (kind == Kind.REDEEM && body.has("command") && body.has("outcome")) {
                            known &=
                                    inspectRoot(
                                            type.typeCode(),
                                            body,
                                            storeNo,
                                            subOrderNo,
                                            countedRedemptions,
                                            count);
                            continue;
                        }
                        var owner = owner(kind, row, body);
                        String target = blank(storeNo) ? owner.sub() : owner.store();
                        if (blank(target)) {
                            known = false;
                            continue;
                        }
                        if (!target.equals(blank(storeNo) ? subOrderNo : storeNo)) continue;
                        switch (kind) {
                            case GRANT -> {
                                if (blank(owner.sub()) || !closed(type.typeCode(), owner.sub()))
                                    count[1]++;
                            }
                            case REDEEM -> {
                                if (blank(owner.sub())) known = false;
                                else if (countedRedemptions.add(
                                        type.typeCode() + "/" + owner.sub())) count[0]++;
                            }
                            case REFUND, RECEIVABLE -> count[2]++;
                            case STATEMENT -> count[3]++;
                            case PAYOUT, TRANSFER -> count[4]++;
                            case OUTBOX -> count[5]++;
                            default ->
                                    throw new IllegalStateException(
                                            "POINTS_OBLIGATION_KIND_INVALID");
                        }
                    }
                    if (rows.size() < 200) break;
                    Object id = rows.getLast().get("id");
                    if (!(id instanceof Number n) || n.longValue() <= after)
                        return result(false, count);
                    after = n.longValue();
                }
            }
        }
        return result(known && (blank(subOrderNo) || terminalSource), count);
    }

    private boolean closed(String type, String sub) {
        var row = read.lifecycle(type, sub);
        if (row == null || row.get("payload_json") == null) return false;
        var body = json.readTree((String) row.get("payload_json"));
        return body.hasNonNull("cancelFact") || body.hasNonNull("refundClosedFact");
    }

    /**
     * Root is an immutable idempotency receipt. Verify and count its children, never the receipt
     * twice.
     */
    private boolean inspectRoot(
            String type,
            JsonNode body,
            String storeNo,
            String subOrderNo,
            Set<String> seen,
            long[] count) {
        var targets = body.path("command").path("targets");
        var shares = body.path("outcome").path("shares");
        if (!targets.isArray()
                || !shares.isArray()
                || targets.size() > 200
                || shares.size() > 200
                || !body.path("outcome").path("points").isIntegralNumber()) return false;
        long declared = body.path("outcome").path("points").longValue(), total = 0;
        if (declared < 0) return false;
        for (var share : shares) {
            if (!share.path("points").isIntegralNumber() || share.path("points").longValue() < 0)
                return false;
            total = Math.addExact(total, share.path("points").longValue());
        }
        if (declared != total) return false;
        Map<String, JsonNode> bySub = new HashMap<>();
        for (var target : targets) {
            String sub = text(target, "/subOrderNo");
            if (blank(sub)
                    || blank(text(target, "/storeNo"))
                    || blank(text(target, "/merchantNo"))
                    || bySub.put(sub, target) != null) return false;
        }
        Set<String> shareSubs = new HashSet<>();
        for (var share : shares) {
            String sub = text(share, "/subOrderNo");
            var target = bySub.get(sub);
            if (target == null
                    || !shareSubs.add(sub)
                    || !Objects.equals(text(target, "/storeNo"), text(share, "/storeNo"))
                    || !Objects.equals(text(target, "/merchantNo"), text(share, "/merchantNo")))
                return false;
            if (!Objects.equals(
                    blank(storeNo) ? subOrderNo : storeNo,
                    blank(storeNo) ? sub : text(share, "/storeNo"))) continue;
            var child =
                    read.position(
                            PositionMove.Kind.REDEEM.table(), PointsKeys.position("R", type, sub));
            if (child == null
                    || !(child.get("original_points") instanceof Number points)
                    || !share.path("points").isIntegralNumber()
                    || points.longValue() != share.path("points").longValue()) return false;
            var frozen = json.readTree((String) child.get("payload_json"));
            if (!Objects.equals(sub, text(frozen, "/subOrderNo"))
                    || !Objects.equals(text(share, "/storeNo"), text(frozen, "/share/storeNo"))
                    || !Objects.equals(
                            text(share, "/merchantNo"), text(frozen, "/share/merchantNo")))
                return false;
            if (!terminal(Kind.REDEEM, child) && seen.add(type + "/" + sub)) count[0]++;
        }
        return true;
    }

    private Owner owner(Kind kind, Map<String, Object> row, JsonNode b) {
        return switch (kind) {
            case GRANT ->
                    new Owner(
                            text(b, "/command/storeNo"),
                            text(b, "/command/subOrderNo", "/subOrderNo"));
            case REDEEM -> new Owner(text(b, "/share/storeNo"), text(b, "/subOrderNo"));
            case REFUND ->
                    new Owner(text(b, "/command/payment/storeNo"), text(b, "/command/subOrderNo"));
            case PAYOUT, TRANSFER ->
                    new Owner(
                            text(
                                    b,
                                    "/fundsCommand/context/storeNo",
                                    "/command/fundsContext/storeNo",
                                    "/command/context/storeNo"),
                            text(b, "/command/subOrderNo", "/subOrderNo"));
            case STATEMENT -> new Owner(text(b, "/period/storeNo"), null);
            case RECEIVABLE ->
                    new Owner(
                            (String) row.get("store_no"),
                            text(b, "/subOrderNo", "/command/subOrderNo"));
            case OUTBOX ->
                    new Owner(
                            text(b, "/storeNo", "/command/storeNo", "/payment/storeNo"),
                            text(b, "/subOrderNo", "/command/subOrderNo"));
            default -> throw new IllegalArgumentException("POINTS_OBLIGATION_KIND_INVALID");
        };
    }

    private static boolean terminal(Kind kind, Map<String, Object> row) {
        String state = (String) row.get("state");
        if (kind == Kind.RECEIVABLE)
            return row.get("original_minor") instanceof Number original
                    && row.get("recovered_minor") instanceof Number recovered
                    && recovered.longValue() >= original.longValue();
        return switch (kind) {
            case GRANT ->
                    Set.of("SKIPPED", "REVOKED", "EXPIRED").contains(state == null ? "" : state);
            case REDEEM ->
                    Set.of("RELEASED", "REFUNDED", "CLOSED").contains(state == null ? "" : state);
            case REFUND -> Set.of("CONFIRMED", "ABORTED").contains(state == null ? "" : state);
            case PAYOUT, TRANSFER ->
                    Set.of("SUCCEEDED", "FAILED").contains(state == null ? "" : state);
            case STATEMENT -> Set.of("PAID", "CLEARED").contains(state == null ? "" : state);
            case OUTBOX -> "DONE".equals(state);
            default -> false;
        };
    }

    private static String text(JsonNode body, String... paths) {
        for (String p : paths) {
            var value = body.at(p);
            if (value.isTextual() && !value.asText().isBlank()) return value.asText();
        }
        return null;
    }

    private static boolean blank(String v) {
        return v == null || v.isBlank();
    }

    private static Obligations result(boolean known, long[] n) {
        return new Obligations(known, n[0], n[1], n[2], n[3], n[4], n[5]);
    }

    private record Owner(String store, String sub) {}
}
