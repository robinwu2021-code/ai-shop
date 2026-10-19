package ai.neargo.shop.pay.settle.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.pay.SplitGateway;
import ai.neargo.shop.pay.entity.StlBill;
import ai.neargo.shop.pay.entity.StlSplitLog;
import ai.neargo.shop.pay.mapper.SettleMappers.BillMapper;
import ai.neargo.shop.pay.mapper.SettleMappers.SplitLogMapper;
import ai.neargo.shop.pay.mapper.SplitIntentMapper;
import ai.neargo.shop.pay.points.app.PointsSettlementHook;
import ai.neargo.shop.pay.points.payout.PointsPayoutExecutionService.DispatchResult;
import ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsStatus;
import ai.neargo.shop.pay.settle.SplitCommand;
import ai.neargo.shop.pay.settle.SplitResultService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;

/** Funding evidence and the bill marker commit together; remote command acceptance alone never marks arrival. */
@Service
@Transactional("payTxManager")
public class SplitResultServiceImpl implements SplitResultService {
    private final SplitIntentMapper intents;
    private final BillMapper bills;
    private final SplitLogMapper logs;
    private final PointsSettlementHook points;
    private final ObjectMapper json=new ObjectMapper();
    public SplitResultServiceImpl(SplitIntentMapper intents,BillMapper bills,SplitLogMapper logs,
                                 PointsSettlementHook points) {
        this.intents=intents; this.bills=bills; this.logs=logs; this.points=points;
    }
    @Override public boolean acceptPoints(String request,DispatchResult dispatch) {
        return DataScopeContext.executeWithoutScope(() -> {
            var prior=intents.intent(request);
            require(prior!=null,"SETTLEMENT_INTENT_NOT_FOUND");
            StlBill bill=intents.lockBill((String)prior.get("settle_no"));
            var head=intents.lockIntent(request);
            String ticket=(String)head.get("points_ticket");
            require(ticket!=null && dispatch!=null && (dispatch.completed()!=null
                    ? ticket.equals(dispatch.completed().ticketNo()) : dispatch.result()!=null
                    && ticket.equals(dispatch.result().ticketNo())),"SETTLEMENT_POINTS_RECEIPT_MISMATCH");
            var outcome=dispatch.completed()!=null?dispatch.completed():points.acceptResult(dispatch.result());
            if (outcome.status()==FundsStatus.SUCCEEDED) {
                intents.funding(request,"FUNDED");
                if (bill.getSubsidyMinor()!=null && bill.getSubsidyMinor()>0 && bill.getSubsidyAt()==null) {
                    bill.setSubsidyAt(System.currentTimeMillis()); bills.updateById(bill);
                }
                return true;
            }
            intents.funding(request,outcome.status().name());
            if (outcome.status()==FundsStatus.FAILED) {
                intents.state(request,(String)head.get("state"),"FAILED");
            }
            retry(bill,"POINTS_FUNDING_"+outcome.status());
            return false;
        });
    }
    @Override public void acceptCash(String request,SplitGateway.Result result) {
        DataScopeContext.executeWithoutScope(() -> {
            var prior=intents.intent(request);
            require(prior!=null,"SETTLEMENT_INTENT_NOT_FOUND");
            StlBill bill=intents.lockBill((String)prior.get("settle_no"));
            var head=intents.lockIntent(request);
            if ("ACCEPTED".equals(head.get("state"))) { return null; }
            require(!StlBill.REVERSED.equals(bill.getStatus()),"SETTLEMENT_RESULT_SOURCE_CLOSED");
            require("FUNDED".equals(head.get("funding_state")),"SETTLEMENT_POINTS_NOT_FUNDED");
            var command=json.readValue((String)head.get("command_json"),SplitCommand.class);
            if (result==null || !result.success() || result.providerNo()==null || result.providerNo().isBlank()) {
                intents.state(request,(String)head.get("state"),"UNKNOWN");
                recordCash(command,"UNKNOWN",result==null?null:result.providerNo(),
                        result==null?"SPLIT_OUTCOME_UNKNOWN":result.message());
                retry(bill,"SPLIT_OUTCOME_UNKNOWN"); return null;
            }
            require(intents.state(request,(String)head.get("state"),"ACCEPTED")==1,"SETTLEMENT_RESULT_CONFLICT");
            bill.setStatus(StlBill.SPLIT); bill.setSplitAt(System.currentTimeMillis());
            bill.setSplitAmountMinor(command.amountMinor()); bills.updateById(bill);
            recordCash(command,"SUCCESS",result.providerNo(),null);
            return null;
        });
    }
    private void recordCash(SplitCommand command,String status,String provider,String message) {
        var row=logs.selectOne(com.baomidou.mybatisplus.core.toolkit.Wrappers.<StlSplitLog>lambdaQuery()
                .eq(StlSplitLog::getRequestNo,command.requestNo()).last("LIMIT 1"));
        boolean fresh=row==null;
        if (fresh) {
            row=new StlSplitLog(); row.setSettleNo(command.settleNo());
            row.setSubOrderNo(command.subOrderNo()); row.setSplitAction(StlSplitLog.SPLIT);
            row.setAmountMinor(command.amountMinor()); row.setRequestNo(command.requestNo());
            row.setTenantNo("MAIN"); row.setCreatedAt(LocalDateTime.now());
        }
        row.setResult(status); row.setProviderNo(provider); row.setAt(System.currentTimeMillis());
        if(message!=null) row.setMessage(message.substring(0,Math.min(message.length(),512)));
        if(fresh) logs.insert(row); else logs.updateById(row);
    }
    private void retry(StlBill bill,String reason) {
        bill.setStatus(StlBill.RETRYING); bill.setRetryCount(bill.getRetryCount()==null?1:bill.getRetryCount()+1);
        bill.setLastError(reason); bills.updateById(bill);
    }
    private void require(boolean condition,String message) {
        if (!condition) { throw new IllegalStateException(message); }
    }
}
