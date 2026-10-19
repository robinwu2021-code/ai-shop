package ai.neargo.shop.pay.settle.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.pay.entity.StlBill;
import ai.neargo.shop.pay.mapper.SettleMappers.BillMapper;
import ai.neargo.shop.pay.mapper.SplitIntentMapper;
import ai.neargo.shop.pay.mapper.PointsSettlementRefundHoldMapper;
import ai.neargo.shop.pay.points.app.PointsSettlementHook;
import ai.neargo.shop.pay.points.app.PointsSettlementHook.BillPayout;
import ai.neargo.shop.pay.settle.SplitCommand;
import ai.neargo.shop.pay.settle.SplitPreparationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.UUID;

/** A bill lock serializes intent creation; unknown attempts retain their original command forever. */
@Service
@Transactional("payTxManager")
public class SplitPreparationServiceImpl implements SplitPreparationService {
    private final SplitIntentMapper intents;
    private final BillMapper bills;
    private final PointsSettlementHook points;
    private final PointsSettlementRefundHoldMapper refundHolds;
    private final ai.neargo.shop.pay.mapper.CashRefundSplitMapper cashReversals;
    private final ObjectMapper json=new ObjectMapper();
    public SplitPreparationServiceImpl(SplitIntentMapper intents,BillMapper bills,PointsSettlementHook points,
                                       PointsSettlementRefundHoldMapper refundHolds,
                                       ai.neargo.shop.pay.mapper.CashRefundSplitMapper cashReversals) {
        this.cashReversals=cashReversals;
        this.intents=intents; this.bills=bills; this.points=points; this.refundHolds=refundHolds;
    }
    @Override public Plan prepare(String settleNo) {
        return DataScopeContext.executeWithoutScope(() -> {
            StlBill bill=intents.lockBill(settleNo);
            require(bill!=null,"SETTLEMENT_BILL_NOT_FOUND");
            require(cashReversals.pending(settleNo)==0,"SETTLEMENT_CASH_REVERSAL_PENDING");
            require(refundHolds.pendingForBill(settleNo)==0,"SETTLEMENT_REFUND_PENDING");
            if (terminal(bill.getStatus())) { return new Plan(null,null,true); }
            var prior=intents.latest(settleNo);
            boolean legacy = bill.getSplitProtocolVersion() == null || bill.getSplitProtocolVersion() == 0;
            if (prior!=null && (legacy || !"FAILED".equals(prior.get("state")))) { return plan(prior); }
            Long amount = legacy ? bill.getNetMinor() : bill.getSplitAmountMinor();
            require(amount!=null && amount>=0, "SETTLEMENT_SPLIT_AMOUNT_REQUIRED");
            require(bill.getPayMerchantNo()!=null && !bill.getPayMerchantNo().isBlank()
                    && bill.getStoreNo()!=null && bill.getCurrency()!=null,"SETTLEMENT_RECIPIENT_REQUIRED");
            String ticket = null;
            if (!legacy) {
                var funding=points.prepareForBill(new BillPayout(settleNo,bill.getSubOrderNo(),bill.getEntityNo(),
                        bill.getStoreNo(),bill.getCurrency(),Math.addExact(nz(bill.getSubsidyMinor()),
                        nz(bill.getPointsCostMinor())),bill.getFundsMode()));
                ticket=funding.ticket()==null?null:funding.ticket().ticketNo();
            }
            String request=legacy ? "SPL-"+settleNo : "SPL-"+UUID.randomUUID();
            var command=new SplitCommand(request,settleNo,bill.getSubOrderNo(),bill.getEntityNo(),bill.getStoreNo(),
                    bill.getPayMerchantNo(),bill.getPayChannel(),bill.getCurrency(),amount);
            intents.insert(request,settleNo,json.writeValueAsString(command),ticket,ticket==null?"FUNDED":"PENDING");
            // A deployed legacy command may have reached the bank even with no local success log.
            // Recover the fixed original identity through query only; NOT_FOUND never permits submit.
            if (legacy) {
                require(intents.state(request,"READY","UNKNOWN")==1,"SETTLEMENT_DISPATCH_CONFLICT");
            }
            bill.setStatus(StlBill.SPLITTING); bills.updateById(bill);
            return new Plan(command,ticket,false);
        });
    }
    @Override public Claim claim(String request) {
        return DataScopeContext.executeWithoutScope(() -> {
            var head=intents.lockIntent(request);
            require(head!=null,"SETTLEMENT_INTENT_NOT_FOUND");
            var command=json.readValue((String)head.get("command_json"),SplitCommand.class);
            String state=(String)head.get("state");
            if ("ACCEPTED".equals(state)) { return new Claim(command,false,true); }
            require("FUNDED".equals(head.get("funding_state")),"SETTLEMENT_POINTS_NOT_FUNDED");
            if (!"READY".equals(state)) { return new Claim(command,false,false); }
            require(intents.state(request,"READY","DISPATCHING")==1,"SETTLEMENT_DISPATCH_CONFLICT");
            return new Claim(command,true,false);
        });
    }
    @Override
    @Transactional(value="payTxManager",propagation=Propagation.MANDATORY)
    public StlBill lockForReverse(String settleNo) {
        return DataScopeContext.executeWithoutScope(() -> {
            StlBill bill=intents.lockBill(settleNo);
            require(bill!=null,"SETTLEMENT_BILL_NOT_FOUND");
            if(cashReversals.pending(settleNo)>0) return null;
            var head=intents.latest(settleNo);
            if (head!=null && !"ACCEPTED".equals(head.get("state")) && !"FAILED".equals(head.get("state"))) {
                return null;
            }
            return bill;
        });
    }
    private Plan plan(Map<String,Object> head) {
        return new Plan(json.readValue((String)head.get("command_json"),SplitCommand.class),
                (String)head.get("points_ticket"),"ACCEPTED".equals(head.get("state")));
    }
    private boolean terminal(String state) {
        return StlBill.SPLIT.equals(state) || StlBill.SPLIT_CONFIRMED.equals(state)
                || StlBill.REVERSED.equals(state) || StlBill.OFFLINE_SETTLED.equals(state);
    }
    private long nz(Long n) { return n==null?0:n; }
    private void require(boolean condition,String message) {
        if (!condition) { throw new IllegalStateException(message); }
    }
}
