package ai.neargo.shop.pay.points.refund.impl;

import ai.neargo.shop.pay.points.model.EvidenceRef;
import ai.neargo.shop.pay.points.model.FundBucket;
import ai.neargo.shop.pay.points.model.FundMove;
import ai.neargo.shop.pay.points.model.PointsKeys;
import ai.neargo.shop.pay.points.model.PositionMove;
import ai.neargo.shop.pay.points.model.ReceivableMove;
import ai.neargo.shop.pay.points.model.Voucher;
import ai.neargo.shop.pay.points.port.PointsFundsGateway;
import ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsCommand;
import ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsContext;
import ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsPath;
import ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsPurpose;
import ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsResult;
import ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsStatus;
import ai.neargo.shop.pay.points.posting.PointsPostingService;
import ai.neargo.shop.pay.points.refund.PointsReceivableRecoveryService;
import ai.neargo.shop.pay.points.store.PointsConfigurationStore;
import ai.neargo.shop.pay.points.store.PointsReadStore;
import ai.neargo.shop.spi.points.PointsRefundPort.RefundCommand;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/** The original store and bank account snapshot own recovery; R is never counted as money. */
@Service
@Transactional("payTxManager")
public class PointsReceivableRecoveryServiceImpl implements PointsReceivableRecoveryService {
    private final PointsReadStore read;
    private final PointsConfigurationStore configuration;
    private final PointsPostingService posting;
    private final ObjectProvider<PointsFundsGateway> gateways;
    private final ObjectMapper json=new ObjectMapper();
    public PointsReceivableRecoveryServiceImpl(PointsReadStore read,PointsConfigurationStore configuration,
            PointsPostingService posting,ObjectProvider<PointsFundsGateway> gateways) {
        this.read=read; this.configuration=configuration; this.posting=posting; this.gateways=gateways;
    }
    @Override public FundsCommand prepare(String biz,String receivableNo,long amount) {
        require(biz!=null && !biz.isBlank() && amount>0,"POINTS_RECOVERY_INPUT_INVALID");
        var initial=read.receivable(receivableNo);
        require(initial!=null,"POINTS_RECEIVABLE_UNKNOWN");
        String typeCode=(String)initial.get("type_code");
        configuration.lockTypeAndPool(typeCode);
        String no=PointsKeys.position("J",typeCode,biz);
        var existing=read.position(PositionMove.Kind.TRANSFER.table(),no);
        if (existing!=null) {
            var prior=snapshot(existing);
            require(biz.equals(prior.bizRef()) && receivableNo.equals(prior.receivableNo())
                    && amount==prior.fundsCommand().amountMinor(),"POINTS_RECOVERY_INPUT_CONFLICT");
            return prior.fundsCommand();
        }
        var debt=read.receivable(receivableNo);
        long available=Math.subtractExact(Math.subtractExact(number(debt,"original_minor"),
                number(debt,"recovered_minor")),number(debt,"locked_minor"));
        require("OPEN".equals(debt.get("state")) && amount<=available,
                "POINTS_RECOVERY_RECEIVABLE_INSUFFICIENT");
        var source=json.readValue((String)debt.get("payload_json"),RefundCommand.class);
        var payment=source.payment();
        require(typeCode.equals(source.typeCode())
                && payment.storeNo().equals(debt.get("store_no")),
                "POINTS_RECOVERY_STORE_MISMATCH");
        var context=new FundsContext(FundsPath.valueOf(payment.fundsPath()),payment.channel(),payment.merchantNo(),
                payment.storeNo(),payment.accountVersion(),payment.subMerchantId(),
                payment.tradeNo(),payment.outTradeNo(),
                payment.orderTotalMinor());
        var gateway=gateways.getIfAvailable();
        require(gateway!=null && gateway.capabilities(context).collection(),"POINTS_RECOVERY_CAPABILITY_UNSUPPORTED");
        var command=new FundsCommand(no,"PTS-"+no,FundsPurpose.COLLECTION,context,source.currency(),amount);
        var saved=new RecoverySnapshot(biz,receivableNo,command,null);
        var type=read.type(typeCode).orElseThrow();
        posting.post(new Voucher("RECOVERY_PREPARE:"+no,typeCode,type.currency(),type.rate(),List.of(),List.of(),
                List.of(new PositionMove(PositionMove.Kind.TRANSFER,no,-1,"NEW","READY",0,0,
                        json.writeValueAsString(saved))),0,List.of(),"{}",List.of(),List.of(),
                List.of(debtMove(receivableNo,debt,0,amount))));
        return command;
    }
    @Override public Claim claim(String no) {
        var head=locked(no);
        var saved=snapshot(head);
        if (terminal((String)head.get("state"))) { return new Claim(saved.fundsCommand(),false,outcome(saved,true)); }
        boolean submit="READY".equals(head.get("state"));
        if (submit) { post(no,head,saved,"DISPATCHING",List.of(),List.of(),List.of(),"RECOVERY_DISPATCH:"+no); }
        return new Claim(saved.fundsCommand(),submit,null);
    }
    @Override public Outcome accept(FundsResult result) {
        require(result!=null && result.status()!=null,"POINTS_RECOVERY_RECEIPT_INVALID");
        var head=locked(result.ticketNo());
        var prior=snapshot(head);
        require(result.amountMinor()==prior.fundsCommand().amountMinor(),"POINTS_RECOVERY_RECEIPT_MISMATCH");
        if (terminal((String)head.get("state"))) { return outcome(prior,true); }
        require("DISPATCHING".equals(head.get("state")) || "UNKNOWN".equals(head.get("state")),
                "POINTS_RECOVERY_NOT_DISPATCHED");
        var next=new RecoverySnapshot(prior.bizRef(),prior.receivableNo(),prior.fundsCommand(),result);
        if (result.status()==FundsStatus.UNKNOWN) {
            post(result.ticketNo(),head,next,"UNKNOWN",List.of(),List.of(),List.of(),
                    "RECOVERY_UNKNOWN:"+result.ticketNo()+":"+number(head,"version"));
            return outcome(next,false);
        }
        var debt=read.receivable(prior.receivableNo());
        long amount=result.amountMinor();
        boolean success=result.status()==FundsStatus.SUCCEEDED;
        List<EvidenceRef> evidence=List.of();
        List<FundMove> funds=List.of();
        if (success) {
            require(result.evidenceNo()!=null && !result.evidenceNo().isBlank(),"POINTS_RECOVERY_EVIDENCE_REQUIRED");
            var receipt=new EvidenceRef(result.evidenceNo(),prior.fundsCommand().currency(),amount,
                    "REFUND_RECOVERY",result.ticketNo());
            posting.recordEvidence(receipt); evidence=List.of(receipt);
            funds=List.of(new FundMove(FundBucket.B,amount));
        }
        post(result.ticketNo(),head,next,success?"SUCCEEDED":"FAILED",funds,evidence,
                List.of(debtMove(prior.receivableNo(),debt,success?amount:0,-amount)),
                "RECOVERY_RESULT:"+result.ticketNo()+":"+result.status());
        return outcome(next,false);
    }
    private ReceivableMove debtMove(String no,Map<String,Object> debt,long recovered,long locked) {
        boolean closed=Math.addExact(number(debt,"recovered_minor"),recovered)==number(debt,"original_minor");
        return new ReceivableMove(no,(String)debt.get("store_no"),number(debt,"version"),(String)debt.get("state"),
                closed?"CLOSED":"OPEN",number(debt,"original_minor"),recovered,locked,(String)debt.get("payload_json"));
    }
    private void post(String no,Map<String,Object> head,RecoverySnapshot saved,String state,List<FundMove> funds,
                      List<EvidenceRef> evidence,List<ReceivableMove> debt,String ref) {
        var type=read.type((String)head.get("type_code")).orElseThrow();
        posting.post(new Voucher(ref,type.typeCode(),type.currency(),type.rate(),List.of(),funds,
                List.of(new PositionMove(PositionMove.Kind.TRANSFER,no,number(head,"version"),
                        (String)head.get("state"),state,0,0,json.writeValueAsString(saved))),0,evidence,"{}",
                List.of(),List.of(),debt));
    }
    private Map<String,Object> locked(String no) {
        var head=read.position(PositionMove.Kind.TRANSFER.table(),no);
        require(head!=null,"POINTS_RECOVERY_UNKNOWN");
        configuration.lockTypeAndPool((String)head.get("type_code"));
        var latest=read.position(PositionMove.Kind.TRANSFER.table(),no);
        require(json.readTree((String)latest.get("payload_json")).hasNonNull("receivableNo"),
                "POINTS_RECOVERY_UNKNOWN");
        return latest;
    }
    private RecoverySnapshot snapshot(Map<String,Object> head) {
        return json.readValue((String)head.get("payload_json"),RecoverySnapshot.class);
    }
    private Outcome outcome(RecoverySnapshot snapshot,boolean replayed) {
        var result=snapshot.result();
        return new Outcome(snapshot.fundsCommand().ticketNo(),result==null?FundsStatus.UNKNOWN:result.status(),
                result!=null && result.status()==FundsStatus.SUCCEEDED?result.amountMinor():0,replayed);
    }
    private boolean terminal(String state) { return "SUCCEEDED".equals(state) || "FAILED".equals(state); }
    private long number(Map<String,Object> row,String key) { return ((Number)row.get(key)).longValue(); }
    private void require(boolean condition,String reason) {
        if (!condition) { throw new IllegalArgumentException(reason); }
    }
    /** Original incoming operation and store debt remain immutable across dispatch and query. */
    public record RecoverySnapshot(String bizRef,String receivableNo,FundsCommand fundsCommand,FundsResult result) { }
}
