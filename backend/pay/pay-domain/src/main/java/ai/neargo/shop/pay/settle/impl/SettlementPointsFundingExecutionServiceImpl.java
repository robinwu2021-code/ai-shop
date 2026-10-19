package ai.neargo.shop.pay.settle.impl;

import ai.neargo.shop.pay.points.payout.PointsPayoutExecutionService;
import ai.neargo.shop.pay.settle.SettlementPointsFundingExecutionService;
import ai.neargo.shop.pay.settle.SettlementPointsFundingService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** The native internal replenishment cannot become a second merchant transfer. */
@Service
public class SettlementPointsFundingExecutionServiceImpl implements SettlementPointsFundingExecutionService {
    private final SettlementPointsFundingService funding;
    private final PointsPayoutExecutionService execution;
    public SettlementPointsFundingExecutionServiceImpl(SettlementPointsFundingService funding,
                                                       PointsPayoutExecutionService execution) {
        this.funding=funding; this.execution=execution;
    }
    @Override public boolean ensureReady(String no) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("SETTLEMENT_FUNDS_REQUIRES_NO_TRANSACTION");
        }
        var plan=funding.prepare(no);
        return plan.ready() || funding.accept(no,execution.perform(plan.ticketNo()));
    }
}
