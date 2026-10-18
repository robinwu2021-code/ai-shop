package ai.neargo.shop.pay.points.port;

import ai.neargo.shop.pay.points.refund.PointsRefundAbortService;
import ai.neargo.shop.pay.points.refund.PointsRefundConfirmationService;
import ai.neargo.shop.pay.points.refund.PointsRefundPreparationService;
import ai.neargo.shop.pay.points.refund.PointsRefundCashDecisionService;
import ai.neargo.shop.pay.points.model.PositionMove;
import ai.neargo.shop.pay.points.store.PointsReadStore;
import ai.neargo.shop.pay.points.refund.impl.PointsRefundPreparationServiceImpl.RefundSnapshot;
import ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsStatus;
import ai.neargo.shop.spi.points.PointsRefundPort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** The order domain exchanges frozen DTOs; pay retains all points and financial mutations. */
@Component
public class PointsRefundPortImpl implements PointsRefundPort {
    private final PointsRefundPreparationService preparations;
    private final PointsRefundConfirmationService confirmations;
    private final PointsRefundAbortService aborts;
    private final org.springframework.beans.factory.ObjectProvider<PointsRefundCashDecisionService> cashDecisions;
    private final org.springframework.beans.factory.ObjectProvider<ai.neargo.shop.pay.points.refund.PointsRefundCompensationService> compensations;
    private final PointsReadStore read;
    private final ObjectMapper json = new ObjectMapper();

    public PointsRefundPortImpl(PointsRefundPreparationService preparations,
                                PointsRefundConfirmationService confirmations, PointsRefundAbortService aborts,
                                org.springframework.beans.factory.ObjectProvider<PointsRefundCashDecisionService> cashDecisions,
                                org.springframework.beans.factory.ObjectProvider<ai.neargo.shop.pay.points.refund.PointsRefundCompensationService> compensations,
                                PointsReadStore read) {
        this.preparations = preparations;
        this.confirmations = confirmations;
        this.aborts = aborts;
        this.cashDecisions = cashDecisions;
        this.compensations = compensations;
        this.read = read;
    }

    @Override public RefundPreparation prepare(RefundCommand command) { return preparations.prepare(command); }
    @Override public CashDecision authorizeCash(String preparationNo,String inputHash,long revision) {
        var service=cashDecisions.getIfAvailable();
        if(service==null) throw new IllegalArgumentException("POINTS_REFUND_CASH_DECISION_REQUIRED");
        var result=service.authorize(preparationNo,inputHash,revision);
        return new CashDecision(result.preparationNo(),result.inputHash(),result.revision(),
                result.afterSaleNo(),result.subOrderNo(),result.userNo(),result.currency(),
                result.channel(),result.netCashMinor(),result.cashOffsetMinor(),
                result.collectionTicket(),result.payment());
    }
    @Override public RefundOutcome confirm(RefundConfirmation confirmation) {
        return confirmations.confirm(confirmation);
    }
    @Override public void abort(RefundAbort abort) { aborts.abort(abort); }
    @Override public void requestCompensation(RefundAbort abort,long revision) {
        var service=compensations.getIfAvailable();
        if(service==null) throw new IllegalArgumentException("POINTS_REFUND_COMPENSATION_REQUIRED");
        service.prepare(abort,revision);
    }
    @Override
    @Transactional(value = "payTxManager", readOnly = true)
    public RefundRecoveryStatus recoveryStatus(String preparationNo, String inputHash) {
        if (preparationNo == null || inputHash == null || inputHash.isBlank())
            throw new IllegalArgumentException("POINTS_REFUND_STATUS_INVALID");
        var row = read.position(PositionMove.Kind.REFUND.table(), preparationNo);
        if (row == null) throw new IllegalArgumentException("POINTS_REFUND_PREPARATION_UNKNOWN");
        var saved = json.readValue((String) row.get("payload_json"), RefundSnapshot.class);
        if (!inputHash.equals(saved.preparation().inputHash()))
            throw new IllegalArgumentException("POINTS_REFUND_INPUT_CONFLICT");
        var compensation = saved.compensation();
        boolean succeeded = compensation != null && compensation.result() != null
                && compensation.result().status() == FundsStatus.SUCCEEDED
                && saved.abort() != null && saved.abort().equals(compensation.abort());
        String cashNo = saved.abort() != null ? saved.abort().cashRefundPaymentNo()
                : saved.confirmation() == null ? null : saved.confirmation().cashRefundPaymentNo();
        return new RefundRecoveryStatus(preparationNo, saved.command().afterSaleNo(),
                saved.command().subOrderNo(), (String) row.get("state"),
                saved.preparation().netCashMinor(), cashNo, succeeded,
                saved.preparation().collectionTicket() != null);
    }
}
