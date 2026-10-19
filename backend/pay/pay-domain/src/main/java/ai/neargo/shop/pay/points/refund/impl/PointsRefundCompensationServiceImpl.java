package ai.neargo.shop.pay.points.refund.impl;

import ai.neargo.shop.pay.points.model.EvidenceRef;
import ai.neargo.shop.pay.points.model.PointsKeys;
import ai.neargo.shop.pay.points.model.PositionMove;
import ai.neargo.shop.pay.points.model.Voucher;
import ai.neargo.shop.pay.points.port.PointsFundsGateway;
import ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsCommand;
import ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsPurpose;
import ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsResult;
import ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsStatus;
import ai.neargo.shop.pay.points.posting.PointsPostingService;
import ai.neargo.shop.pay.points.refund.PointsRefundCompensationService;
import ai.neargo.shop.pay.points.refund.impl.PointsRefundPreparationServiceImpl.RefundSnapshot;
import ai.neargo.shop.pay.points.store.PointsCashRefundReadStore;
import ai.neargo.shop.pay.points.store.PointsConfigurationStore;
import ai.neargo.shop.pay.points.store.PointsReadStore;
import ai.neargo.shop.spi.points.PointsRefundPort.RefundAbort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Compensation preserves the original financial freeze until verified money has returned. */
@Service
@Transactional("payTxManager")
public class PointsRefundCompensationServiceImpl implements PointsRefundCompensationService {
    private final PointsReadStore read;
    private final PointsConfigurationStore configuration;
    private final PointsCashRefundReadStore cash;
    private final PointsPostingService posting;
    private final ObjectProvider<PointsFundsGateway> gateways;
    private final ObjectMapper json = new ObjectMapper();

    public PointsRefundCompensationServiceImpl(PointsReadStore read, PointsConfigurationStore configuration,
            PointsCashRefundReadStore cash, PointsPostingService posting, ObjectProvider<PointsFundsGateway> gateways) {
        this.read = read;
        this.configuration = configuration;
        this.cash = cash;
        this.posting = posting;
        this.gateways = gateways;
    }

    @Override public FundsCommand prepare(RefundAbort abort, long revision) {
        require(abort != null && abort.preparationNo() != null && abort.cashRefundPaymentNo() != null,
                "POINTS_REFUND_ABORT_INVALID");
        cash.lockRefund(abort.cashRefundPaymentNo());
        var head = locked(abort.preparationNo());
        var saved = snapshot(head);
        require(saved.preparation().inputHash().equals(abort.inputHash()), "POINTS_REFUND_INPUT_CONFLICT");
        require(saved.preparation().revision() == revision, "POINTS_REFUND_REVISION_CONFLICT");
        if (saved.compensation() != null) {
            require(abort.equals(saved.compensation().abort()), "POINTS_REFUND_COMPENSATION_INPUT_CONFLICT");
            return saved.compensation().command();
        }
        require("CASH_AUTHORIZED".equals(head.get("state")) && saved.preparation().netCashMinor() > 0,
                "POINTS_REFUND_COMPENSATION_NOT_SAFE");
        var collected = saved.collectionResult();
        var original = saved.collectionCommand();
        require(original != null && collected != null && collected.status() == FundsStatus.SUCCEEDED
                && original.ticketNo().equals(collected.ticketNo())
                && original.amountMinor() == collected.amountMinor() && collected.evidenceNo() != null
                && !collected.evidenceNo().isBlank(), "POINTS_REFUND_COLLECTION_NOT_CONFIRMED");
        verifyCashFailure(saved, abort);
        var gateway = gateways.getIfAvailable();
        require(gateway != null && gateway.capabilities(original.context()).collectionReversal(),
                "POINTS_REFUND_COMPENSATION_CAPABILITY_UNSUPPORTED");
        String ticket = PointsKeys.position("X", saved.command().typeCode(), abort.preparationNo());
        var command = new FundsCommand(ticket, "POINTS:" + ticket, FundsPurpose.COLLECTION_REVERSAL,
                original.context(), original.currency(), original.amountMinor(),
                original.ticketNo(), collected.evidenceNo());
        transition(head, saved, new CompensationSnapshot(abort, command, null),
                "COMPENSATION_REQUIRED", List.of());
        return command;
    }

    @Override public Dispatch claim(String no) {
        var initial = snapshot(read.position(PositionMove.Kind.REFUND.table(), no));
        require(initial.compensation() != null, "POINTS_REFUND_COMPENSATION_REQUIRED");
        cash.lockRefund(initial.compensation().abort().cashRefundPaymentNo());
        var head = locked(no);
        var saved = snapshot(head);
        var compensation = saved.compensation();
        require(compensation != null, "POINTS_REFUND_COMPENSATION_REQUIRED");
        if (terminal(compensation.result())) {
            return new Dispatch(compensation.command(), false, compensation.result());
        }
        String state = (String) head.get("state");
        require(Set.of("COMPENSATION_REQUIRED", "COMPENSATING", "COMPENSATION_UNKNOWN").contains(state),
                "POINTS_REFUND_COMPENSATION_STATE_CONFLICT");
        boolean submit = "COMPENSATION_REQUIRED".equals(state);
        if (submit) verifyCashFailure(saved, compensation.abort());
        if (submit) { transition(head, saved, compensation, "COMPENSATING", List.of()); }
        return new Dispatch(compensation.command(), submit, null);
    }

    @Override public FundsResult accept(String no, FundsResult result) {
        var head = locked(no);
        var saved = snapshot(head);
        var compensation = saved.compensation();
        require(compensation != null && result != null && result.status() != null
                && compensation.command().ticketNo().equals(result.ticketNo())
                && compensation.command().amountMinor() == result.amountMinor(),
                "POINTS_REFUND_COMPENSATION_RECEIPT_MISMATCH");
        if (terminal(compensation.result())) { return compensation.result(); }
        require(Set.of("COMPENSATING", "COMPENSATION_UNKNOWN").contains(head.get("state")),
                "POINTS_REFUND_COMPENSATION_NOT_DISPATCHED");
        String next = switch (result.status()) {
            case SUCCEEDED -> "COMPENSATED";
            case FAILED -> "COMPENSATION_FAILED";
            case UNKNOWN -> "COMPENSATION_UNKNOWN";
        };
        List<EvidenceRef> evidence = List.of();
        if (result.status() == FundsStatus.SUCCEEDED) {
            require(result.evidenceNo() != null && !result.evidenceNo().isBlank(),
                    "POINTS_REFUND_COMPENSATION_RECEIPT_MISMATCH");
            // Collection was held outside U; consuming both legs reconciles cash without minting rights.
            var incoming = new EvidenceRef(saved.collectionResult().evidenceNo(), compensation.command().currency(),
                    result.amountMinor(), "REFUND_COLLECTION", no);
            var outgoing = new EvidenceRef(result.evidenceNo(), compensation.command().currency(),
                    -result.amountMinor(), "REFUND_COMPENSATION", no);
            posting.recordEvidence(outgoing);
            evidence = List.of(incoming, outgoing);
        }
        transition(head, saved, new CompensationSnapshot(compensation.abort(), compensation.command(), result),
                next, evidence);
        return result;
    }

    private void verifyCashFailure(RefundSnapshot saved, RefundAbort abort) {
        var payment = cash.lockRefund(abort.cashRefundPaymentNo());
        require(payment != null && payment.safety() != null && payment.safety().safeToRecover()
                && payment.succeededAt() == null && payment.verifiedMinor() == null && payment.verifiedAt() == null,
                "POINTS_REFUND_CASH_OUTCOME_UNKNOWN");
        var command = saved.command();
        require("REFUND".equals(payment.direction()) && command.afterSaleNo().equals(payment.afterSaleNo())
                && command.subOrderNo().equals(payment.subOrderNo()) && command.userNo().equals(payment.userNo())
                && command.currency().equals(payment.currency())
                && command.payment().channel().equals(payment.channel())
                && saved.preparation().netCashMinor() == payment.amountMinor(), "POINTS_CASH_REFUND_MISMATCH");
    }

    private Map<String, Object> locked(String no) {
        var initial = read.position(PositionMove.Kind.REFUND.table(), no);
        require(initial != null, "POINTS_REFUND_PREPARATION_UNKNOWN");
        configuration.lockTypeAndPool((String) initial.get("type_code"));
        return configuration.lockRefundPreparation(no);
    }

    private RefundSnapshot snapshot(Map<String, Object> head) {
        return json.readValue((String) head.get("payload_json"), RefundSnapshot.class);
    }

    private void transition(Map<String, Object> head, RefundSnapshot saved, CompensationSnapshot compensation,
                            String next, List<EvidenceRef> evidence) {
        var updated = new RefundSnapshot(saved.command(), saved.preparation(), saved.grantState(),
                saved.redeemState(), saved.reclaimedByLine(), saved.collectionCommand(), saved.confirmation(),
                saved.abort(), saved.collectionResult(), saved.zeroCashCompletion(), compensation);
        var type = read.type(saved.command().typeCode()).orElseThrow();
        String no = saved.preparation().preparationNo();
        long version = ((Number) head.get("version")).longValue();
        posting.post(new Voucher("REFUND_COMPENSATE:" + no + ":" + version, type.typeCode(), type.currency(),
                type.rate(), List.of(), List.of(), List.of(new PositionMove(PositionMove.Kind.REFUND, no, version,
                (String) head.get("state"), next, ((Number) head.get("original_points")).longValue(), 0,
                json.writeValueAsString(updated))), 0, evidence, "{}"));
    }

    private boolean terminal(FundsResult result) {
        return result != null && result.status() != FundsStatus.UNKNOWN;
    }

    private void require(boolean condition, String reason) {
        if (!condition) { throw new IllegalArgumentException(reason); }
    }
}
