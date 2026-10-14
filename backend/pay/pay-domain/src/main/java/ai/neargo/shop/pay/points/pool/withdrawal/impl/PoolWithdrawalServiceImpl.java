package ai.neargo.shop.pay.points.pool.withdrawal.impl;

import ai.neargo.shop.pay.points.model.AccountKey;
import ai.neargo.shop.pay.points.pool.withdrawal.WithdrawalSnapshot;
import ai.neargo.shop.pay.points.model.BudgetLockMove;
import ai.neargo.shop.pay.points.model.Bucket;
import ai.neargo.shop.pay.points.model.EvidenceRef;
import ai.neargo.shop.pay.points.model.FundBucket;
import ai.neargo.shop.pay.points.model.FundMove;
import ai.neargo.shop.pay.points.model.HolderType;
import ai.neargo.shop.pay.points.model.PointType;
import ai.neargo.shop.pay.points.model.PointsKeys;
import ai.neargo.shop.pay.points.model.PointsMove;
import ai.neargo.shop.pay.points.model.PoolRequest;
import ai.neargo.shop.pay.points.model.PositionMove;
import ai.neargo.shop.pay.points.model.Voucher;
import ai.neargo.shop.pay.points.pool.PoolWithdrawalService;
import ai.neargo.shop.pay.points.pool.impl.PoolRequestServiceImpl.Envelope;
import ai.neargo.shop.pay.points.port.PointsFundsGateway;
import ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsCommand;
import ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsPurpose;
import ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsResult;
import ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsStatus;
import ai.neargo.shop.pay.points.posting.PointsPostingService;
import ai.neargo.shop.pay.points.posting.PostingResult;
import ai.neargo.shop.pay.points.store.PointsConfigurationStore;
import ai.neargo.shop.pay.points.store.PointsReadStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/** Pool locks protect both current cash and pending withdrawal budgets against grants and refund promises. */
@Service
@Transactional("payTxManager")
public class PoolWithdrawalServiceImpl implements PoolWithdrawalService {
    private final PointsReadStore read;
    private final PointsConfigurationStore configuration;
    private final PointsPostingService posting;
    private final ObjectProvider<PointsFundsGateway> gateways;
    private final ObjectMapper json=new ObjectMapper();
    public PoolWithdrawalServiceImpl(PointsReadStore read,PointsConfigurationStore configuration,
                                     PointsPostingService posting,ObjectProvider<PointsFundsGateway> gateways) {
        this.read=read; this.configuration=configuration; this.posting=posting; this.gateways=gateways;
    }
    @Override public PostingResult prepareApproved(PoolRequest request) {
        var approval=configuration.lockRequest(request.requestNo());
        require(approval!=null && "APPROVING".equals(approval.get("state")),"POINTS_APPROVAL_REQUIRED");
        require(json.readValue((String)approval.get("payload_json"),Envelope.class).request().equals(request),
                "POINTS_APPROVAL_CONTENT_MISMATCH");
        configuration.lockTypeAndPool(request.typeCode());
        var type=read.type(request.typeCode()).orElseThrow();
        FundBucket bucket=bucket(request);
        long amount=request.amountMinor();
        require(amount>0 && type.status()!=PointType.Status.RETIRED,"POINTS_WITHDRAW_INPUT_INVALID");
        var target=request.fundsContext();
        require(target!=null && "PLATFORM".equals(target.merchantNo()) && "PLATFORM".equals(target.storeNo())
                && target.accountVersion()!=null && !target.accountVersion().isBlank()
                && target.subMerchantId()!=null && !target.subMerchantId().isBlank(),"POINTS_WITHDRAW_TARGET_REQUIRED");
        var gateway=gateways.getIfAvailable();
        require(gateway!=null && gateway.capabilities(target).transfer(),"POINTS_WITHDRAW_TRANSFER_UNSUPPORTED");
        long points=bucket==FundBucket.U?type.rate().pointsForMinor(amount):0;
        var pool=read.pool(type.typeCode());
        require(amount<=(bucket==FundBucket.U?pool.freeU():pool.freeB()),"POINTS_WITHDRAW_AVAILABLE_INSUFFICIENT");
        String no=PointsKeys.position("W",type.typeCode(),request.requestNo());
        var command=new FundsCommand(no,"PTS-"+no,FundsPurpose.TRANSFER,target,type.currency(),amount);
        var snapshot=new WithdrawalSnapshot(request,command,null);
        return posting.post(new Voucher("WITHDRAW_PREPARE:"+request.requestNo(),type.typeCode(),type.currency(),
                type.rate(),List.of(),List.of(),List.of(new PositionMove(PositionMove.Kind.TRANSFER,no,-1,"NEW",
                "READY",points,0,json.writeValueAsString(snapshot))),0,List.of(),
                json.writeValueAsString(new Outcome(no,FundsStatus.UNKNOWN,0,false)),List.of(),
                List.of(new BudgetLockMove(bucket,amount))));
    }
    @Override public Claim claimDispatch(String no) {
        var head=locked(no);
        var snapshot=snapshot(head);
        String state=(String)head.get("state");
        if (terminal(state)) { return new Claim(snapshot.fundsCommand(),false,outcome(snapshot,true)); }
        if (!"READY".equals(state)) { return new Claim(snapshot.fundsCommand(),false,null); }
        post(head,snapshot,"DISPATCHING",0,List.of(),List.of(),List.of(),List.of(),"WITHDRAW_DISPATCH:"+no);
        return new Claim(snapshot.fundsCommand(),true,null);
    }
    @Override public Outcome acceptResult(FundsResult result) {
        require(result!=null && result.ticketNo()!=null && result.status()!=null,"POINTS_WITHDRAW_RECEIPT_INVALID");
        var head=locked(result.ticketNo());
        var original=snapshot(head);
        require(result.amountMinor()==original.fundsCommand().amountMinor(),"POINTS_WITHDRAW_RECEIPT_INVALID");
        String state=(String)head.get("state");
        if (terminal(state)) {
            require(result.status()==FundsStatus.UNKNOWN || result.status()==original.result().status(),
                    "POINTS_WITHDRAW_TERMINAL_CONFLICT");
            return outcome(original,true);
        }
        require("DISPATCHING".equals(state) || "UNKNOWN".equals(state),"POINTS_WITHDRAW_NOT_DISPATCHED");
        if ("UNKNOWN".equals(state) && result.status()==FundsStatus.UNKNOWN) { return outcome(original,true); }
        FundBucket bucket=bucket(original.request());
        long amount=original.request().amountMinor();
        var type=read.type(original.request().typeCode()).orElseThrow();
        var next=new WithdrawalSnapshot(original.request(),original.fundsCommand(),result);
        if (result.status()==FundsStatus.UNKNOWN) {
            post(head,next,"UNKNOWN",0,List.of(),List.of(),List.of(),List.of(),
                    "WITHDRAW_RESULT:"+result.ticketNo()+":UNKNOWN");
            return outcome(next,false);
        }
        boolean success=result.status()==FundsStatus.SUCCEEDED;
        List<PointsMove> moves=List.of();
        List<FundMove> funds=List.of();
        List<EvidenceRef> evidence=List.of();
        long processed=0;
        if (success) {
            require(result.evidenceNo()!=null && !result.evidenceNo().isBlank(),"POINTS_WITHDRAW_EVIDENCE_REQUIRED");
            var proof=new EvidenceRef(result.evidenceNo(),type.currency(),-amount);
            posting.recordEvidence(proof); evidence=List.of(proof); funds=List.of(new FundMove(bucket,-amount));
            if (bucket==FundBucket.U) {
                processed=type.rate().pointsForMinor(amount);
                moves=List.of(new PointsMove(new AccountKey(type.typeCode(),HolderType.ISSUE,"PLATFORM",
                        Bucket.ISSUABLE),-processed),new PointsMove(new AccountKey(type.typeCode(),HolderType.RECEIVE,
                        "PLATFORM",Bucket.RECIRCULATABLE),processed));
            }
        }
        post(head,next,success?"SUCCEEDED":"FAILED",processed,moves,funds,evidence,
                List.of(new BudgetLockMove(bucket,-amount)),"WITHDRAW_RESULT:"+result.ticketNo()+":"+result.status());
        return outcome(next,false);
    }
    private PostingResult post(Map<String,Object> head,WithdrawalSnapshot snapshot,String next,long processed,
                               List<PointsMove> moves,List<FundMove> funds,List<EvidenceRef> evidence,
                               List<BudgetLockMove> budget,String biz) {
        var type=read.type(snapshot.request().typeCode()).orElseThrow();
        return posting.post(new Voucher(biz,type.typeCode(),type.currency(),type.rate(),moves,funds,
                List.of(new PositionMove(PositionMove.Kind.TRANSFER,snapshot.fundsCommand().ticketNo(),
                        number(head,"version"),(String)head.get("state"),next,number(head,"original_points"),processed,
                        json.writeValueAsString(snapshot))),0,evidence,json.writeValueAsString(outcome(snapshot,false)),
                List.of(),budget));
    }
    private Map<String,Object> locked(String no) {
        var head=read.position(PositionMove.Kind.TRANSFER.table(),no);
        require(head!=null,"POINTS_WITHDRAW_UNKNOWN");
        configuration.lockTypeAndPool((String)head.get("type_code"));
        return read.position(PositionMove.Kind.TRANSFER.table(),no);
    }
    private WithdrawalSnapshot snapshot(Map<String,Object> head) {
        return json.readValue((String)head.get("payload_json"),WithdrawalSnapshot.class);
    }
    private FundBucket bucket(PoolRequest request) {
        require(request.action()==PoolRequest.Action.WITHDRAW_U || request.action()==PoolRequest.Action.WITHDRAW_B,
                "POINTS_WITHDRAW_ACTION_REQUIRED");
        return request.action()==PoolRequest.Action.WITHDRAW_U?FundBucket.U:FundBucket.B;
    }
    private Outcome outcome(WithdrawalSnapshot snapshot,boolean replayed) {
        var result=snapshot.result();
        return new Outcome(snapshot.fundsCommand().ticketNo(),result==null?FundsStatus.UNKNOWN:result.status(),
                result!=null && result.status()==FundsStatus.SUCCEEDED?result.amountMinor():0,replayed);
    }
    private boolean terminal(String state) { return "SUCCEEDED".equals(state) || "FAILED".equals(state); }
    private long number(Map<String,Object> row,String key) { return ((Number)row.get(key)).longValue(); }
    private void require(boolean condition,String message) {
        if (!condition) { throw new IllegalArgumentException(message); }
    }
}
