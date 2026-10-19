package ai.neargo.shop.pay.points.app.impl;

import ai.neargo.shop.pay.points.app.PointsPaidFactService;
import ai.neargo.shop.pay.points.model.AccountKey;
import ai.neargo.shop.pay.points.model.Bucket;
import ai.neargo.shop.pay.points.model.FundBucket;
import ai.neargo.shop.pay.points.model.FundMove;
import ai.neargo.shop.pay.points.model.HolderType;
import ai.neargo.shop.pay.points.model.PointsKeys;
import ai.neargo.shop.pay.points.model.PointsMove;
import ai.neargo.shop.pay.points.model.PositionMove;
import ai.neargo.shop.pay.points.model.Voucher;
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

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Payment consumes H exactly once; store ownership and settlement mode come from the acceptance
 * snapshot.
 */
@Service
@Transactional("payTxManager")
public class PointsPaidFactServiceImpl implements PointsPaidFactService {
    private final PointsReadStore read;
    private final PointsConfigurationStore configuration;
    private final PointsFactStore facts;
    private final PointsPostingService posting;
    private final ObjectMapper json = new ObjectMapper();

    public PointsPaidFactServiceImpl(
            PointsReadStore read,
            PointsConfigurationStore configuration,
            PointsFactStore facts,
            PointsPostingService posting) {
        this.read = read;
        this.configuration = configuration;
        this.facts = facts;
        this.posting = posting;
    }

    @Override
    public void onPaid(LifecycleFact fact) {
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
        var state =
                lifecycle == null
                        ? json.createObjectNode()
                        : (ObjectNode) json.readTree((String) lifecycle.get("payload_json"));
        if (state.hasNonNull("paidFact")) {
            LifecycleFact original = json.treeToValue(state.get("paidFact"), LifecycleFact.class);
            require(
                    original.userNo().equals(fact.userNo())
                            && original.lines().equals(fact.lines())
                            && original.finalGoodsMinor() == fact.finalGoodsMinor()
                            && Objects.equals(original.payment(), fact.payment()),
                    "POINTS_PAID_SNAPSHOT_CONFLICT");
            facts.record(fact, payload, "REPLAYED");
            return;
        }
        require(!state.path("cancelled").asBoolean(false), "POINTS_PAID_AFTER_RELEASE");
        String no = PointsKeys.position("R", fact.typeCode(), fact.subOrderNo());
        var head = read.position(PositionMove.Kind.REDEEM.table(), no);
        if (head != null) {
            require("HELD".equals(head.get("state")), "POINTS_PAID_POSITION_CONFLICT");
            var snapshot = json.readValue((String) head.get("payload_json"), RedeemSnapshot.class);
            require(fact.userNo().equals(snapshot.userNo()), "POINTS_FACT_SUBJECT_MISMATCH");
            var type = read.type(fact.typeCode()).orElseThrow();
            long points = number(head, "original_points");
            require(
                    points == snapshot.share().points()
                            && type.rate().toMinor(points) == snapshot.share().minor(),
                    "POINTS_POSITION_AMOUNT_MISMATCH");
            boolean periodic = "PERIODIC".equals(snapshot.agreement().settleMode());
            boolean finalized = state.path("finalized").asBoolean(false);
            Bucket destination =
                    periodic
                            ? (finalized ? Bucket.AVAILABLE : Bucket.PENDING)
                            : Bucket.REFUND_RESERVED;
            var next =
                    new RedeemSnapshot(
                            snapshot.subOrderNo(),
                            snapshot.userNo(),
                            snapshot.orderNo(),
                            snapshot.share(),
                            snapshot.target(),
                            snapshot.agreement(),
                            paidContext(fact, snapshot),
                            true);
            List<PointsMove> moves =
                    List.of(
                            new PointsMove(
                                    new AccountKey(
                                            fact.typeCode(),
                                            HolderType.USER,
                                            fact.userNo(),
                                            Bucket.HELD),
                                    -points),
                            new PointsMove(
                                    new AccountKey(
                                            fact.typeCode(),
                                            periodic ? HolderType.STORE : HolderType.RECEIVE,
                                            periodic ? snapshot.share().storeNo() : "PLATFORM",
                                            destination),
                                    points));
            List<FundMove> funds =
                    periodic
                            ? List.of()
                            : List.of(
                                    new FundMove(FundBucket.C, -snapshot.share().minor()),
                                    new FundMove(FundBucket.D, snapshot.share().minor()));
            String nextState = periodic ? (finalized ? "SETTLEABLE" : "IN_STORE") : "PAYABLE";
            posting.post(
                    new Voucher(
                            "PAID:" + fact.eventNo(),
                            fact.typeCode(),
                            type.currency(),
                            type.rate(),
                            moves,
                            funds,
                            List.of(
                                    new PositionMove(
                                            PositionMove.Kind.REDEEM,
                                            no,
                                            number(head, "version"),
                                            "HELD",
                                            nextState,
                                            points,
                                            0,
                                            json.writeValueAsString(next))),
                            0,
                            List.of(),
                            "{}"));
        }
        state.put("paid", true);
        state.set("paidFact", json.readTree(payload));
        facts.saveLifecycle(
                fact,
                json.writeValueAsString(state),
                lifecycle == null ? null : number(lifecycle, "version"));
        facts.record(fact, payload, "APPLIED");
    }

    private ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsContext paidContext(
            LifecycleFact fact, RedeemSnapshot snapshot) {
        var original = snapshot.fundsContext();
        var proof = fact.payment();
        if (proof == null) return original;
        require(
                original.path().name().equals(proof.fundsPath())
                        && original.channel().equals(proof.channel())
                        && original.merchantNo().equals(proof.merchantNo())
                        && original.storeNo().equals(proof.storeNo())
                        && original.accountVersion().equals(proof.accountVersion())
                        && Objects.equals(original.subMerchantId(), proof.subMerchantId())
                        && proof.tradeNo() != null
                        && !proof.tradeNo().isBlank()
                        && proof.outTradeNo() != null
                        && !proof.outTradeNo().isBlank()
                        && proof.orderTotalMinor() >= 0,
                "POINTS_PAYMENT_ROUTE_MISMATCH");
        require(
                snapshot.target().tradeNo() == null
                        || Objects.equals(snapshot.target().tradeNo(), proof.tradeNo()),
                "POINTS_PAYMENT_ROUTE_MISMATCH");
        return new ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsContext(
                original.path(),
                original.channel(),
                original.merchantNo(),
                original.storeNo(),
                original.accountVersion(),
                original.subMerchantId(),
                proof.tradeNo(),
                proof.outTradeNo(),
                proof.orderTotalMinor());
    }

    private void validate(LifecycleFact fact) {
        require(
                fact != null
                        && fact.kind() == FactKind.PAID
                        && fact.eventNo() != null
                        && !fact.eventNo().isBlank()
                        && fact.payloadHash() != null
                        && !fact.payloadHash().isBlank()
                        && fact.typeCode() != null
                        && fact.subOrderNo() != null
                        && fact.userNo() != null
                        && fact.sourceVersion() >= 0
                        && fact.occurredAt() != null
                        && fact.finalGoodsMinor() >= 0
                        && fact.refundedGoodsMinor() == 0,
                "POINTS_PAID_FACT_INVALID");
        long goods = 0;
        var items = new HashSet<String>();
        for (var line : fact.lines()) {
            require(
                    line.itemId() != null
                            && items.add(line.itemId())
                            && line.quantity() >= 0
                            && line.goodsMinor() >= 0
                            && line.refundedGoodsMinor() == 0,
                    "POINTS_PAID_FACT_INVALID");
            goods = Math.addExact(goods, line.goodsMinor());
        }
        require(goods == fact.finalGoodsMinor(), "POINTS_PAID_FACT_INVALID");
    }

    private long number(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }

    private void require(boolean condition, String reason) {
        if (!condition) {
            throw new IllegalArgumentException(reason);
        }
    }
}
