package ai.neargo.shop.pay.points.app.impl;

import ai.neargo.shop.pay.points.app.PointsSettlementHook;
import ai.neargo.shop.pay.points.earn.impl.EarnServiceImpl.GrantSnapshot;
import ai.neargo.shop.pay.points.model.AccountKey;
import ai.neargo.shop.pay.points.model.Bucket;
import ai.neargo.shop.pay.points.model.HolderType;
import ai.neargo.shop.pay.points.model.PointType;
import ai.neargo.shop.pay.points.model.PointsKeys;
import ai.neargo.shop.pay.points.model.PointsMove;
import ai.neargo.shop.pay.points.model.PositionMove;
import ai.neargo.shop.pay.points.model.Voucher;
import ai.neargo.shop.pay.points.payout.PayoutSnapshot;
import ai.neargo.shop.pay.points.payout.PointsPayoutService;
import ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsPurpose;
import ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsResult;
import ai.neargo.shop.pay.points.posting.PointsPostingService;
import ai.neargo.shop.pay.points.redeem.impl.RedeemServiceImpl.RedeemSnapshot;
import ai.neargo.shop.pay.points.store.PointsConfigurationStore;
import ai.neargo.shop.pay.points.store.PointsFactStore;
import ai.neargo.shop.pay.points.store.PointsReadStore;
import ai.neargo.shop.spi.points.PointsFactPort.FactKind;
import ai.neargo.shop.spi.points.PointsFactPort.LifecycleFact;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * Settlement entry joins the same short pay transaction as financial posting and durable source
 * facts.
 */
@Service
@Transactional("payTxManager")
public class PointsSettlementHookImpl implements PointsSettlementHook {
    private final PointsPayoutService payouts;
    private final PointsReadStore read;
    private final PointsConfigurationStore configuration;
    private final PointsFactStore facts;
    private final PointsPostingService posting;
    private final ObjectMapper json = new ObjectMapper();

    public PointsSettlementHookImpl(
            PointsPayoutService payouts,
            PointsReadStore read,
            PointsConfigurationStore configuration,
            PointsFactStore facts,
            PointsPostingService posting) {
        this.payouts = payouts;
        this.read = read;
        this.configuration = configuration;
        this.facts = facts;
        this.posting = posting;
    }

    @Override
    public PayoutTicket beginPayout(PayoutCommand command) {
        return payouts.beginPayout(command);
    }

    @Override
    public PayoutOutcome acceptResult(FundsResult result) {
        return payouts.acceptResult(result);
    }

    @Override
    public SettleMode settleModeOf(String sub) {
        var sources = read.redemptionsForSub(sub);
        require(sources.size() == 1, "POINTS_REDEMPTION_NOT_UNIQUE");
        var snapshot =
                json.readValue(
                        (String) sources.getFirst().get("payload_json"), RedeemSnapshot.class);
        return SettleMode.valueOf(snapshot.agreement().settleMode());
    }

    @Override
    public BillPayoutPlan prepareForBill(BillPayout bill) {
        require(bill.pointsMinor() >= 0, "POINTS_SETTLEMENT_AMOUNT_INVALID");
        var sources = read.redemptionsForSub(bill.subOrderNo());
        if (sources.isEmpty() && bill.pointsMinor() == 0) {
            return new BillPayoutPlan(null, null);
        }
        require(sources.size() == 1, "POINTS_REDEMPTION_NOT_UNIQUE");
        var head = sources.getFirst();
        String typeCode = (String) head.get("type_code");
        configuration.lockTypeAndPool(typeCode);
        head = read.position(PositionMove.Kind.REDEEM.table(), (String) head.get("position_no"));
        var snapshot = json.readValue((String) head.get("payload_json"), RedeemSnapshot.class);
        var type = read.type(typeCode).orElseThrow();
        require(
                snapshot.share().storeNo().equals(bill.storeNo())
                        && snapshot.share().merchantNo().equals(bill.merchantNo())
                        && type.currency().equals(bill.currency())
                        && snapshot.fundsContext().path().name().equals(bill.fundsPath()),
                "POINTS_SETTLEMENT_SUBJECT_MISMATCH");
        SettleMode mode = SettleMode.valueOf(snapshot.agreement().settleMode());
        long remaining =
                type.rate()
                        .toMinor(
                                Math.subtractExact(
                                        number(head, "original_points"),
                                        number(head, "processed_points")));
        require(remaining == bill.pointsMinor(), "POINTS_SETTLEMENT_AMOUNT_MISMATCH");
        if (mode == SettleMode.PERIODIC) {
            require(
                    !"HELD".equals(head.get("state")) && !"RELEASED".equals(head.get("state")),
                    "POINTS_PAYMENT_FACT_REQUIRED");
            return new BillPayoutPlan(mode, null);
        }
        PayoutCommand command;
        if (head.get("ticket_no") != null) {
            var ticket =
                    read.position(PositionMove.Kind.PAYOUT.table(), (String) head.get("ticket_no"));
            require(ticket != null, "POINTS_PAYOUT_BINDING_MISSING");
            command =
                    json.readValue((String) ticket.get("payload_json"), PayoutSnapshot.class)
                            .command();
        } else {
            command =
                    new PayoutCommand(
                            "SETTLE:" + bill.settleNo() + ":" + number(head, "version"),
                            typeCode,
                            bill.subOrderNo(),
                            List.of((String) head.get("position_no")),
                            FundsPurpose.forPerOrder(snapshot.fundsContext().path()),
                            snapshot.fundsContext(),
                            type.currency());
        }
        return new BillPayoutPlan(mode, payouts.beginPayout(command));
    }

    @Override
    public void onBillFinalized(LifecycleFact fact) {
        validate(fact);
        configuration.lockTypeAndPool(fact.typeCode());
        String payload = json.writeValueAsString(fact);
        var prior = facts.inbox(fact.eventNo());
        if (prior != null) {
            require(
                    fact.payloadHash().equals(prior.get("payload_hash"))
                            && json.readTree(payload)
                                    .equals(json.readTree((String) prior.get("payload_json"))),
                    "POINTS_FACT_CONFLICT");
            return;
        }
        var lifecycle = configuration.lockLifecycle(fact.typeCode(), fact.subOrderNo());
        var saved =
                lifecycle == null
                        ? null
                        : json.readTree((String) lifecycle.get("payload_json")).path("finalFact");
        if (saved != null && saved.isObject()) {
            long finalVersion = saved.path("sourceVersion").longValue();
            if (finalVersion > fact.sourceVersion()) {
                facts.record(fact, payload, "STALE");
                return;
            }
            if (finalVersion == fact.sourceVersion()) {
                require(saved.equals(json.readTree(payload)), "POINTS_FACT_VERSION_CONFLICT");
            }
        }
        require(
                read.openRefunds(fact.typeCode(), fact.subOrderNo()).isEmpty(),
                "POINTS_REFUND_SOURCE_BUSY");
        PointType type = read.type(fact.typeCode()).orElseThrow();
        List<PointsMove> moves = new ArrayList<>();
        List<PositionMove> positions = new ArrayList<>();
        finalizeGrant(fact, type, moves, positions);
        finalizeRedemption(fact, type, moves, positions);
        posting.post(
                new Voucher(
                        "FINALIZE:" + fact.eventNo(),
                        type.typeCode(),
                        type.currency(),
                        type.rate(),
                        moves,
                        List.of(),
                        positions,
                        0,
                        List.of(),
                        "{}"));
        var state =
                lifecycle == null
                        ? json.createObjectNode()
                        : (ObjectNode) json.readTree((String) lifecycle.get("payload_json"));
        state.put("finalized", true);
        state.set("finalFact", json.readTree(payload));
        facts.saveLifecycle(
                fact,
                json.writeValueAsString(state),
                lifecycle == null ? null : number(lifecycle, "version"));
        facts.record(fact, payload, "APPLIED");
    }

    private void finalizeGrant(
            LifecycleFact fact,
            PointType type,
            List<PointsMove> moves,
            List<PositionMove> positions) {
        String no = PointsKeys.position("G", fact.typeCode(), fact.subOrderNo());
        var head = read.position(PositionMove.Kind.GRANT.table(), no);
        if (head == null) {
            return;
        }
        GrantSnapshot grant =
                json.readValue((String) head.get("payload_json"), GrantSnapshot.class);
        require(fact.userNo().equals(grant.userNo()), "POINTS_FACT_SUBJECT_MISMATCH");
        long remaining =
                Math.subtractExact(
                        Math.subtractExact(
                                number(head, "original_points"), grant.activatedPoints()),
                        grant.reclaimedPending());
        require(remaining >= 0, "POINTS_GRANT_COUNTER_INVALID");
        if (remaining == 0) {
            return;
        }
        require(
                fact.refundedGoodsMinor() == 0 || grant.reclaimedPending() > 0,
                "POINTS_REFUND_FACT_NOT_APPLIED");
        moves.add(move(type, HolderType.USER, grant.userNo(), Bucket.PENDING, -remaining));
        moves.add(move(type, HolderType.USER, grant.userNo(), Bucket.AVAILABLE, remaining));
        var next =
                new GrantSnapshot(
                        grant.subOrderNo(),
                        grant.userNo(),
                        grant.command(),
                        grant.decision(),
                        grant.outcome(),
                        Math.addExact(grant.activatedPoints(), remaining),
                        grant.reclaimedPending(),
                        grant.reclaimedAvailable(),
                        grant.collectedMinor(),
                        grant.waivedPoints(),
                        grant.processedByLine(),
                        grant.refundedGoodsByLine());
        positions.add(
                new PositionMove(
                        PositionMove.Kind.GRANT,
                        no,
                        number(head, "version"),
                        (String) head.get("state"),
                        (String) head.get("state"),
                        number(head, "original_points"),
                        0,
                        json.writeValueAsString(next)));
    }

    private void finalizeRedemption(
            LifecycleFact fact,
            PointType type,
            List<PointsMove> moves,
            List<PositionMove> positions) {
        String no = PointsKeys.position("R", fact.typeCode(), fact.subOrderNo());
        var head = read.position(PositionMove.Kind.REDEEM.table(), no);
        if (head == null) {
            return;
        }
        var snapshot = json.readValue((String) head.get("payload_json"), RedeemSnapshot.class);
        require(fact.userNo().equals(snapshot.userNo()), "POINTS_FACT_SUBJECT_MISMATCH");
        String state = (String) head.get("state");
        require(!"HELD".equals(state) && !"RELEASED".equals(state), "POINTS_PAYMENT_FACT_REQUIRED");
        require(fact.refundedGoodsMinor() == 0, "POINTS_REFUND_FACT_NOT_APPLIED");
        if (snapshot.finalSnapshotReceived() && !"IN_STORE".equals(state)) {
            return;
        }
        String nextState = state;
        if ("IN_STORE".equals(state)) {
            long remaining =
                    Math.subtractExact(
                            number(head, "original_points"), number(head, "processed_points"));
            moves.add(
                    move(
                            type,
                            HolderType.STORE,
                            snapshot.share().storeNo(),
                            Bucket.PENDING,
                            -remaining));
            moves.add(
                    move(
                            type,
                            HolderType.STORE,
                            snapshot.share().storeNo(),
                            Bucket.AVAILABLE,
                            remaining));
            nextState = "SETTLEABLE";
        }
        var next =
                new RedeemSnapshot(
                        snapshot.subOrderNo(),
                        snapshot.userNo(),
                        snapshot.orderNo(),
                        snapshot.share(),
                        snapshot.target(),
                        snapshot.agreement(),
                        snapshot.fundsContext(),
                        true);
        positions.add(
                new PositionMove(
                        PositionMove.Kind.REDEEM,
                        no,
                        number(head, "version"),
                        state,
                        nextState,
                        number(head, "original_points"),
                        0,
                        json.writeValueAsString(next)));
    }

    private void validate(LifecycleFact f) {
        require(
                f != null
                        && f.kind() == FactKind.FINALIZED
                        && f.eventNo() != null
                        && !f.eventNo().isBlank()
                        && f.payloadHash() != null
                        && !f.payloadHash().isBlank()
                        && f.subOrderNo() != null
                        && f.userNo() != null
                        && f.typeCode() != null
                        && f.sourceVersion() >= 0
                        && f.occurredAt() != null
                        && f.finalGoodsMinor() >= 0
                        && f.refundedGoodsMinor() >= 0
                        && f.refundedGoodsMinor() <= f.finalGoodsMinor(),
                "POINTS_FINAL_FACT_INVALID");
        long goods = 0, refunded = 0;
        var items = new HashSet<String>();
        for (var line : f.lines()) {
            require(
                    line.itemId() != null
                            && items.add(line.itemId())
                            && line.quantity() >= 0
                            && line.goodsMinor() >= 0
                            && line.refundedGoodsMinor() >= 0
                            && line.refundedGoodsMinor() <= line.goodsMinor(),
                    "POINTS_FINAL_FACT_INVALID");
            goods = Math.addExact(goods, line.goodsMinor());
            refunded = Math.addExact(refunded, line.refundedGoodsMinor());
        }
        require(
                goods == f.finalGoodsMinor() && refunded == f.refundedGoodsMinor(),
                "POINTS_FINAL_FACT_INVALID");
    }

    private PointsMove move(
            PointType type, HolderType holder, String no, Bucket bucket, long amount) {
        return new PointsMove(new AccountKey(type.typeCode(), holder, no, bucket), amount);
    }

    private long number(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }

    private void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
