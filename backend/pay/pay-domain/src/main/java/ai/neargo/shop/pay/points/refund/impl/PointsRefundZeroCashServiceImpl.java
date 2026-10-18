package ai.neargo.shop.pay.points.refund.impl;

import ai.neargo.shop.pay.points.model.PositionMove;
import ai.neargo.shop.pay.points.model.Voucher;
import ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsStatus;
import ai.neargo.shop.pay.points.posting.PointsPostingService;
import ai.neargo.shop.pay.points.refund.PointsRefundZeroCashService;
import ai.neargo.shop.pay.points.refund.impl.PointsRefundPreparationServiceImpl.RefundSnapshot;
import ai.neargo.shop.pay.points.store.PointsCashRefundReadStore;
import ai.neargo.shop.pay.points.store.PointsConfigurationStore;
import ai.neargo.shop.pay.points.store.PointsFactStore;
import ai.neargo.shop.pay.points.store.PointsReadStore;
import ai.neargo.shop.spi.points.PointsRefundPort.ZeroCashCompletionFact;
import ai.neargo.shop.spi.points.PointsRefundPort.ZeroCashReason;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** Accepts trusted cash-leg facts; metadata and authorization commit together without inventing bank cash. */
@Service
@Transactional("payTxManager")
public class PointsRefundZeroCashServiceImpl implements PointsRefundZeroCashService {
    private final PointsReadStore read;
    private final PointsConfigurationStore configuration;
    private final PointsFactStore facts;
    private final PointsCashRefundReadStore cash;
    private final PointsPostingService posting;
    private final ObjectMapper json = new ObjectMapper();

    public PointsRefundZeroCashServiceImpl(PointsReadStore read, PointsConfigurationStore configuration,
                                           PointsFactStore facts, PointsCashRefundReadStore cash,
                                           PointsPostingService posting) {
        this.read = read;
        this.configuration = configuration;
        this.facts = facts;
        this.cash = cash;
        this.posting = posting;
    }

    @Override
    public void accept(ZeroCashCompletionFact fact) {
        require(fact != null && nonblank(fact.eventNo()) && fact.eventNo().length() <= 120
                && nonblank(fact.preparationNo()) && fact.reason() != null && fact.occurredAt() != null
                && fact.occurredAt().toEpochMilli() > 0, "POINTS_ZERO_CASH_FACT_INVALID");
        var initial = read.position(PositionMove.Kind.REFUND.table(), fact.preparationNo());
        require(initial != null, "POINTS_REFUND_PREPARATION_UNKNOWN");
        configuration.lockTypeAndPool((String) initial.get("type_code"));
        var head = configuration.lockRefundPreparation(fact.preparationNo());
        require(head != null, "POINTS_REFUND_PREPARATION_UNKNOWN");
        var snapshot = json.readValue((String) head.get("payload_json"), RefundSnapshot.class);
        var command = snapshot.command();
        var preparation = snapshot.preparation();
        require(preparation.netCashMinor() == 0, "POINTS_ZERO_CASH_NOT_ZERO");
        require(preparation.inputHash().equals(fact.inputHash()) && preparation.revision() == fact.revision()
                && command.typeCode().equals(fact.typeCode()) && command.afterSaleNo().equals(fact.afterSaleNo())
                && command.subOrderNo().equals(fact.subOrderNo()) && command.userNo().equals(fact.userNo())
                && command.payment().storeNo().equals(fact.storeNo()) && command.currency().equals(fact.currency())
                && command.sourceVersion() == fact.sourceVersion() && fact.netCashMinor() == 0
                && command.businessCashMinor() == fact.businessCashMinor()
                && preparation.cashOffsetMinor() == fact.cashOffsetMinor(), "POINTS_ZERO_CASH_FACT_MISMATCH");
        String state = (String) head.get("state");
        if (snapshot.zeroCashCompletion() != null) {
            require(snapshot.zeroCashCompletion().equals(fact), "POINTS_ZERO_CASH_FACT_CONFLICT");
            require("ZERO_AUTHORIZED".equals(state) || "CONFIRMED".equals(state),
                    "POINTS_ZERO_CASH_DECISION_NOT_READY");
            return;
        }
        boolean noCashDue = fact.reason() == ZeroCashReason.NO_CASH_DUE && command.businessCashMinor() == 0
                && preparation.cashOffsetMinor() == 0 && preparation.collectionTicket() == null;
        boolean fullOffset = fact.reason() == ZeroCashReason.COLLECTED_OFFSET && command.businessCashMinor() > 0
                && preparation.cashOffsetMinor() == command.businessCashMinor()
                && preparation.collectionTicket() != null;
        require(noCashDue || fullOffset, "POINTS_ZERO_CASH_REASON_MISMATCH");
        if (noCashDue) {
            require("PREPARED".equals(state), "POINTS_ZERO_CASH_DECISION_NOT_READY");
        } else {
            var receipt = snapshot.collectionResult();
            require("COLLECTED".equals(state) && receipt != null && receipt.status() == FundsStatus.SUCCEEDED
                    && preparation.collectionTicket().equals(receipt.ticketNo())
                    && receipt.amountMinor() == preparation.cashOffsetMinor() && nonblank(receipt.evidenceNo()),
                    "POINTS_ZERO_CASH_DECISION_NOT_READY");
        }
        require(!cash.hasRefundForAfterSale(command.afterSaleNo()), "POINTS_ZERO_CASH_CASH_INTENT_EXISTS");
        require(facts.lockInbox(fact.eventNo()) == null, "POINTS_ZERO_CASH_EVENT_CONFLICT");
        String payload = json.writeValueAsString(fact);
        facts.recordZeroCash(fact, payload, hash(payload));
        var authorized = new RefundSnapshot(command, preparation, snapshot.grantState(), snapshot.redeemState(),
                snapshot.reclaimedByLine(), snapshot.collectionCommand(), null, null,
                snapshot.collectionResult(), fact);
        var type = read.type(command.typeCode()).orElseThrow();
        posting.post(new Voucher("REFUND_ZERO_AUTHORIZE:" + fact.preparationNo(), type.typeCode(), type.currency(),
                type.rate(), List.of(), List.of(), List.of(new PositionMove(PositionMove.Kind.REFUND,
                fact.preparationNo(), ((Number) head.get("version")).longValue(), state, "ZERO_AUTHORIZED",
                ((Number) head.get("original_points")).longValue(), 0, json.writeValueAsString(authorized))),
                0, List.of(), payload));
    }

    private static String hash(String payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static boolean nonblank(String value) {
        return value != null && !value.isBlank();
    }

    private static void require(boolean condition, String reason) {
        if (!condition) { throw new IllegalArgumentException(reason); }
    }
}
