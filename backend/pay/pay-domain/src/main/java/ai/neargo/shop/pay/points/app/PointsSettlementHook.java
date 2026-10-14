package ai.neargo.shop.pay.points.app;

import ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsContext;
import ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsPurpose;
import ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsResult;
import ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsStatus;
import ai.neargo.shop.spi.points.PointsFactPort.LifecycleFact;

import java.util.List;

/** Pay's settlement module enters points through this hook, without writing points tables directly. */
public interface PointsSettlementHook {
    /** Creates the immutable ticket in the caller's short pay transaction; does not call a gateway. */
    PayoutTicket beginPayout(PayoutCommand command);

    /** Records a verified outcome atomically with settlement state in payTxManager. */
    PayoutOutcome acceptResult(FundsResult result);

    /** Applies the settlement terminal fact to remaining pending rights. */
    void onBillFinalized(LifecycleFact fact);

    /** Reads the acceptance snapshot; later agreement changes do not rewrite an existing order. */
    SettleMode settleModeOf(String subOrderNo);

    enum SettleMode { PER_ORDER, PERIODIC }
    record PayoutCommand(String bizRef, String typeCode, String sourceRef, List<String> positionIds,
                         FundsPurpose purpose, FundsContext context, String currency) {
        public PayoutCommand { positionIds = List.copyOf(positionIds); }
    }
    record PayoutTicket(String ticketNo, long gross, long offset, long cash, String providerIdem,
                        FundsStatus status) { }
    record PayoutOutcome(String ticketNo, FundsStatus status, long confirmedMinor, boolean replayed) { }
}
