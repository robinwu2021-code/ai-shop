package ai.neargo.shop.pay.settle.refund.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.pay.SplitGateway;
import ai.neargo.shop.pay.entity.StlBill;
import ai.neargo.shop.pay.entity.StlSplitLog;
import ai.neargo.shop.pay.mapper.*;
import ai.neargo.shop.pay.mapper.SettleMappers.BillMapper;
import ai.neargo.shop.pay.mapper.SettleMappers.SplitLogMapper;
import ai.neargo.shop.pay.settle.SplitCommand;
import ai.neargo.shop.pay.settle.refund.*;
import ai.neargo.shop.spi.settle.SettlePort.SplitReversal;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.Map;

/** No source/pay transaction spans an external reversal or query. */
@Service
public class CashRefundSplitServiceImpl implements CashRefundSplitService {
    private final CashRefundSplitMapper intents;
    private final SplitIntentMapper splits;
    private final BillMapper bills;
    private final BillRefundMapper refunds;
    private final SplitLogMapper logs;
    private final PointsSettlementRefundHoldMapper holds;
    private final SettlementRefundCoordinator settlement;
    private final SettlementRefundPolicy policy;
    private final PreparedRefundShares preparedShares;
    private final BillRefundJournal journal;
    private final SplitGateway bank;
    private final TransactionTemplate tx;
    private final ObjectMapper json=new ObjectMapper();

    public CashRefundSplitServiceImpl(CashRefundSplitMapper intents,SplitIntentMapper splits,BillMapper bills,
            BillRefundMapper refunds,SplitLogMapper logs,PointsSettlementRefundHoldMapper holds,
            SettlementRefundCoordinator settlement,SettlementRefundPolicy policy,PreparedRefundShares preparedShares,BillRefundJournal journal,
            SplitGateway bank,@Qualifier("payTxManager") PlatformTransactionManager manager) {
        this.intents=intents; this.splits=splits; this.bills=bills; this.refunds=refunds; this.logs=logs;
        this.holds=holds; this.settlement=settlement; this.policy=policy; this.journal=journal; this.bank=bank;
        this.preparedShares=preparedShares;
        this.tx=new TransactionTemplate(manager);
    }
    @Override public boolean reverse(SplitReversal command) { return reverse(command, false); }
    @Override public boolean reversePrepared(SplitReversal command) { return reverse(command, true); }
    private boolean reverse(SplitReversal command, boolean prepared) {
        require(!TransactionSynchronizationManager.isActualTransactionActive(),"CASH_REVERSAL_REQUIRES_NO_TRANSACTION");
        require(command!=null && command.subOrderNo()!=null && command.afterSaleNo()!=null
                && command.goodsMinor()>=0 && command.freightMinor()>=0 && command.goodsPaidMinor()>=0,
                "CASH_REVERSAL_INPUT_INVALID");
        var plan=tx.execute(s -> DataScopeContext.executeWithoutScope(() -> prepare(command, prepared)));
        if(plan.done()) return true;
        if(plan.frozen()==null) return false;
        var frozen=plan.frozen(); var cash=frozen.cash();
        SplitGateway.Result result;
        try {
            if(cash.amountMinor()==0) result=SplitGateway.Result.ok("ZERO:"+java.util.UUID.nameUUIDFromBytes(cash.requestNo().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            else result=plan.submit()
                    ? bank.reverse(cash) : bank.queryReverse(cash);
        } catch(RuntimeException unknown) { result=SplitGateway.Result.fail("REVERSAL_TRANSPORT_UNKNOWN"); }
        var outcome=result;
        return Boolean.TRUE.equals(tx.execute(s -> DataScopeContext.executeWithoutScope(() -> accept(frozen,outcome))));
    }
    private Plan prepare(SplitReversal source, boolean prepared) {
        StlBill bill=bills.selectOne(Wrappers.<StlBill>lambdaQuery().eq(StlBill::getSubOrderNo,source.subOrderNo())
                .last("LIMIT 1 FOR UPDATE"));
        if(bill==null) return new Plan(null,false,true);
        var prior=intents.find(source.afterSaleNo());
        if(prior!=null) {
            var frozen=json.readValue((String)prior.get("command_json"),Frozen.class);
            require(frozen.source().equals(source) && frozen.deferred()==prepared,"CASH_REVERSAL_INPUT_CONFLICT");
            return new Plan(frozen,false,"CONFIRMED".equals(prior.get("state")));
        }
        if(journal.recorded(source.afterSaleNo())) return new Plan(null,false,true);
        if (prepared) {
            var hold=holds.find(source.afterSaleNo());
            require(hold!=null && "PENDING".equals(hold.get("status"))
                    && bill.getSettleNo().equals(hold.get("settle_no"))
                    && source.goodsMinor()==((Number)hold.get("goods_minor")).longValue()
                    && source.freightMinor()==((Number)hold.get("freight_minor")).longValue()
                    && source.goodsPaidMinor()==((Number)hold.get("goods_paid_minor")).longValue(),
                    "SETTLEMENT_REFUND_HOLD_MISMATCH");
        }
        if(intents.pending(bill.getSettleNo())>0 || !prepared && holds.pendingForBill(bill.getSettleNo())>0)
            return new Plan(null,false,false);
        var split=splits.latest(bill.getSettleNo());
        if(split!=null && !"ACCEPTED".equals(split.get("state")) && !"FAILED".equals(split.get("state")))
            return new Plan(null,false,false);
        if(StlBill.SPLITTING.equals(bill.getStatus())) return new Plan(null,false,false);
        boolean splitDone=StlBill.SPLIT.equals(bill.getStatus()) || StlBill.SPLIT_CONFIRMED.equals(bill.getStatus())
                || StlBill.MANUAL.equals(bill.getStatus()) && bill.getSplitAt()!=null;
        if(!splitDone) return new Plan(null,false,prepared || settlement.reverse(source));
        require(prepared || nz(bill.getSubsidyMinor())==0 && nz(bill.getPointsCostMinor())==0,"CASH_REVERSAL_POINTS_PREPARATION_REQUIRED");
        SplitCommand original=null;
        if(split!=null && "ACCEPTED".equals(split.get("state")))
            original=json.readValue((String)split.get("command_json"),SplitCommand.class);
        else {
            var receipts=logs.selectList(Wrappers.<StlSplitLog>lambdaQuery().eq(StlSplitLog::getSettleNo,bill.getSettleNo())
                    .eq(StlSplitLog::getSubOrderNo,bill.getSubOrderNo()).eq(StlSplitLog::getSplitAction,StlSplitLog.SPLIT)
                    .eq(StlSplitLog::getResult,"SUCCESS"));
            if(receipts.size()!=1 || receipts.getFirst().getAmountMinor()==null
                    || receipts.getFirst().getProviderNo()==null) return new Plan(null,false,false);
            original=new SplitCommand(receipts.getFirst().getRequestNo(),bill.getSettleNo(),bill.getSubOrderNo(),
                    bill.getEntityNo(),bill.getStoreNo(),bill.getPayMerchantNo(),bill.getPayChannel(),bill.getCurrency(),
                    receipts.getFirst().getAmountMinor());
        }
        require(original.settleNo().equals(bill.getSettleNo()) && original.subOrderNo().equals(source.subOrderNo())
                && original.amountMinor()==nz(bill.getSplitAmountMinor()),"CASH_REVERSAL_SPLIT_PROOF_CONFLICT");
        require(original.payMerchantNo()!=null && !original.payMerchantNo().isBlank()
                && original.channel()!=null && original.currency()!=null,"CASH_REVERSAL_ROUTE_REQUIRED");
        String request="REV-"+source.afterSaleNo(); require(request.length()<=64,"CASH_REVERSAL_REQUEST_TOO_LONG");
        var cash=new SplitCommand(request,original.settleNo(),original.subOrderNo(),original.merchantNo(),original.storeNo(),
                original.payMerchantNo(),original.channel(),original.currency(),settlement.splitReturn(bill,source));
        var share=prepared?preparedShares.share(bill,source)
                :policy.share(bill,source.goodsMinor(),source.freightMinor(),source.goodsPaidMinor());
        var frozen=new Frozen(source,cash,share,
                nz(bill.getRefundedGoodsMinor()),bill.getStatus(),prepared);
        intents.create(source.afterSaleNo(),bill.getSettleNo(),request,json.writeValueAsString(frozen),cash.channel());
        return new Plan(frozen,true,false);
    }
    private boolean accept(Frozen frozen,SplitGateway.Result result) {
        var cash=frozen.cash(); var bill=splits.lockBill(cash.settleNo());
        var row=intents.find(frozen.source().afterSaleNo());
        require(row!=null && frozen.equals(json.readValue((String)row.get("command_json"),Frozen.class)),"CASH_REVERSAL_COMMAND_CONFLICT");
        if("CONFIRMED".equals(row.get("state"))) return true;
        boolean success=result!=null && result.success() && result.providerNo()!=null && !result.providerNo().isBlank()
                && result.providerNo().length()<=64;
        String error=success?null:result==null?"REVERSAL_OUTCOME_UNKNOWN":result.message();
        if(error!=null) error=error.substring(0,Math.min(error.length(),512));
        intents.result(frozen.source().afterSaleNo(),success?"CONFIRMED":"UNKNOWN",success?result.providerNo():null,error);
        log(cash,success?"SUCCESS":"UNKNOWN",success?result.providerNo():null,error);
        if(!success) return false;
        boolean sameState=bill!=null && (bill.getStatus().equals(frozen.billStatus())
                || StlBill.SPLIT.equals(frozen.billStatus()) && StlBill.SPLIT_CONFIRMED.equals(bill.getStatus()));
        require(sameState && nz(bill.getRefundedGoodsMinor())==frozen.before(),"CASH_REVERSAL_BILL_CHANGED");
        if (frozen.deferred()) {
            // Record the actual bank movement now, without pretending that goods/cash were refunded.
            require(refunds.recordAfterSplit(cash.settleNo(),0,cash.amountMinor(),frozen.before(),
                    bill.getStatus(),bill.getStatus())==1,"CASH_REVERSAL_RESULT_CONFLICT");
            return true;
        }
        journal.append(bill,frozen.share(),frozen.source().afterSaleNo(),false);
        require(refunds.recordAfterSplit(cash.settleNo(),frozen.share().goodsMinor(),cash.amountMinor(),frozen.before(),
                bill.getStatus(),frozen.share().finalRefund()?StlBill.REVERSED:bill.getStatus())==1,
                "CASH_REVERSAL_RESULT_CONFLICT");
        return true;
    }
    private void log(SplitCommand cash,String status,String provider,String message) {
        var row=logs.selectOne(Wrappers.<StlSplitLog>lambdaQuery().eq(StlSplitLog::getRequestNo,cash.requestNo()).last("LIMIT 1"));
        boolean fresh=row==null;
        if(fresh) {
            row=new StlSplitLog(); row.setSettleNo(cash.settleNo()); row.setSubOrderNo(cash.subOrderNo());
            row.setSplitAction(StlSplitLog.REVERSE); row.setRequestNo(cash.requestNo()); row.setAmountMinor(cash.amountMinor());
            row.setTenantNo("MAIN"); row.setCreatedAt(LocalDateTime.now());
        }
        require(row.getAmountMinor()==cash.amountMinor() && cash.subOrderNo().equals(row.getSubOrderNo()),"CASH_REVERSAL_LOG_CONFLICT");
        row.setResult(status); row.setProviderNo(provider); row.setMessage(message); row.setAt(System.currentTimeMillis());
        if(fresh) logs.insert(row); else logs.updateById(row);
    }
    private long nz(Long value) { return value==null?0:value; }
    private void require(boolean valid,String message) { if(!valid) throw new IllegalStateException(message); }
    private record Plan(Frozen frozen,boolean submit,boolean done) {}
    public record Frozen(SplitReversal source,SplitCommand cash,BillRefundShare share,long before,String billStatus,
                         boolean deferred) {}
}
