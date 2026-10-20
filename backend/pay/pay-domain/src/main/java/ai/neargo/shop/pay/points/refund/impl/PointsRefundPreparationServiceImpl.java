package ai.neargo.shop.pay.points.refund.impl;

import ai.neargo.shop.pay.points.earn.impl.EarnServiceImpl.GrantSnapshot;
import ai.neargo.shop.pay.points.model.AccountKey;
import ai.neargo.shop.pay.points.model.Bucket;
import ai.neargo.shop.pay.points.model.HolderType;
import ai.neargo.shop.pay.points.model.PointsKeys;
import ai.neargo.shop.pay.points.model.PointsMove;
import ai.neargo.shop.pay.points.model.PositionMove;
import ai.neargo.shop.pay.points.model.Voucher;
import ai.neargo.shop.pay.points.policy.CashClawbackPolicy;
import ai.neargo.shop.pay.points.policy.RefundRatioPolicy;
import ai.neargo.shop.pay.points.port.PointsFundsGateway;
import ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsCommand;
import ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsContext;
import ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsPath;
import ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsPurpose;
import ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsResult;
import ai.neargo.shop.pay.points.posting.PointsPostingService;
import ai.neargo.shop.pay.points.redeem.impl.RedeemServiceImpl.RedeemSnapshot;
import ai.neargo.shop.pay.points.refund.PointsRefundCompensationService.CompensationSnapshot;
import ai.neargo.shop.pay.points.refund.PointsRefundPreparationService;
import ai.neargo.shop.pay.points.statement.StoreStatementService;
import ai.neargo.shop.pay.points.store.PointsConfigurationStore;
import ai.neargo.shop.pay.points.store.PointsReadStore;
import ai.neargo.shop.spi.points.PointsFactPort.LifecycleFact;
import ai.neargo.shop.spi.points.PointsRefundPort.RefundAbort;
import ai.neargo.shop.spi.points.PointsRefundPort.RefundCommand;
import ai.neargo.shop.spi.points.PointsRefundPort.RefundConfirmation;
import ai.neargo.shop.spi.points.PointsRefundPort.RefundPreparation;
import ai.neargo.shop.spi.points.PointsRefundPort.ZeroCashCompletionFact;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * No refund promise is released here: P/A moves to H and all funding remains unchanged until
 * confirmation.
 */
@Service
@Transactional("payTxManager")
public class PointsRefundPreparationServiceImpl implements PointsRefundPreparationService {
    private final PointsReadStore read;
    private final PointsConfigurationStore configuration;
    private final PointsPostingService writer;
    private final ObjectProvider<PointsFundsGateway> funds;
    private final ObjectProvider<StoreStatementService> statements;
    private final ObjectMapper json = new ObjectMapper();
    private final RefundRatioPolicy ratios = new RefundRatioPolicy();

    public PointsRefundPreparationServiceImpl(
            PointsReadStore read,
            PointsConfigurationStore configuration,
            PointsPostingService posting,
            ObjectProvider<PointsFundsGateway> funds,
            ObjectProvider<StoreStatementService> statements) {
        this.read = read;
        this.configuration = configuration;
        this.writer = posting;
        this.funds = funds;
        this.statements = statements;
    }

    @Override
    public RefundPreparation prepare(RefundCommand command) {
        Map<String, Long> cumulative = validate(command);
        configuration.lockTypeAndPool(command.typeCode());
        var type = read.type(command.typeCode()).orElseThrow();
        require(type.currency().equals(command.currency()), "POINTS_REFUND_CURRENCY_MISMATCH");
        String no = PointsKeys.position("F", command.typeCode(), command.afterSaleNo());
        var prior = read.position(PositionMove.Kind.REFUND.table(), no);
        if (prior != null) {
            var saved = json.readValue((String) prior.get("payload_json"), RefundSnapshot.class);
            require(saved.command().equals(command), "POINTS_REFUND_INPUT_CONFLICT");
            require(!"ABORTED".equals(prior.get("state")), "POINTS_REFUND_PREPARATION_ABORTED");
            return saved.preparation();
        }
        require(
                read.openRefunds(command.typeCode(), command.subOrderNo()).isEmpty(),
                "POINTS_REFUND_SOURCE_BUSY");
        var responsibility = configuration.lockLifecycle(command.typeCode(), command.subOrderNo());
        require(
                responsibility == null
                        || !json.readTree((String) responsibility.get("payload_json"))
                                .hasNonNull("refundClosedFact"),
                "POINTS_REFUND_RESPONSIBILITY_CLOSED");
        String grantNo = PointsKeys.position("G", command.typeCode(), command.subOrderNo());
        String redeemNo = PointsKeys.position("R", command.typeCode(), command.subOrderNo());
        var boundRedemption = read.position(PositionMove.Kind.REDEEM.table(), redeemNo);
        if (boundRedemption != null && boundRedemption.get("statement_no") != null) {
            var statementService = statements.getIfAvailable();
            require(statementService != null, "POINTS_STATEMENT_SERVICE_REQUIRED");
            statementService.reopenForRefund(redeemNo);
        }
        var grantHead = read.position(PositionMove.Kind.GRANT.table(), grantNo);
        var redeemHead = read.position(PositionMove.Kind.REDEEM.table(), redeemNo);
        if (grantHead == null && redeemHead == null) {
            // An earning budget shortage must never block refunding a verified cash purchase.
            validateUnfundedPaidSource(command, cumulative, responsibility);
        }
        long due = 0;
        long pendingDue = 0;
        Map<String, Long> dueByLine = new HashMap<>();
        List<PositionMove> positions = new ArrayList<>();
        String grantState = null;
        String redeemState = null;
        if (grantHead != null) {
            GrantSnapshot grant =
                    json.readValue((String) grantHead.get("payload_json"), GrantSnapshot.class);
            require(command.userNo().equals(grant.userNo()), "POINTS_REFUND_SUBJECT_MISMATCH");
            require(
                    grant.command().storeNo().equals(command.payment().storeNo())
                            && (grant.command().merchantNo() == null
                                    || grant.command()
                                            .merchantNo()
                                            .equals(command.payment().merchantNo())),
                    "POINTS_REFUND_STORE_MISMATCH");
            require(
                    redeemHead != null || grant.command().payment() != null,
                    "POINTS_REFUND_PAYMENT_SNAPSHOT_REQUIRED");
            if (grant.command().payment() != null) {
                require(
                        grant.command().payment().equals(command.payment()),
                        "POINTS_REFUND_STORE_MISMATCH");
            }
            require(
                    grant.decision().lines().size() == cumulative.size(),
                    "POINTS_REFUND_LINES_INVALID");
            grantState = (String) grantHead.get("state");
            require(!"REFUND_PREPARED".equals(grantState), "POINTS_REFUND_SOURCE_BUSY");
            boolean issued = number(grantHead, "original_points") > 0;
            for (var line : grant.decision().lines()) {
                Long refunded = cumulative.get(line.itemId());
                require(
                        refunded != null
                                && refunded
                                        >= grant.refundedGoodsByLine()
                                                .getOrDefault(line.itemId(), 0L)
                                && refunded <= line.originalGoodsMinor(),
                        "POINTS_REFUND_LINES_INVALID");
                long delta =
                        ratios.reclaimDelta(
                                type.rate(),
                                issued ? line.grantedPoints() : 0,
                                line.originalGoodsMinor(),
                                line.refundedBeforeGrantMinor(),
                                line.grantBasisMinor(),
                                refunded,
                                issued
                                        ? grant.processedByLine().getOrDefault(line.itemId(), 0L)
                                        : 0);
                dueByLine.put(line.itemId(), delta);
                due = Math.addExact(due, delta);
            }
            pendingDue =
                    Math.min(
                            due,
                            Math.subtractExact(
                                    Math.subtractExact(
                                            number(grantHead, "original_points"),
                                            grant.activatedPoints()),
                                    grant.reclaimedPending()));
            require(pendingDue >= 0, "POINTS_GRANT_COUNTER_INVALID");
            if (issued) {
                positions.add(freeze(PositionMove.Kind.GRANT, grantNo, grantHead));
            }
        }
        long returned = 0;
        if (redeemHead != null) {
            var redemption =
                    json.readValue((String) redeemHead.get("payload_json"), RedeemSnapshot.class);
            require(command.userNo().equals(redemption.userNo()), "POINTS_REFUND_SUBJECT_MISMATCH");
            var payment = command.payment();
            var context = redemption.fundsContext();
            require(
                    context.storeNo().equals(payment.storeNo())
                            && context.merchantNo().equals(payment.merchantNo())
                            && context.path().name().equals(payment.fundsPath())
                            && context.channel().equals(payment.channel())
                            && context.accountVersion().equals(payment.accountVersion())
                            && java.util.Objects.equals(
                                    context.subMerchantId(), payment.subMerchantId())
                            && java.util.Objects.equals(context.tradeNo(), payment.tradeNo())
                            && java.util.Objects.equals(context.outTradeNo(), payment.outTradeNo())
                            && context.orderTotalMinor() == payment.orderTotalMinor(),
                    "POINTS_REFUND_STORE_MISMATCH");
            redeemState = (String) redeemHead.get("state");
            require(
                    Set.of("PAYABLE", "PAID", "IN_STORE", "SETTLEABLE").contains(redeemState),
                    "POINTS_REFUND_SOURCE_BUSY");
            var lifecycle = read.lifecycle(command.typeCode(), command.subOrderNo());
            require(lifecycle != null, "POINTS_REFUND_PAID_SNAPSHOT_REQUIRED");
            var paidNode = json.readTree((String) lifecycle.get("payload_json")).get("paidFact");
            require(paidNode != null, "POINTS_REFUND_PAID_SNAPSHOT_REQUIRED");
            var paid = json.treeToValue(paidNode, LifecycleFact.class);
            require(
                    paid.userNo().equals(command.userNo())
                            && paid.lines().size() == cumulative.size(),
                    "POINTS_REFUND_LINES_INVALID");
            for (var line : paid.lines()) {
                require(
                        cumulative.containsKey(line.itemId())
                                && cumulative.get(line.itemId()) <= line.goodsMinor(),
                        "POINTS_REFUND_LINES_INVALID");
            }
            returned =
                    ratios.redemptionDelta(
                            type.rate(),
                            number(redeemHead, "original_points"),
                            paid.finalGoodsMinor(),
                            command.cumulativeGoodsMinor(),
                            number(redeemHead, "processed_points"));
            positions.add(freeze(PositionMove.Kind.REDEEM, redeemNo, redeemHead));
        }
        var account = read.account(command.typeCode(), HolderType.USER, command.userNo());
        require(account.points(Bucket.PENDING) >= pendingDue, "POINTS_REFUND_PENDING_INSUFFICIENT");
        long available =
                type.rate()
                        .floorAligned(Math.min(due - pendingDue, account.points(Bucket.AVAILABLE)));
        long missing = Math.subtractExact(Math.subtractExact(due, pendingDue), available);
        var payment = command.payment();
        FundsContext context =
                new FundsContext(
                        FundsPath.valueOf(payment.fundsPath()),
                        payment.channel(),
                        payment.merchantNo(),
                        payment.storeNo(),
                        payment.accountVersion(),
                        payment.subMerchantId(),
                        payment.tradeNo(),
                        payment.outTradeNo(),
                        payment.orderTotalMinor());
        var gateway = funds.getIfAvailable();
        var capabilities = gateway == null ? null : gateway.capabilities(context);
        var cash =
                new CashClawbackPolicy()
                        .allocate(
                                type.rate(),
                                missing,
                                command.businessCashMinor(),
                                capabilities != null
                                        && capabilities.collection()
                                        && capabilities.collectionReversal());
        String hash = hash(json.writeValueAsString(command));
        String ticket =
                cash.cashOffsetMinor() == 0
                        ? null
                        : PointsKeys.position("K", command.typeCode(), command.afterSaleNo());
        var preparation =
                new RefundPreparation(
                        no,
                        command.typeCode(),
                        1,
                        hash,
                        returned,
                        pendingDue,
                        available,
                        missing,
                        cash.cashOffsetMinor(),
                        cash.netCashMinor(),
                        cash.waivedPoints(),
                        ticket,
                        ticket == null ? "PREPARED" : "COLLECTION_REQUIRED");
        FundsCommand collection =
                ticket == null
                        ? null
                        : new FundsCommand(
                                ticket,
                                "POINTS:" + ticket,
                                FundsPurpose.COLLECTION,
                                context,
                                command.currency(),
                                cash.cashOffsetMinor());
        var snapshot =
                new RefundSnapshot(
                        command, preparation, grantState, redeemState, dueByLine, collection);
        positions.add(
                new PositionMove(
                        PositionMove.Kind.REFUND,
                        no,
                        -1,
                        "NEW",
                        preparation.state(),
                        Math.addExact(due, returned),
                        0,
                        json.writeValueAsString(snapshot)));
        List<PointsMove> moves = new ArrayList<>();
        addFreeze(moves, command, Bucket.PENDING, pendingDue);
        addFreeze(moves, command, Bucket.AVAILABLE, available);
        writer.post(
                new Voucher(
                        "REFUND_PREPARE:" + no,
                        command.typeCode(),
                        type.currency(),
                        type.rate(),
                        moves,
                        List.of(),
                        positions,
                        0,
                        List.of(),
                        json.writeValueAsString(preparation)));
        return preparation;
    }

    private void validateUnfundedPaidSource(RefundCommand command, Map<String, Long> cumulative,
                                           Map<String, Object> lifecycle) {
        require(lifecycle != null, "POINTS_REFUND_SOURCE_UNKNOWN");
        var state = json.readTree((String) lifecycle.get("payload_json"));
        require(state.hasNonNull("paidFact"), "POINTS_REFUND_SOURCE_UNKNOWN");
        var paid = json.treeToValue(state.get("paidFact"), LifecycleFact.class);
        require(command.userNo().equals(paid.userNo()) && command.typeCode().equals(paid.typeCode())
                && command.subOrderNo().equals(paid.subOrderNo()) && command.sourceVersion() >= paid.sourceVersion(),
                "POINTS_REFUND_SUBJECT_MISMATCH");
        require(paid.payment() != null && paid.payment().equals(command.payment()), "POINTS_REFUND_PAYMENT_MISMATCH");
        var previous = new HashMap<String, Long>();
        var confirmed = read.confirmedRefunds(command.typeCode(), command.subOrderNo());
        require(confirmed.size() <= 10000, "POINTS_REFUND_HISTORY_LIMIT");
        for (var row : confirmed) {
            var refund = json.readValue((String) row.get("payload_json"), RefundSnapshot.class);
            require(command.userNo().equals(refund.command().userNo())
                    && command.payment().equals(refund.command().payment()), "POINTS_REFUND_SUBJECT_MISMATCH");
            for (var line : refund.command().lines()) previous.merge(line.itemId(),line.cumulativeGoodsMinor(),Math::max);
        }
        require(paid.lines().size() == cumulative.size() && cumulative.keySet().containsAll(previous.keySet()), "POINTS_REFUND_LINES_INVALID");
        var seen = new java.util.HashSet<String>();
        for (var line : paid.lines()) {
            Long amount = cumulative.get(line.itemId());
            require(seen.add(line.itemId()) && amount != null && amount >= previous.getOrDefault(line.itemId(),0L)
                    && amount <= line.goodsMinor(), "POINTS_REFUND_LINES_INVALID");
        }
    }

    private Map<String, Long> validate(RefundCommand command) {
        require(
                command != null
                        && nonblank(command.afterSaleNo())
                        && nonblank(command.subOrderNo())
                        && nonblank(command.userNo())
                        && nonblank(command.typeCode())
                        && nonblank(command.currency())
                        && command.sourceVersion() >= 0
                        && command.businessCashMinor() >= 0
                        && command.cumulativeGoodsMinor() >= 0
                        && command.payment() != null,
                "POINTS_REFUND_INPUT_INVALID");
        var payment = command.payment();
        require(
                Set.of("DIRECT", "AGGREGATED").contains(payment.fundsPath())
                        && nonblank(payment.channel())
                        && nonblank(payment.merchantNo())
                        && nonblank(payment.storeNo())
                        && nonblank(payment.accountVersion())
                        && nonblank(payment.subMerchantId())
                        && nonblank(payment.tradeNo())
                        && nonblank(payment.outTradeNo())
                        && payment.orderTotalMinor() >= command.businessCashMinor(),
                "POINTS_REFUND_PAYMENT_INVALID");
        Map<String, Long> lines = new HashMap<>();
        long total = 0;
        for (var line : command.lines()) {
            require(
                    nonblank(line.itemId())
                            && line.cumulativeGoodsMinor() >= 0
                            && lines.putIfAbsent(line.itemId(), line.cumulativeGoodsMinor())
                                    == null,
                    "POINTS_REFUND_LINES_INVALID");
            total = Math.addExact(total, line.cumulativeGoodsMinor());
        }
        require(total == command.cumulativeGoodsMinor(), "POINTS_REFUND_LINES_INVALID");
        return lines;
    }

    private PositionMove freeze(PositionMove.Kind kind, String no, Map<String, Object> head) {
        return new PositionMove(
                kind,
                no,
                number(head, "version"),
                (String) head.get("state"),
                "REFUND_PREPARED",
                number(head, "original_points"),
                0,
                (String) head.get("payload_json"));
    }

    private void addFreeze(
            List<PointsMove> moves, RefundCommand command, Bucket bucket, long amount) {
        if (amount == 0) {
            return;
        }
        moves.add(
                new PointsMove(
                        new AccountKey(
                                command.typeCode(), HolderType.USER, command.userNo(), bucket),
                        -amount));
        moves.add(
                new PointsMove(
                        new AccountKey(
                                command.typeCode(), HolderType.USER, command.userNo(), Bucket.HELD),
                        amount));
    }

    private String hash(String input) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private boolean nonblank(String value) {
        return value != null && !value.isBlank();
    }

    private long number(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }

    private void require(boolean condition, String reason) {
        if (!condition) {
            throw new IllegalArgumentException(reason);
        }
    }

    /**
     * Original states and line deltas let confirmation or compensation restore the frozen decision.
     */
    public record RefundSnapshot(
            RefundCommand command,
            RefundPreparation preparation,
            String grantState,
            String redeemState,
            Map<String, Long> reclaimedByLine,
            FundsCommand collectionCommand,
            RefundConfirmation confirmation,
            RefundAbort abort,
            FundsResult collectionResult,
            ZeroCashCompletionFact zeroCashCompletion,
            CompensationSnapshot compensation) {
        public RefundSnapshot {
            reclaimedByLine = Map.copyOf(reclaimedByLine);
        }

        public RefundSnapshot(
                RefundCommand command,
                RefundPreparation preparation,
                String grantState,
                String redeemState,
                Map<String, Long> reclaimedByLine,
                FundsCommand collectionCommand,
                RefundConfirmation confirmation,
                RefundAbort abort,
                FundsResult collectionResult,
                ZeroCashCompletionFact zeroCashCompletion) {
            this(
                    command,
                    preparation,
                    grantState,
                    redeemState,
                    reclaimedByLine,
                    collectionCommand,
                    confirmation,
                    abort,
                    collectionResult,
                    zeroCashCompletion,
                    null);
        }

        public RefundSnapshot(
                RefundCommand command,
                RefundPreparation preparation,
                String grantState,
                String redeemState,
                Map<String, Long> reclaimedByLine,
                FundsCommand collectionCommand,
                RefundConfirmation confirmation,
                RefundAbort abort,
                FundsResult collectionResult) {
            this(
                    command,
                    preparation,
                    grantState,
                    redeemState,
                    reclaimedByLine,
                    collectionCommand,
                    confirmation,
                    abort,
                    collectionResult,
                    null);
        }

        public RefundSnapshot(
                RefundCommand command,
                RefundPreparation preparation,
                String grantState,
                String redeemState,
                Map<String, Long> reclaimedByLine,
                FundsCommand collectionCommand) {
            this(
                    command,
                    preparation,
                    grantState,
                    redeemState,
                    reclaimedByLine,
                    collectionCommand,
                    null,
                    null,
                    null);
        }
    }
}
