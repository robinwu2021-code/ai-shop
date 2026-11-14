package ai.neargo.shop.pay.points.redeem.impl;

import ai.neargo.shop.pay.points.agreement.AgreementService;
import ai.neargo.shop.pay.points.lifecycle.AccountExpiryGuard;
import ai.neargo.shop.pay.points.model.AccountKey;
import ai.neargo.shop.pay.points.model.AgreementSnapshot;
import ai.neargo.shop.pay.points.model.Bucket;
import ai.neargo.shop.pay.points.model.HolderType;
import ai.neargo.shop.pay.points.model.PointType;
import ai.neargo.shop.pay.points.model.PointsKeys;
import ai.neargo.shop.pay.points.model.PointsMove;
import ai.neargo.shop.pay.points.model.PositionMove;
import ai.neargo.shop.pay.points.model.RedeemRate;
import ai.neargo.shop.pay.points.model.ReserveMove;
import ai.neargo.shop.pay.points.model.Voucher;
import ai.neargo.shop.pay.points.policy.DefaultRedeemPolicy;
import ai.neargo.shop.pay.points.policy.RedeemPolicy;
import ai.neargo.shop.pay.points.pool.PointsSwitchService.Flow;
import ai.neargo.shop.pay.points.port.PointsFundsGateway;
import ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsContext;
import ai.neargo.shop.spi.pay.FundsPath;
import ai.neargo.shop.pay.points.posting.PointsPostingService;
import ai.neargo.shop.pay.points.redeem.RedeemService;
import ai.neargo.shop.pay.points.store.PointsConfigurationStore;
import ai.neargo.shop.pay.points.store.PointsReadStore;
import ai.neargo.shop.pay.points.store.PointsReadStore.Availability;
import ai.neargo.shop.spi.points.PointsRedeemPort.HoldCommand;
import ai.neargo.shop.spi.points.PointsRedeemPort.HoldOutcome;
import ai.neargo.shop.spi.points.PointsRedeemPort.RedeemQuery;
import ai.neargo.shop.spi.points.PointsRedeemPort.RedeemQuote;
import ai.neargo.shop.spi.points.PointsRedeemPort.ReleaseCommand;
import ai.neargo.shop.spi.points.PointsRedeemPort.Share;
import ai.neargo.shop.spi.points.PointsRedeemPort.Target;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** A short pay transaction freezes the agreement, user rights, and complete B reserve together. */
@Service
@Transactional("payTxManager")
public class RedeemServiceImpl implements RedeemService {
    private final PointsReadStore read;
    private final PointsConfigurationStore config;
    private final AgreementService agreements;
    private final PointsPostingService posting;
    private final ObjectProvider<PointsFundsGateway> gateway;
    private final boolean enabled;
    @Value("${shop.points.default-participation-enabled:${shop.points.unified-earning-enabled:false}}")
    private boolean defaultParticipation;
    private final AccountExpiryGuard expiry;
    private final RedeemPolicy policy = new DefaultRedeemPolicy();
    private final ObjectMapper json = new ObjectMapper();

    public RedeemServiceImpl(
            PointsReadStore read,
            PointsConfigurationStore config,
            AgreementService agreements,
            PointsPostingService posting,
            ObjectProvider<PointsFundsGateway> gateway,
            AccountExpiryGuard expiry,
            @Value("${shop.points.enabled:false}") boolean enabled) {
        this.read = read;
        this.config = config;
        this.agreements = agreements;
        this.posting = posting;
        this.gateway = gateway;
        this.enabled = enabled;
        this.expiry = expiry;
    }

    @Override
    public RedeemQuote quote(RedeemQuery q) {
        validate(q);
        PointType type = q.typeCode()==null?read.activeType(q.market()).orElse(null):read.type(q.typeCode()).orElse(null);
        if (type == null) {
            return new RedeemQuote(null, 0, 0, 0, 0, "POINTS_TYPE_NOT_ACTIVE", List.of());
        }
        if(q.typeCode()!=null && (type.status()!=PointType.Status.ACTIVE||!type.market().equals(q.market()))) throw new IllegalArgumentException("POINTS_TYPE_NOT_ACTIVE");
        return evaluate(q, type, false).quote();
    }

    @Override
    public HoldOutcome hold(HoldCommand c) {
        if (c.orderNo() == null || c.orderNo().isBlank()) {
            throw new IllegalArgumentException("POINTS_ORDER_REQUIRED");
        }
        RedeemQuery q =
                new RedeemQuery(
                        c.market(),
                        c.userNo(),
                        c.wantPoints(),
                        c.payMode(),
                        c.clientType(),
                        c.targets(),c.typeCode());
        validate(q);
        String root = ai.neargo.shop.pay.points.operation.PointsOperationKeys.holdRoot(c.orderNo(),c.typeCode(),c.operationKey());
        Map<String, Object> prior = read.position(PositionMove.Kind.REDEEM.table(), root);
        PointType type =
                prior == null
                        ? (c.typeCode()==null?read.activeType(c.market()).orElse(null):read.type(c.typeCode()).orElse(null))
                        : read.type((String) prior.get("type_code")).orElseThrow();
        if (type == null) {
            if (c.acceptedPrice() != null)
                throw new IllegalArgumentException("POINTS_REQUOTE_REQUIRED");
            return new HoldOutcome(
                    root, null, 0, 0, "SKIPPED", "POINTS_TYPE_NOT_ACTIVE", List.of());
        }
        config.lockTypeAndPool(type.typeCode());
        prior = config.lockRedeemPosition(root);
        if (prior != null) {
            HoldSnapshot old =
                    json.readValue((String) prior.get("payload_json"), HoldSnapshot.class);
            if (!old.command().equals(c)) {
                throw new IllegalArgumentException("POINTS_IDEMPOTENCY_CONFLICT");
            }
            if ("RELEASED".equals(prior.get("state"))) {
                return releaseOutcome(old);
            }
            return old.outcome();
        }
        type = read.type(type.typeCode()).orElseThrow();
        if(c.operationKey()!=null) {
            if(type.status()!=PointType.Status.ACTIVE || !type.market().equals(c.market())) throw new IllegalArgumentException("POINTS_TYPE_NOT_ACTIVE");
            if(read.position(PositionMove.Kind.REDEEM.table(),root(c.orderNo()))!=null) throw new IllegalArgumentException("POINTS_LEGACY_OPERATION_CONFLICT");
            for(var target:c.targets()) if(read.position(PositionMove.Kind.REDEEM.table(),PointsKeys.position("R",type.typeCode(),target.subOrderNo()))!=null) throw new IllegalArgumentException("POINTS_OPERATION_CONFLICT");
        }
        Evaluation e = evaluate(q, type, true);
        RedeemQuote quote = e.quote();
        var acceptedPrice = c.acceptedPrice();
        if (acceptedPrice != null
                && (!java.util.Objects.equals(acceptedPrice.typeCode(), quote.typeCode())
                        || acceptedPrice.pointsUnit() != quote.pointsUnit()
                        || acceptedPrice.minorUnit() != quote.minorUnit()
                        || acceptedPrice.points() != quote.points()
                        || acceptedPrice.minor() != quote.minor()
                        || !acceptedPrice.shares().equals(quote.shares()))) {
            throw new IllegalArgumentException("POINTS_REQUOTE_REQUIRED");
        }
        HoldOutcome outcome =
                new HoldOutcome(
                        root,
                        type.typeCode(),
                        quote.points(),
                        quote.minor(),
                        quote.points() > 0 ? "HELD" : "SKIPPED",
                        quote.reason(),
                        quote.shares());
        List<PositionMove> positions = new ArrayList<>();
        List<ReserveMove> reserves = new ArrayList<>();
        for (Accepted accepted : e.accepted()) {
            Share share =
                    quote.shares().stream()
                            .filter(s -> s.subOrderNo().equals(accepted.target().subOrderNo()))
                            .findFirst()
                            .orElse(null);
            if (share == null) {
                continue;
            }
            String pos = PointsKeys.position("R", type.typeCode(), share.subOrderNo());
            positions.add(
                    new PositionMove(
                            PositionMove.Kind.REDEEM,
                            pos,
                            -1,
                            "NEW",
                            "HELD",
                            share.points(),
                            0,
                            json.writeValueAsString(
                                    new RedeemSnapshot(
                                            share.subOrderNo(),
                                            c.userNo(),
                                            c.orderNo(),
                                            share,
                                            accepted.target(),
                                            accepted.agreement(),
                                            accepted.context(),
                                            false))));
            reserves.add(new ReserveMove(pos, pos, -1, share.minor(), share.minor(), 0, 0));
        }
        positions.add(
                new PositionMove(
                        PositionMove.Kind.REDEEM,
                        root,
                        -1,
                        "NEW",
                        outcome.state(),
                        outcome.points(),
                        0,
                        json.writeValueAsString(new HoldSnapshot(c, outcome))));
        List<PointsMove> moves =
                outcome.points() == 0
                        ? List.of()
                        : List.of(
                                userMove(type, c.userNo(), Bucket.AVAILABLE, -outcome.points()),
                                userMove(type, c.userNo(), Bucket.HELD, outcome.points()));
        posting.post(
                new Voucher(
                        ai.neargo.shop.pay.points.operation.PointsOperationKeys.reference("HOLD",c.orderNo(),c.typeCode(),c.operationKey()),
                        type.typeCode(),
                        type.currency(),
                        type.rate(),
                        moves,
                        List.of(),
                        positions,
                        outcome.minor(),
                        List.of(),
                        json.writeValueAsString(outcome),
                        reserves));
        return outcome;
    }

    @Override
    public HoldOutcome release(ReleaseCommand c) {
        if (c.orderNo() == null || c.orderNo().isBlank() || c.sourceVersion() < 0) {
            throw new IllegalArgumentException("POINTS_RELEASE_INPUT_INVALID");
        }
        String root = ai.neargo.shop.pay.points.operation.PointsOperationKeys.holdRoot(c.orderNo(),c.typeCode(),c.operationKey());
        var head = read.position(PositionMove.Kind.REDEEM.table(), root);
        if (head == null) {
            return new HoldOutcome(root, null, 0, 0, "ABSENT", null, List.of());
        }
        PointType type = read.type((String) head.get("type_code")).orElseThrow();
        config.lockTypeAndPool(type.typeCode());
        head = config.lockRedeemPosition(root);
        HoldSnapshot held = json.readValue((String) head.get("payload_json"), HoldSnapshot.class);
        if (!"HELD".equals(head.get("state"))) {
            if ("SKIPPED".equals(head.get("state"))) {
                return held.outcome();
            }
            if(!"RELEASED".equals(head.get("state"))) throw new IllegalArgumentException("POINTS_HOLD_ALREADY_PAID");
            return releaseOutcome(held);
        }
        if(c.operationKey()!=null && (!Objects.equals(c.typeCode(),held.command().typeCode()) || !Objects.equals(c.operationKey(),held.command().operationKey()))) throw new IllegalArgumentException("POINTS_OPERATION_CONFLICT");
        List<PositionMove> positions = new ArrayList<>();
        List<ReserveMove> reserves = new ArrayList<>();
        long releasedPoints = 0, releasedMinor = 0;
        for (Share share : held.outcome().shares()) {
            String pos = PointsKeys.position("R", type.typeCode(), share.subOrderNo());
            var child = config.lockRedeemPosition(pos);
            if (child == null) {
                throw new IllegalArgumentException("POINTS_HOLD_POSITION_MISSING");
            }
            var snapshot = json.readValue((String) child.get("payload_json"), RedeemSnapshot.class);
            if (!held.command().userNo().equals(snapshot.userNo())
                    || !c.orderNo().equals(snapshot.orderNo())
                    || num(child, "original_points") != share.points()) {
                throw new IllegalArgumentException("POINTS_HOLD_POSITION_MISMATCH");
            }
            if ("RELEASED".equals(child.get("state"))) {
                continue;
            }
            if (!"HELD".equals(child.get("state"))) {
                throw new IllegalArgumentException("POINTS_HOLD_ALREADY_PAID");
            }
            var reserve = read.reserve(pos);
            positions.add(
                    new PositionMove(
                            PositionMove.Kind.REDEEM,
                            pos,
                            num(child, "version"),
                            "HELD",
                            "RELEASED",
                            share.points(),
                            share.points(),
                            (String) child.get("payload_json")));
            reserves.add(
                    new ReserveMove(
                            pos,
                            pos,
                            num(reserve, "version"),
                            share.minor(),
                            -share.minor(),
                            0,
                            share.minor()));
            releasedPoints = Math.addExact(releasedPoints, share.points());
            releasedMinor = Math.addExact(releasedMinor, share.minor());
        }
        HoldOutcome result = releaseOutcome(held);
        positions.add(
                new PositionMove(
                        PositionMove.Kind.REDEEM,
                        root,
                        num(head, "version"),
                        "HELD",
                        "RELEASED",
                        held.outcome().points(),
                        Math.subtractExact(held.outcome().points(), num(head, "processed_points")),
                        json.writeValueAsString(held)));
        var moves =
                releasedPoints == 0
                        ? List.<PointsMove>of()
                        : List.of(
                                userMove(
                                        type,
                                        held.command().userNo(),
                                        Bucket.HELD,
                                        -releasedPoints),
                                userMove(
                                        type,
                                        held.command().userNo(),
                                        Bucket.AVAILABLE,
                                        releasedPoints));
        posting.post(
                new Voucher(
                        ai.neargo.shop.pay.points.operation.PointsOperationKeys.reference("RELEASE",c.orderNo(),c.typeCode(),c.operationKey()),
                        type.typeCode(),
                        type.currency(),
                        type.rate(),
                        moves,
                        List.of(),
                        positions,
                        -releasedMinor,
                        List.of(),
                        json.writeValueAsString(result),
                        reserves));
        return result;
    }

    private Evaluation evaluate(RedeemQuery q, PointType type, boolean authoritative) {
        String reason = null;
        if (!enabled || !read.enabled(type.typeCode(), q.userNo(), null)) {
            reason = "POINTS_DISABLED";
        } else if (type.status() != PointType.Status.ACTIVE
                && type.status() != PointType.Status.DRAINING) {
            reason = "POINTS_TYPE_NOT_ACTIVE";
        } else if ("OFFLINE".equals(q.payMode())) {
            reason = "POINTS_OFFLINE";
        }
        var account =
                authoritative
                        ? read.currentUser(type.typeCode(), q.userNo())
                        : read.account(type.typeCode(), HolderType.USER, q.userNo());
        if (reason == null && account.frozen()) {
            reason = "POINTS_FROZEN";
        }
        if (reason == null) {
            try {
                if (expiry.overdue(account, System.currentTimeMillis())) reason = "POINTS_EXPIRED";
            } catch (IllegalStateException pending) {
                if (!"POINTS_AVAILABILITY_PROJECTION_PENDING".equals(pending.getMessage())) throw pending;
                reason = pending.getMessage();
            }
        }
        List<Accepted> accepted = new ArrayList<>();
        if (reason == null) {
            for (Target target : q.targets()) {
                var lifecycle =
                        authoritative
                                ? config.lockLifecycle(type.typeCode(), target.subOrderNo())
                                : read.lifecycle(type.typeCode(), target.subOrderNo());
                if (lifecycle != null) {
                    var state = json.readTree((String) lifecycle.get("payload_json"));
                    if (state.path("cancelled").asBoolean(false)) {
                        reason = "POINTS_ORDER_CANCELLED";
                        break;
                    }
                    if (state.path("paid").asBoolean(false)
                            || state.path("finalized").asBoolean(false)) {
                        reason = "POINTS_SOURCE_ALREADY_PAID";
                        break;
                    }
                }
                if (!read.enabled(
                        new Availability(
                                type.typeCode(),
                                q.userNo(),
                                target.storeNo(),
                                target.merchantNo(),
                                type.market(),
                                q.payMode(),
                                q.clientType(),
                                Flow.REDEEM))) {
                    reason = "POINTS_DISABLED";
                    break;
                }
                AgreementSnapshot agreement = agreements.active(type.typeCode(), target.storeNo());
                if(agreement==null && defaultParticipation) agreement=new AgreementSnapshot(
                        "POLICY:"+type.typeCode()+":"+target.storeNo(),type.typeCode(),target.merchantNo(),target.storeNo(),
                        "SYSTEM_POLICY","ALL_STORES_V1","ALL_STORES_V1","平台统一积分规则，默认全部门店参与",
                        "PER_ORDER",target.path(),target.channel(),target.accountVersion(),"ACTIVE");
                if (agreement == null || !agreement.merchantNo().equals(target.merchantNo())) {
                    reason = "POINTS_STORE_NOT_ACCEPTING";
                    break;
                }
                if (!Objects.equals(agreement.path(), target.path())
                        || !Objects.equals(agreement.channel(), target.channel())
                        || !Objects.equals(agreement.accountVersion(), target.accountVersion())) {
                    reason = "POINTS_FUNDS_CONTEXT_CHANGED";
                    break;
                }
                FundsContext context =
                        new FundsContext(
                                FundsPath.valueOf(agreement.path()),
                                agreement.channel(),
                                agreement.merchantNo(),
                                agreement.storeNo(),
                                agreement.accountVersion(),
                                target.subMerchantId(),
                                target.tradeNo(),
                                target.subOrderNo(),
                                target.goodsMinor());
                var funds = gateway.getIfAvailable();
                if (funds == null) {
                    reason = "POINTS_CAPABILITY_UNSUPPORTED";
                    break;
                }
                var cap = funds.capabilities(context);
                boolean supported =
                        "PERIODIC".equals(agreement.settleMode())
                                ? cap.transfer()
                                : context.path() == FundsPath.DIRECT
                                        ? cap.subsidy()
                                        : cap.internalReplenishment();
                if (!supported) {
                    reason = "POINTS_CAPABILITY_UNSUPPORTED";
                    break;
                }
                accepted.add(new Accepted(target, agreement, context));
            }
        }
        RedeemPolicy.Decision decision =
                reason == null
                        ? policy.calculate(
                                q.wantPoints(),
                                account.points(Bucket.AVAILABLE),
                                type.deductRatioBp(),
                                q.targets().stream()
                                        .map(
                                                t ->
                                                        new RedeemPolicy.Target(
                                                                t.subOrderNo(),
                                                                t.storeNo(),
                                                                t.goodsMinor(),
                                                                true))
                                        .toList(),
                                type.rate())
                        : new RedeemPolicy.Decision(0, 0, List.of());
        if (reason == null && decision.points() == 0) {
            reason = "POINTS_NO_REDEEMABLE_BALANCE";
        }
        if (reason == null && read.pool(type.typeCode()).freeB() < decision.minor()) {
            reason = "POINTS_B_RESERVE_INSUFFICIENT";
        }
        List<Share> shares = new ArrayList<>();
        if (reason == null) {
            for (var s : decision.shares()) {
                Target target =
                        q.targets().stream()
                                .filter(t -> t.subOrderNo().equals(s.subOrderNo()))
                                .findFirst()
                                .orElseThrow();
                shares.add(
                        new Share(
                                s.subOrderNo(),
                                target.merchantNo(),
                                s.storeNo(),
                                s.points(),
                                s.minor()));
            }
        }
        return new Evaluation(
                new RedeemQuote(
                        type.typeCode(),
                        type.rate().pointsUnit(),
                        type.rate().minorUnit(),
                        reason == null ? decision.points() : 0,
                        reason == null ? decision.minor() : 0,
                        reason,
                        shares),
                accepted);
    }

    private void validate(RedeemQuery q) {
        if (q.market() == null
                || q.userNo() == null
                || q.userNo().isBlank()
                || q.wantPoints() < 0
                || q.payMode() == null
                || q.clientType() == null
                || q.targets().isEmpty()) {
            throw new IllegalArgumentException("POINTS_REDEEM_INPUT_INVALID");
        }
        policy.calculate(
                0,
                0,
                0,
                q.targets().stream()
                        .map(
                                t ->
                                        new RedeemPolicy.Target(
                                                t.subOrderNo(), t.storeNo(), t.goodsMinor(), true))
                        .toList(),
                new RedeemRate(1, 1));
        for (Target t : q.targets()) {
            long sum = 0;
            for (var item : t.items()) {
                if (item.itemId() == null || item.goodsMinor() < 0) {
                    throw new IllegalArgumentException("POINTS_ITEMS_INVALID");
                }
                sum = Math.addExact(sum, item.goodsMinor());
            }
            if (sum != t.goodsMinor()) {
                throw new IllegalArgumentException("POINTS_ITEM_TOTAL_MISMATCH");
            }
        }
    }

    private PointsMove userMove(PointType type, String user, Bucket bucket, long points) {
        return new PointsMove(
                new AccountKey(type.typeCode(), HolderType.USER, user, bucket), points);
    }

    private HoldOutcome releaseOutcome(HoldSnapshot h) {
        HoldOutcome o = h.outcome();
        return new HoldOutcome(
                o.holdNo(), o.typeCode(), o.points(), o.minor(), "RELEASED", null, o.shares());
    }

    private String root(String order) {
        return PointsKeys.position("H", "ORDER", order);
    }

    private long num(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }

    public record HoldSnapshot(HoldCommand command, HoldOutcome outcome) {}

    public record RedeemSnapshot(
            String subOrderNo,
            String userNo,
            String orderNo,
            Share share,
            Target target,
            AgreementSnapshot agreement,
            FundsContext fundsContext,
            boolean finalSnapshotReceived) {}

    private record Accepted(Target target, AgreementSnapshot agreement, FundsContext context) {}

    private record Evaluation(RedeemQuote quote, List<Accepted> accepted) {}
}
