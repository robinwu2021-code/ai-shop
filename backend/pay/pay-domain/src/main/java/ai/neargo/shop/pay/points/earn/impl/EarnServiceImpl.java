package ai.neargo.shop.pay.points.earn.impl;

import ai.neargo.shop.pay.points.earn.EarnService;
import ai.neargo.shop.pay.points.lifecycle.AccountExpiryGuard;
import ai.neargo.shop.pay.points.model.AccountKey;
import ai.neargo.shop.pay.points.model.Bucket;
import ai.neargo.shop.pay.points.model.FundBucket;
import ai.neargo.shop.pay.points.model.FundMove;
import ai.neargo.shop.pay.points.model.HolderType;
import ai.neargo.shop.pay.points.model.PointType;
import ai.neargo.shop.pay.points.model.PointsKeys;
import ai.neargo.shop.pay.points.model.PointsMove;
import ai.neargo.shop.pay.points.model.PositionMove;
import ai.neargo.shop.pay.points.model.Voucher;
import ai.neargo.shop.pay.points.policy.DefaultEarnPolicy;
import ai.neargo.shop.pay.points.policy.EarnPolicy;
import ai.neargo.shop.pay.points.pool.PointsSwitchService.Flow;
import ai.neargo.shop.pay.points.posting.PointsPostingService;
import ai.neargo.shop.pay.points.store.PointsConfigurationStore;
import ai.neargo.shop.pay.points.store.PointsReadStore;
import ai.neargo.shop.pay.points.store.PointsReadStore.Availability;
import ai.neargo.shop.spi.points.PointsEarnPort;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Pool locking makes the last-budget decision and its entire grant atomic, with a recorded skipped
 * result.
 */
@Service
@Transactional("payTxManager")
public class EarnServiceImpl implements EarnService {
    private final PointsReadStore read;
    private final PointsConfigurationStore config;
    private final PointsPostingService posting;
    private final boolean enabled;
    private final AccountExpiryGuard expiry;
    private final EarnPolicy policy = new DefaultEarnPolicy();
    private final ObjectMapper json = new ObjectMapper();

    public EarnServiceImpl(
            PointsReadStore read,
            PointsConfigurationStore config,
            PointsPostingService posting,
            AccountExpiryGuard expiry,
            @Value("${shop.points.enabled:false}") boolean enabled) {
        this.read = read;
        this.config = config;
        this.posting = posting;
        this.enabled = enabled;
        this.expiry = expiry;
    }

    @Override
    public PointsEarnPort.GrantOutcome grant(PointsEarnPort.GrantCommand c) {
        return grant(c, false);
    }

    @Override
    public PointsEarnPort.GrantOutcome grantAccepted(PointsEarnPort.GrantCommand c) {
        return grant(c, true);
    }

    private PointsEarnPort.GrantOutcome grant(PointsEarnPort.GrantCommand c, boolean accepted) {
        if (c == null
                || blank(c.eventNo())
                || blank(c.subOrderNo())
                || blank(c.userNo())
                || blank(c.typeCode())
                || blank(c.storeNo())
                || c.sourceVersion() < 0) {
            throw new IllegalArgumentException("POINTS_EARN_INPUT_INVALID");
        }
        if (c.payment() != null
                && (!c.storeNo().equals(c.payment().storeNo())
                        || !Objects.equals(c.payChannel(), c.payment().channel())
                        || (c.merchantNo() != null
                                && !c.merchantNo().equals(c.payment().merchantNo())))) {
            throw new IllegalArgumentException("POINTS_EARN_PAYMENT_MISMATCH");
        }
        config.lockTypeAndPool(c.typeCode());
        String ref = "GRANT:" + c.subOrderNo();
        String position = PointsKeys.position("G", c.typeCode(), c.subOrderNo());
        var prior = read.position(PositionMove.Kind.GRANT.table(), position);
        if (prior != null) {
            var stored = json.readTree((String) prior.get("payload_json"));
            var original =
                    json.treeToValue(stored.get("command"), PointsEarnPort.GrantCommand.class);
            if (!original.userNo().equals(c.userNo())
                    || !original.typeCode().equals(c.typeCode())
                    || !Objects.equals(original.storeNo(), c.storeNo())
                    || !original.lines().equals(c.lines())
                    || !Objects.equals(original.payChannel(), c.payChannel())
                    || !Objects.equals(original.payScene(), c.payScene())
                    || !Objects.equals(original.merchantNo(), c.merchantNo())
                    || !Objects.equals(original.clientType(), c.clientType())
                    || !Objects.equals(original.payment(), c.payment())) {
                throw new IllegalArgumentException("POINTS_IDEMPOTENCY_CONFLICT");
            }
            return outcome(ref);
        }
        PointType type = read.type(c.typeCode()).orElseThrow();
        var decision =
                policy.calculate(
                        c.lines().stream()
                                .map(
                                        l ->
                                                new EarnPolicy.Line(
                                                        l.itemId(),
                                                        l.quantity(),
                                                        l.goodsCashMinor(),
                                                        l.refundedBeforeGrantMinor(),
                                                        l.rule() == null
                                                                ? EarnPolicy.Mode.FIXED
                                                                : EarnPolicy.Mode.valueOf(
                                                                        l.rule().mode().name()),
                                                        l.rule() == null ? 0 : l.rule().value()))
                                .toList(),
                        type.rate());
        String reason = null;
        boolean finalized = false;
        var fact = config.lockLifecycle(type.typeCode(), c.subOrderNo());
        if (fact != null) {
            var state = json.readTree((String) fact.get("payload_json"));
            if (state.path("cancelled").asBoolean(false)) {
                reason = "POINTS_ORDER_CANCELLED";
            }
            finalized = state.path("finalized").asBoolean(false);
        }
        if (accepted && fact != null) {
            var state = json.readTree((String) fact.get("payload_json"));
            if (state.hasNonNull("paidFact")) {
                var paid = json.treeToValue(state.get("paidFact"), ai.neargo.shop.spi.points.PointsFactPort.LifecycleFact.class);
                if (!c.userNo().equals(paid.userNo())) throw new IllegalArgumentException("POINTS_FACT_SUBJECT_MISMATCH");
                var goods = new java.util.HashMap<String,Long>();
                for (var line : paid.lines()) {
                    if (goods.put(line.itemId(),line.goodsMinor()) != null) throw new IllegalArgumentException("POINTS_EARN_INPUT_INVALID");
                }
                var returned = new java.util.HashMap<String,Long>();
                var refunds = read.confirmedRefunds(c.typeCode(),c.subOrderNo());
                if (refunds.size()>10000) throw new IllegalArgumentException("POINTS_REFUND_HISTORY_LIMIT");
                for (var row : refunds) {
                    var refund = json.readValue((String) row.get("payload_json"), ai.neargo.shop.pay.points.refund.impl.PointsRefundPreparationServiceImpl.RefundSnapshot.class);
                    if (!c.userNo().equals(refund.command().userNo())) throw new IllegalArgumentException("POINTS_FACT_SUBJECT_MISMATCH");
                    for (var line : refund.command().lines()) returned.merge(line.itemId(),line.cumulativeGoodsMinor(),Math::max);
                }
                decision = ai.neargo.shop.pay.points.policy.DelayedEarnPolicy.apply(decision,goods,returned,type.rate());
            }
        }
        if (reason == null
                && !accepted
                && (!enabled
                        || !read.enabled(
                                new Availability(
                                        c.typeCode(),
                                        c.userNo(),
                                        c.storeNo(),
                                        c.merchantNo(),
                                        type.market(),
                                        c.payScene(),
                                        c.clientType(),
                                        Flow.EARN)))) {
            reason = "POINTS_DISABLED";
        }
        if (reason == null && type.status() != PointType.Status.ACTIVE
                && !(accepted && type.status() == PointType.Status.DRAINING)) {
            reason = "POINTS_TYPE_NOT_ACTIVE";
        }
        if (reason == null && ("OFFLINE".equals(c.payChannel()) || c.payChannel() == null
                        || (accepted && !"ONLINE".equals(c.payScene())))) {
            reason = "POINTS_OFFLINE";
        }
        var account = read.currentUser(c.typeCode(), c.userNo());
        if (reason == null && account.frozen()) {
            reason = "POINTS_FROZEN";
        }
        if (reason == null && expiry.overdue(account, System.currentTimeMillis())) {
            throw new IllegalArgumentException("POINTS_ACCOUNT_EXPIRY_PENDING");
        }
        if (reason == null && decision.grantedPoints() == 0) {
            reason = decision.expectedPoints()>0 ? "POINTS_REFUNDED_BEFORE_GRANT" : "POINTS_RULE_ZERO";
        }
        long amount = type.rate().toMinor(decision.grantedPoints());
        if (reason == null && read.pool(c.typeCode()).freeU() < amount) {
            // Accepted source events remain retryable; no permanent SKIPPED voucher consumes this promise.
            if (accepted) throw new IllegalStateException("POINTS_POOL_INSUFFICIENT");
            reason = "POINTS_POOL_INSUFFICIENT";
        }
        boolean granted = reason == null;
        long actual = granted ? decision.grantedPoints() : 0;
        var result =
                new PointsEarnPort.GrantOutcome(
                        granted
                                ? PointsEarnPort.GrantStatus.GRANTED
                                : PointsEarnPort.GrantStatus.SKIPPED,
                        actual,
                        decision.grantedPoints(),
                        reason,
                        null);
        String resultJson = json.writeValueAsString(result);
        String payload =
                json.writeValueAsString(
                        new GrantSnapshot(
                                c.subOrderNo(),
                                c.userNo(),
                                c,
                                decision,
                                result,
                                finalized ? actual : 0,
                                0,
                                0,
                                0,
                                0));
        List<PointsMove> points =
                granted
                        ? List.of(
                                new PointsMove(
                                        new AccountKey(
                                                type.typeCode(),
                                                HolderType.ISSUE,
                                                "PLATFORM",
                                                Bucket.ISSUABLE),
                                        -actual),
                                new PointsMove(
                                        new AccountKey(
                                                type.typeCode(),
                                                HolderType.USER,
                                                c.userNo(),
                                                finalized ? Bucket.AVAILABLE : Bucket.PENDING),
                                        actual))
                        : List.of();
        List<FundMove> funds =
                granted
                        ? List.of(
                                new FundMove(FundBucket.U, -amount),
                                new FundMove(FundBucket.C, amount))
                        : List.of();
        posting.post(
                new Voucher(
                        ref,
                        type.typeCode(),
                        type.currency(),
                        type.rate(),
                        points,
                        funds,
                        List.of(
                                new PositionMove(
                                        PositionMove.Kind.GRANT,
                                        position,
                                        -1,
                                        "NEW",
                                        granted ? "GRANTED" : "SKIPPED",
                                        actual,
                                        0,
                                        payload)),
                        0,
                        List.of(),
                        resultJson));
        return outcome(ref);
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private PointsEarnPort.GrantOutcome outcome(String ref) {
        var voucher = read.voucher(ref);
        var r =
                json.readValue(
                        (String) voucher.get("result_json"), PointsEarnPort.GrantOutcome.class);
        return new PointsEarnPort.GrantOutcome(
                r.status(),
                r.grantedPoints(),
                r.expectedPoints(),
                r.reason(),
                (String) voucher.get("voucher_no"));
    }

    public record GrantSnapshot(
            String subOrderNo,
            String userNo,
            PointsEarnPort.GrantCommand command,
            EarnPolicy.EarnDecision decision,
            PointsEarnPort.GrantOutcome outcome,
            long activatedPoints,
            long reclaimedPending,
            long reclaimedAvailable,
            long collectedMinor,
            long waivedPoints,
            Map<String, Long> processedByLine,
            Map<String, Long> refundedGoodsByLine) {
        public GrantSnapshot {
            processedByLine = processedByLine == null ? Map.of() : Map.copyOf(processedByLine);
            refundedGoodsByLine =
                    refundedGoodsByLine == null ? Map.of() : Map.copyOf(refundedGoodsByLine);
        }

        public GrantSnapshot(
                String subOrderNo,
                String userNo,
                PointsEarnPort.GrantCommand command,
                EarnPolicy.EarnDecision decision,
                PointsEarnPort.GrantOutcome outcome,
                long activatedPoints,
                long reclaimedPending,
                long reclaimedAvailable,
                long collectedMinor,
                long waivedPoints) {
            this(
                    subOrderNo,
                    userNo,
                    command,
                    decision,
                    outcome,
                    activatedPoints,
                    reclaimedPending,
                    reclaimedAvailable,
                    collectedMinor,
                    waivedPoints,
                    Map.of(),
                    Map.of());
        }
    }
}
