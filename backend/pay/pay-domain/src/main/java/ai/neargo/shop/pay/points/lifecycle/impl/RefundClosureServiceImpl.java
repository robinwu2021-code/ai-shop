package ai.neargo.shop.pay.points.lifecycle.impl;

import ai.neargo.shop.pay.points.lifecycle.RefundClosureService;
import ai.neargo.shop.pay.points.model.AccountKey;
import ai.neargo.shop.pay.points.model.Bucket;
import ai.neargo.shop.pay.points.model.HolderType;
import ai.neargo.shop.pay.points.model.PointsKeys;
import ai.neargo.shop.pay.points.model.PointsMove;
import ai.neargo.shop.pay.points.model.PositionMove;
import ai.neargo.shop.pay.points.model.ReserveMove;
import ai.neargo.shop.pay.points.model.Voucher;
import ai.neargo.shop.pay.points.posting.PointsPostingService;
import ai.neargo.shop.pay.points.redeem.impl.RedeemServiceImpl.RedeemSnapshot;
import ai.neargo.shop.pay.points.store.LifecycleStore;
import ai.neargo.shop.pay.points.store.PointsConfigurationStore;
import ai.neargo.shop.pay.points.store.PointsFactStore;
import ai.neargo.shop.pay.points.store.PointsReadStore;
import ai.neargo.shop.pay.points.store.StatementStore;
import ai.neargo.shop.spi.points.PointsFactPort.FactKind;
import ai.neargo.shop.spi.points.PointsFactPort.LifecycleFact;
import ai.neargo.shop.spi.points.PointsFactPort.RefundClosureFact;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** Source facts authorize closure; settlement completion by itself never does. */
@Service
@Transactional("payTxManager")
public class RefundClosureServiceImpl implements RefundClosureService {
    private final PointsReadStore read;
    private final PointsConfigurationStore config;
    private final PointsFactStore facts;
    private final StatementStore sources;
    private final LifecycleStore lifecycle;
    private final PointsPostingService posting;
    private final ObjectMapper json = new ObjectMapper();

    public RefundClosureServiceImpl(
            PointsReadStore read,
            PointsConfigurationStore config,
            PointsFactStore facts,
            StatementStore sources,
            LifecycleStore lifecycle,
            PointsPostingService posting) {
        this.read = read;
        this.config = config;
        this.facts = facts;
        this.sources = sources;
        this.lifecycle = lifecycle;
        this.posting = posting;
    }

    @Override
    public Outcome close(RefundClosureFact fact) {
        require(
                fact != null
                        && nonblank(fact.eventNo())
                        && nonblank(fact.typeCode())
                        && nonblank(fact.subOrderNo())
                        && nonblank(fact.userNo())
                        && fact.sourceVersion() >= 0
                        && fact.windowClosedAt() != null
                        && fact.occurredAt() != null
                        && !fact.occurredAt().isBefore(fact.windowClosedAt())
                        && !fact.occurredAt().isAfter(Instant.now())
                        && fact.noOpenAfterSale()
                        && fact.fundsFinal(),
                "POINTS_CLOSURE_PROOF_INVALID");
        config.lockTypeAndPool(fact.typeCode());
        var metadata = lifecycle.lockLifecycle(fact.typeCode(), fact.subOrderNo());
        require(metadata != null, "POINTS_CLOSURE_SOURCE_UNKNOWN");
        var state = (ObjectNode) json.readTree((String) metadata.get("payload_json"));
        String ref = "REFUND_CLOSE:" + PointsKeys.position("C", fact.typeCode(), fact.subOrderNo());
        if (state.hasNonNull("refundClosedFact")) {
            require(
                    json.treeToValue(state.get("refundClosedFact"), RefundClosureFact.class)
                            .equals(fact),
                    "POINTS_CLOSURE_CONFLICT");
            var saved =
                    json.readValue((String) read.voucher(ref).get("result_json"), Outcome.class);
            return new Outcome(
                    saved.subOrderNo(), saved.releasedPoints(), saved.releasedMinor(), true);
        }
        require(
                state.path("finalized").asBoolean(false) && state.hasNonNull("paidFact"),
                "POINTS_CLOSURE_SOURCE_NOT_FINAL");
        var paid = json.treeToValue(state.get("paidFact"), LifecycleFact.class);
        require(fact.userNo().equals(paid.userNo()), "POINTS_CLOSURE_SUBJECT_MISMATCH");
        require(
                read.openRefunds(fact.typeCode(), fact.subOrderNo()).isEmpty(),
                "POINTS_REFUND_SOURCE_BUSY");
        String no = PointsKeys.position("R", fact.typeCode(), fact.subOrderNo());
        var source = sources.lockSource(no);
        List<PointsMove> moves = List.of();
        List<PositionMove> positions = List.of();
        List<ReserveMove> reserves = List.of();
        long points = 0;
        long minor = 0;
        var type = read.type(fact.typeCode()).orElseThrow();
        if (source != null) {
            var snapshot =
                    json.readValue((String) source.get("payload_json"), RedeemSnapshot.class);
            require(fact.userNo().equals(snapshot.userNo()), "POINTS_CLOSURE_SUBJECT_MISMATCH");
            points =
                    Math.subtractExact(
                            num(source, "original_points"), num(source, "processed_points"));
            require(
                    "PAID".equals(source.get("state"))
                            || (points == 0
                                    && List.of("PAYABLE", "SETTLEABLE", "RELEASED")
                                            .contains(source.get("state"))),
                    "POINTS_CLOSURE_FUNDS_NOT_FINAL");
            minor = type.rate().toMinor(points);
            var reserve = read.reserve(no);
            require(
                    reserve != null && num(reserve, "remaining_minor") == minor,
                    "POINTS_CLOSURE_RESERVE_CONFLICT");
            moves =
                    points == 0
                            ? List.of()
                            : List.of(
                                    new PointsMove(
                                            new AccountKey(
                                                    type.typeCode(),
                                                    HolderType.RECEIVE,
                                                    "PLATFORM",
                                                    Bucket.REFUND_RESERVED),
                                            -points),
                                    new PointsMove(
                                            new AccountKey(
                                                    type.typeCode(),
                                                    HolderType.RECEIVE,
                                                    "PLATFORM",
                                                    Bucket.RECIRCULATABLE),
                                            points));
            reserves =
                    minor == 0
                            ? List.of()
                            : List.of(
                                    new ReserveMove(
                                            no,
                                            no,
                                            num(reserve, "version"),
                                            num(reserve, "original_minor"),
                                            -minor,
                                            0,
                                            minor));
            positions =
                    List.of(
                            new PositionMove(
                                    PositionMove.Kind.REDEEM,
                                    no,
                                    num(source, "version"),
                                    (String) source.get("state"),
                                    "CLOSED",
                                    num(source, "original_points"),
                                    0,
                                    (String) source.get("payload_json")));
        }
        var result = new Outcome(fact.subOrderNo(), points, minor, false);
        String payload = json.writeValueAsString(fact);
        String hash = hash(payload);
        var prior = facts.lockInbox(fact.eventNo());
        require(prior == null, "POINTS_CLOSURE_EVENT_CONFLICT");
        posting.post(
                new Voucher(
                        ref,
                        type.typeCode(),
                        type.currency(),
                        type.rate(),
                        moves,
                        List.of(),
                        positions,
                        -minor,
                        List.of(),
                        json.writeValueAsString(result),
                        reserves));
        state.set("refundClosedFact", json.valueToTree(fact));
        var closed =
                new LifecycleFact(
                        fact.eventNo(),
                        hash,
                        fact.subOrderNo(),
                        fact.userNo(),
                        fact.typeCode(),
                        fact.sourceVersion(),
                        FactKind.REFUND_CLOSED,
                        0,
                        0,
                        List.of(),
                        fact.occurredAt());
        facts.saveLifecycle(closed, json.writeValueAsString(state), num(metadata, "version"));
        facts.record(closed, payload, "REFUND_CLOSED");
        return result;
    }

    private String hash(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private boolean nonblank(String value) {
        return value != null && !value.isBlank();
    }

    private long num(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }

    private void require(boolean condition, String reason) {
        if (!condition) {
            throw new IllegalArgumentException(reason);
        }
    }
}
