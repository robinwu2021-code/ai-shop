package ai.neargo.shop.pay.settle.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.pay.entity.StlBill;
import ai.neargo.shop.pay.mapper.SettleMappers.BillMapper;
import ai.neargo.shop.pay.mapper.SplitIntentMapper;
import ai.neargo.shop.pay.points.app.PointsSettlementHook;
import ai.neargo.shop.pay.points.app.PointsSettlementHook.BillPayout;
import ai.neargo.shop.pay.points.payout.PointsPayoutExecutionService.DispatchResult;
import ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsStatus;
import ai.neargo.shop.pay.settle.SettlementPointsFundingService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Bill binding and points confirmation use payTxManager; no remote operation is hidden in this service. */
@Service
@Transactional("payTxManager")
public class SettlementPointsFundingServiceImpl implements SettlementPointsFundingService {
    private final SplitIntentMapper locks;
    private final BillMapper bills;
    private final PointsSettlementHook points;
    public SettlementPointsFundingServiceImpl(SplitIntentMapper locks,BillMapper bills,PointsSettlementHook points) {
        this.locks=locks; this.bills=bills; this.points=points;
    }
    @Override public Plan prepare(String no) {
        return DataScopeContext.executeWithoutScope(() -> {
            StlBill bill=locks.lockBill(no);
            require(bill!=null,"SETTLEMENT_BILL_NOT_FOUND");
            if (amount(bill)==0 || bill.getPointsFundingReadyAt()!=null) { return new Plan(null,true); }
            require(StlBill.AGGREGATED.equals(bill.getFundsMode()),"POINTS_GOODS_FUNDING_REQUIRES_AGGREGATED");
            var plan=points.prepareForBill(new BillPayout(no,bill.getSubOrderNo(),bill.getEntityNo(),bill.getStoreNo(),
                    bill.getCurrency(),amount(bill),bill.getFundsMode()));
            if (plan.ticket()==null) {
                bill.setPointsFundingReadyAt(System.currentTimeMillis()); bills.updateById(bill);
                return new Plan(null,true);
            }
            String ticket=plan.ticket().ticketNo();
            require(bill.getPointsFundingTicket()==null || ticket.equals(bill.getPointsFundingTicket()),
                    "POINTS_GOODS_FUNDING_BINDING_CONFLICT");
            bill.setPointsFundingTicket(ticket); bills.updateById(bill);
            return new Plan(ticket,false);
        });
    }
    @Override public boolean accept(String no,DispatchResult dispatch) {
        return DataScopeContext.executeWithoutScope(() -> {
            StlBill bill=locks.lockBill(no);
            require(bill!=null && bill.getPointsFundingTicket()!=null,"POINTS_GOODS_FUNDING_INTENT_REQUIRED");
            String ticket=bill.getPointsFundingTicket();
            require(dispatch!=null && (dispatch.completed()!=null?ticket.equals(dispatch.completed().ticketNo())
                    : dispatch.result()!=null && ticket.equals(dispatch.result().ticketNo())),
                    "POINTS_GOODS_FUNDING_RECEIPT_MISMATCH");
            var result=dispatch.completed()!=null?dispatch.completed():points.acceptResult(dispatch.result());
            if (result.status()==FundsStatus.SUCCEEDED) {
                if (bill.getPointsFundingReadyAt()==null) {
                    bill.setPointsFundingReadyAt(System.currentTimeMillis()); bills.updateById(bill);
                }
                return true;
            }
            if (result.status()==FundsStatus.FAILED) { locks.clearFunding(no,ticket); }
            return false;
        });
    }
    @Override public void assertReady(String no) {
        DataScopeContext.executeWithoutScope(() -> {
            StlBill bill=locks.lockBill(no);
            require(bill!=null,"SETTLEMENT_BILL_NOT_FOUND");
            require(amount(bill)==0 || bill.getPointsFundingReadyAt()!=null,
                    "POINTS_FUNDING_PENDING_EXPORT_BLOCKED");
            return null;
        });
    }
    private long amount(StlBill bill) {
        return Math.addExact(bill.getSubsidyMinor()==null?0:bill.getSubsidyMinor(),
                bill.getPointsCostMinor()==null?0:bill.getPointsCostMinor());
    }
    private void require(boolean condition,String message) {
        if (!condition) { throw new IllegalStateException(message); }
    }
}
