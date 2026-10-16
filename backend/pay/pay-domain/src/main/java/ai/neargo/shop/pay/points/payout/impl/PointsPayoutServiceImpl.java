package ai.neargo.shop.pay.points.payout.impl;

import ai.neargo.shop.pay.points.app.PointsSettlementHook.PayoutCommand;
import ai.neargo.shop.pay.points.app.PointsSettlementHook.PayoutTicket;
import ai.neargo.shop.pay.points.model.PointType;
import ai.neargo.shop.pay.points.model.FundBucket;
import ai.neargo.shop.pay.points.model.FundMove;
import ai.neargo.shop.pay.points.model.EvidenceRef;
import ai.neargo.shop.pay.points.app.PointsSettlementHook.PayoutOutcome;
import ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsResult;
import ai.neargo.shop.pay.points.model.PointsKeys;
import ai.neargo.shop.pay.points.model.PositionMove;
import ai.neargo.shop.pay.points.model.Voucher;
import ai.neargo.shop.pay.points.payout.PayoutSnapshot;
import ai.neargo.shop.pay.points.payout.PointsPayoutService;
import ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsCommand;
import ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsPurpose;
import ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsStatus;
import ai.neargo.shop.pay.points.posting.PointsPostingService;
import ai.neargo.shop.pay.points.redeem.impl.RedeemServiceImpl.RedeemSnapshot;
import ai.neargo.shop.pay.points.store.PointsConfigurationStore;
import ai.neargo.shop.pay.points.store.PointsReadStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Locks each payable source once and persists the money intent without performing an external operation. */
@Service
@Transactional("payTxManager")
public class PointsPayoutServiceImpl implements PointsPayoutService {
    private final PointsReadStore read;
    private final PointsConfigurationStore config;
    private final PointsPostingService posting;
    private final ObjectMapper json=new ObjectMapper();

    public PointsPayoutServiceImpl(PointsReadStore read,PointsConfigurationStore config,PointsPostingService posting) {
        this.read=read; this.config=config; this.posting=posting;
    }
    @Override public PayoutTicket beginPayout(PayoutCommand input) {
        PayoutCommand c=normalize(input);
        config.lockTypeAndPool(c.typeCode());
        PointType type=read.type(c.typeCode()).orElseThrow();
        String ticketNo=PointsKeys.position("Y",c.typeCode(),c.bizRef());
        Map<String,Object> prior=read.position(PositionMove.Kind.PAYOUT.table(),ticketNo);
        if (prior!=null) {
            PayoutSnapshot old=json.readValue((String)prior.get("payload_json"),PayoutSnapshot.class);
            if (!old.command().equals(c)) { throw new IllegalArgumentException("POINTS_IDEMPOTENCY_CONFLICT"); }
            return old.ticket();
        }
        if (!type.currency().equals(c.currency())) { throw new IllegalArgumentException("POINTS_DIMENSION_MISMATCH"); }
        List<PositionMove> positions=new ArrayList<>();
        long points=0;
        for (String id:c.positionIds()) {
            var head=read.position(PositionMove.Kind.REDEEM.table(),id);
            if (head==null || !c.typeCode().equals(head.get("type_code")) || !"PAYABLE".equals(head.get("state"))) {
                throw new IllegalArgumentException("POINTS_POSITION_NOT_PAYABLE");
            }
            RedeemSnapshot source=json.readValue((String)head.get("payload_json"),RedeemSnapshot.class);
            if (!source.finalSnapshotReceived()) {
                throw new IllegalArgumentException("POINTS_FINAL_ORDER_SNAPSHOT_REQUIRED");
            }
            if (!"PER_ORDER".equals(source.agreement().settleMode())) {
                throw new IllegalArgumentException("POINTS_PERIODIC_STATEMENT_REQUIRED");
            }
            if (!source.fundsContext().equals(c.context())) {
                throw new IllegalArgumentException("POINTS_PAYOUT_RECIPIENT_MISMATCH");
            }
            long original=num(head,"original_points");
            if (source.share().points()!=original || type.rate().toMinor(original)!=source.share().minor()) {
                throw new IllegalArgumentException("POINTS_POSITION_AMOUNT_MISMATCH");
            }
            long remaining=Math.subtractExact(original,num(head,"processed_points"));
            if (remaining<=0) { throw new IllegalArgumentException("POINTS_POSITION_NOT_PAYABLE"); }
            points=Math.addExact(points,remaining);
            positions.add(new PositionMove(PositionMove.Kind.REDEEM,id,num(head,"version"),"PAYABLE","PAYING",
                    original,0,(String)head.get("payload_json")));
        }
        long gross=type.rate().toMinor(points);
        if (read.pool(c.typeCode()).d()<gross) { throw new IllegalArgumentException("POINTS_D_INSUFFICIENT"); }
        String providerIdem="PTS-"+ticketNo;
        PayoutTicket ticket=new PayoutTicket(ticketNo,gross,0,gross,providerIdem,FundsStatus.UNKNOWN);
        FundsCommand money=new FundsCommand(ticketNo,providerIdem,c.purpose(),c.context(),c.currency(),gross);
        PayoutSnapshot snapshot=new PayoutSnapshot(c,ticket,money);
        positions.add(new PositionMove(PositionMove.Kind.PAYOUT,ticketNo,-1,"NEW","READY",points,0,
                json.writeValueAsString(snapshot)));
        posting.post(new Voucher("PAYOUT_PREP:"+c.bizRef(),c.typeCode(),type.currency(),type.rate(),List.of(),List.of(),
                positions,0,List.of(),json.writeValueAsString(ticket)));
        return ticket;
    }
    @Override public FundsCommand commandFor(String no) {
        var head=read.position(PositionMove.Kind.PAYOUT.table(),no);
        if (head==null) { throw new IllegalArgumentException("POINTS_PAYOUT_UNKNOWN"); }
        return json.readValue((String)head.get("payload_json"),PayoutSnapshot.class).fundsCommand();
    }
    @Override public DispatchClaim claimDispatch(String no) {
        var head=lockedTicket(no);
        PayoutSnapshot snapshot=json.readValue((String)head.get("payload_json"),PayoutSnapshot.class);
        String state=(String)head.get("state");
        if (terminal(state)) { return new DispatchClaim(snapshot.fundsCommand(),false,outcome(snapshot,true)); }
        if (!"READY".equals(state)) { return new DispatchClaim(snapshot.fundsCommand(),false,null); }
        PointType type=read.type((String)head.get("type_code")).orElseThrow();
        posting.post(new Voucher("PAYOUT_DISPATCH:"+no,type.typeCode(),type.currency(),type.rate(),List.of(),List.of(),
                List.of(new PositionMove(PositionMove.Kind.PAYOUT,no,num(head,"version"),"READY","DISPATCHING",
                        num(head,"original_points"),0,(String)head.get("payload_json"))),0,List.of(),"{}"));
        return new DispatchClaim(snapshot.fundsCommand(),true,null);
    }
    @Override public PayoutOutcome acceptResult(FundsResult result) {
        if (result==null || result.status()==null || blank(result.ticketNo())) {
            throw new IllegalArgumentException("POINTS_FUNDS_RESULT_INVALID");
        }
        var head=lockedTicket(result.ticketNo());
        PayoutSnapshot old=json.readValue((String)head.get("payload_json"),PayoutSnapshot.class);
        if (result.amountMinor()!=old.ticket().cash()) {
            throw new IllegalArgumentException("POINTS_FUNDS_RESULT_AMOUNT_MISMATCH");
        }
        String state=(String)head.get("state");
        if (terminal(state)) {
            if (result.status()!=FundsStatus.UNKNOWN && (result.status()!=old.result().status()
                    || (result.status()==FundsStatus.SUCCEEDED
                    && !Objects.equals(result.evidenceNo(),old.result().evidenceNo())))) {
                throw new IllegalArgumentException("POINTS_FUNDS_RESULT_CONFLICT");
            }
            return outcome(old,true);
        }
        if (!"DISPATCHING".equals(state) && !"UNKNOWN".equals(state)) {
            throw new IllegalArgumentException("POINTS_PAYOUT_NOT_DISPATCHED");
        }
        if (result.status()==FundsStatus.UNKNOWN && "UNKNOWN".equals(state)) { return outcome(old,true); }
        PointType type=read.type((String)head.get("type_code")).orElseThrow();
        PayoutSnapshot next=new PayoutSnapshot(old.command(),old.ticket(),old.fundsCommand(),result);
        List<PositionMove> positions=new ArrayList<>();
        List<FundMove> money=List.of();
        List<EvidenceRef> evidence=List.of();
        if (result.status()!=FundsStatus.UNKNOWN) {
            for (String id:old.command().positionIds()) {
                var source=read.position(PositionMove.Kind.REDEEM.table(),id);
                if (source==null || !"PAYING".equals(source.get("state"))) {
                    throw new IllegalArgumentException("POINTS_POSITION_STATE_CONFLICT");
                }
                positions.add(new PositionMove(PositionMove.Kind.REDEEM,id,num(source,"version"),"PAYING",
                        result.status()==FundsStatus.SUCCEEDED?"PAID":"PAYABLE",num(source,"original_points"),0,
                        (String)source.get("payload_json")));
            }
        }
        if (result.status()==FundsStatus.SUCCEEDED) {
            if (blank(result.evidenceNo())) { throw new IllegalArgumentException("POINTS_FUNDS_EVIDENCE_MISSING"); }
            EvidenceRef proof=new EvidenceRef(result.evidenceNo(),type.currency(),
                    Math.negateExact(result.amountMinor()));
            posting.recordEvidence(proof);
            money=List.of(new FundMove(FundBucket.D,-result.amountMinor()));
            evidence=List.of(proof);
        }
        positions.add(new PositionMove(PositionMove.Kind.PAYOUT,result.ticketNo(),num(head,"version"),state,
                result.status().name(),num(head,"original_points"),0,json.writeValueAsString(next)));
        PayoutOutcome outcome=outcome(next,false);
        posting.post(new Voucher("PAYOUT_RESULT:"+result.ticketNo()+":"+result.status(),type.typeCode(),type.currency(),
                type.rate(),List.of(),money,positions,0,evidence,json.writeValueAsString(outcome)));
        return outcome;
    }
    private Map<String,Object> lockedTicket(String no) {
        var head=read.position(PositionMove.Kind.PAYOUT.table(),no);
        if (head==null) { throw new IllegalArgumentException("POINTS_PAYOUT_UNKNOWN"); }
        config.lockTypeAndPool((String)head.get("type_code"));
        return config.lockPayoutTicket(no);
    }
    private boolean terminal(String state) { return "SUCCEEDED".equals(state) || "FAILED".equals(state); }
    private PayoutOutcome outcome(PayoutSnapshot snapshot,boolean replayed) {
        FundsResult result=snapshot.result();
        return new PayoutOutcome(snapshot.ticket().ticketNo(),result.status(),
                result.status()==FundsStatus.SUCCEEDED?result.amountMinor():0,replayed);
    }

    private PayoutCommand normalize(PayoutCommand c) {
        if (c==null || blank(c.bizRef()) || blank(c.typeCode()) || blank(c.sourceRef()) || blank(c.currency())
                || c.context()==null || c.context().path()==null || c.positionIds().isEmpty()
                || c.positionIds().stream().anyMatch(this::blank)
                || new HashSet<>(c.positionIds()).size()!=c.positionIds().size()) {
            throw new IllegalArgumentException("POINTS_PAYOUT_INPUT_INVALID");
        }
        FundsPurpose required=FundsPurpose.forPerOrder(c.context().path());
        if (c.purpose()!=required) { throw new IllegalArgumentException("POINTS_PAYOUT_PURPOSE_INVALID"); }
        return new PayoutCommand(c.bizRef(),c.typeCode(),c.sourceRef(),c.positionIds().stream().sorted().toList(),
                c.purpose(),c.context(),c.currency());
    }
    private boolean blank(String value) { return value==null || value.isBlank(); }
    private long num(Map<String,Object> row,String key) { return ((Number)row.get(key)).longValue(); }
}
