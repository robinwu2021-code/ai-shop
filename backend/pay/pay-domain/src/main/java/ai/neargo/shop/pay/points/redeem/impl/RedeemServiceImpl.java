package ai.neargo.shop.pay.points.redeem.impl;

import ai.neargo.shop.pay.points.agreement.AgreementService;
import ai.neargo.shop.pay.points.model.AccountKey;
import ai.neargo.shop.pay.points.model.AgreementSnapshot;
import ai.neargo.shop.pay.points.model.Bucket;
import ai.neargo.shop.pay.points.model.HolderType;
import ai.neargo.shop.pay.points.model.PointType;
import ai.neargo.shop.pay.points.model.PointsKeys;
import ai.neargo.shop.pay.points.model.PointsMove;
import ai.neargo.shop.pay.points.model.PositionMove;
import ai.neargo.shop.pay.points.model.ReserveMove;
import ai.neargo.shop.pay.points.model.RedeemRate;
import ai.neargo.shop.pay.points.model.Voucher;
import ai.neargo.shop.pay.points.policy.DefaultRedeemPolicy;
import ai.neargo.shop.pay.points.policy.RedeemPolicy;
import ai.neargo.shop.pay.points.port.PointsFundsGateway;
import ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsContext;
import ai.neargo.shop.pay.points.port.PointsFundsGateway.FundsPath;
import ai.neargo.shop.pay.points.posting.PointsPostingService;
import ai.neargo.shop.pay.points.redeem.RedeemService;
import ai.neargo.shop.pay.points.store.PointsConfigurationStore;
import ai.neargo.shop.pay.points.store.PointsReadStore;
import ai.neargo.shop.spi.points.PointsRedeemPort.HoldCommand;
import ai.neargo.shop.spi.points.PointsRedeemPort.HoldOutcome;
import ai.neargo.shop.spi.points.PointsRedeemPort.RedeemQuery;
import ai.neargo.shop.spi.points.PointsRedeemPort.RedeemQuote;
import ai.neargo.shop.spi.points.PointsRedeemPort.ReleaseCommand;
import ai.neargo.shop.spi.points.PointsRedeemPort.Share;
import ai.neargo.shop.spi.points.PointsRedeemPort.Target;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** A short pay transaction freezes the agreement, user rights, and complete B reserve together. */
@Service
@Transactional("payTxManager")
public class RedeemServiceImpl implements RedeemService {
    private final PointsReadStore read;
    private final PointsConfigurationStore config;
    private final AgreementService agreements;
    private final PointsPostingService posting;
    private final ObjectProvider<PointsFundsGateway> gateway;
    private final boolean enabled;
    private final RedeemPolicy policy=new DefaultRedeemPolicy();
    private final ObjectMapper json=new ObjectMapper();

    public RedeemServiceImpl(PointsReadStore read,PointsConfigurationStore config,AgreementService agreements,
                             PointsPostingService posting,ObjectProvider<PointsFundsGateway> gateway,
                             @Value("${shop.points.enabled:false}") boolean enabled) {
        this.read=read; this.config=config; this.agreements=agreements; this.posting=posting;
        this.gateway=gateway; this.enabled=enabled;
    }
    @Override public RedeemQuote quote(RedeemQuery q) {
        validate(q);
        PointType type=read.activeType(q.market()).orElse(null);
        if (type==null) { return new RedeemQuote(null,0,0,0,0,"POINTS_TYPE_NOT_ACTIVE",List.of()); }
        return evaluate(q,type).quote();
    }
    @Override public HoldOutcome hold(HoldCommand c) {
        if (c.orderNo()==null || c.orderNo().isBlank()) { throw new IllegalArgumentException("POINTS_ORDER_REQUIRED"); }
        RedeemQuery q=new RedeemQuery(c.market(),c.userNo(),c.wantPoints(),c.payMode(),c.clientType(),c.targets());
        validate(q);
        String root=root(c.orderNo());
        Map<String,Object> prior=read.position(PositionMove.Kind.REDEEM.table(),root);
        PointType type=prior==null?read.activeType(c.market()).orElse(null):
                read.type((String)prior.get("type_code")).orElseThrow();
        if (type==null) { return new HoldOutcome(root,null,0,0,"SKIPPED","POINTS_TYPE_NOT_ACTIVE",List.of()); }
        config.lockTypeAndPool(type.typeCode());
        prior=read.position(PositionMove.Kind.REDEEM.table(),root);
        if (prior!=null) {
            HoldSnapshot old=json.readValue((String)prior.get("payload_json"),HoldSnapshot.class);
            if (!old.command().equals(c)) { throw new IllegalArgumentException("POINTS_IDEMPOTENCY_CONFLICT"); }
            return old.outcome();
        }
        type=read.type(type.typeCode()).orElseThrow();
        Evaluation e=evaluate(q,type);
        RedeemQuote quote=e.quote();
        HoldOutcome outcome=new HoldOutcome(root,type.typeCode(),quote.points(),quote.minor(),
                quote.points()>0?"HELD":"SKIPPED",quote.reason(),quote.shares());
        List<PositionMove> positions=new ArrayList<>();
        List<ReserveMove> reserves=new ArrayList<>();
        for (Accepted accepted:e.accepted()) {
            Share share=quote.shares().stream().filter(s -> s.subOrderNo().equals(accepted.target().subOrderNo()))
                    .findFirst().orElse(null);
            if (share==null) { continue; }
            String pos=PointsKeys.position("R",type.typeCode(),share.subOrderNo());
            positions.add(new PositionMove(PositionMove.Kind.REDEEM,pos,-1,"NEW","HELD",share.points(),0,
                    json.writeValueAsString(new RedeemSnapshot(share.subOrderNo(),c.userNo(),c.orderNo(),share,
                            accepted.target(),accepted.agreement(),accepted.context(),false))));
            reserves.add(new ReserveMove(pos,pos,-1,share.minor(),share.minor(),0,0));
        }
        positions.add(new PositionMove(PositionMove.Kind.REDEEM,root,-1,"NEW",outcome.state(),outcome.points(),0,
                json.writeValueAsString(new HoldSnapshot(c,outcome))));
        List<PointsMove> moves=outcome.points()==0?List.of():List.of(
                userMove(type,c.userNo(),Bucket.AVAILABLE,-outcome.points()),
                userMove(type,c.userNo(),Bucket.HELD,outcome.points()));
        posting.post(new Voucher("HOLD:"+c.orderNo(),type.typeCode(),type.currency(),type.rate(),moves,List.of(),
                positions,outcome.minor(),List.of(),json.writeValueAsString(outcome),reserves));
        return outcome;
    }
    @Override public HoldOutcome release(ReleaseCommand c) {
        if (c.orderNo()==null || c.orderNo().isBlank() || c.sourceVersion()<0) {
            throw new IllegalArgumentException("POINTS_RELEASE_INPUT_INVALID");
        }
        String root=root(c.orderNo());
        var head=read.position(PositionMove.Kind.REDEEM.table(),root);
        if (head==null) { return new HoldOutcome(root,null,0,0,"ABSENT",null,List.of()); }
        PointType type=read.type((String)head.get("type_code")).orElseThrow();
        config.lockTypeAndPool(type.typeCode());
        head=read.position(PositionMove.Kind.REDEEM.table(),root);
        HoldSnapshot held=json.readValue((String)head.get("payload_json"),HoldSnapshot.class);
        if (!"HELD".equals(head.get("state"))) {
            if ("SKIPPED".equals(head.get("state"))) { return held.outcome(); }
            return releaseOutcome(held);
        }
        List<PositionMove> positions=new ArrayList<>();
        List<ReserveMove> reserves=new ArrayList<>();
        for (Share share:held.outcome().shares()) {
            String pos=PointsKeys.position("R",type.typeCode(),share.subOrderNo());
            var child=read.position(PositionMove.Kind.REDEEM.table(),pos);
            if (!"HELD".equals(child.get("state"))) {
                throw new IllegalArgumentException("POINTS_HOLD_ALREADY_PAID");
            }
            var reserve=read.reserve(pos);
            positions.add(new PositionMove(PositionMove.Kind.REDEEM,pos,num(child,"version"),"HELD","RELEASED",
                    share.points(),share.points(),(String)child.get("payload_json")));
            reserves.add(new ReserveMove(pos,pos,num(reserve,"version"),share.minor(),-share.minor(),0,share.minor()));
        }
        HoldOutcome result=releaseOutcome(held);
        positions.add(new PositionMove(PositionMove.Kind.REDEEM,root,num(head,"version"),"HELD","RELEASED",
                held.outcome().points(),held.outcome().points(),json.writeValueAsString(held)));
        posting.post(new Voucher("RELEASE:"+c.orderNo(),type.typeCode(),type.currency(),type.rate(),List.of(
                userMove(type,held.command().userNo(),Bucket.HELD,-held.outcome().points()),
                userMove(type,held.command().userNo(),Bucket.AVAILABLE,held.outcome().points())),List.of(),positions,
                -held.outcome().minor(),List.of(),json.writeValueAsString(result),reserves));
        return result;
    }
    private Evaluation evaluate(RedeemQuery q,PointType type) {
        String reason=null;
        if (!enabled) { reason="POINTS_DISABLED"; }
        else if (type.status()!=PointType.Status.ACTIVE && type.status()!=PointType.Status.DRAINING) {
            reason="POINTS_TYPE_NOT_ACTIVE";
        }
        else if ("OFFLINE".equals(q.payMode())) { reason="POINTS_OFFLINE"; }
        var account=read.account(type.typeCode(),HolderType.USER,q.userNo());
        if (reason==null && account.frozen()) { reason="POINTS_FROZEN"; }
        List<Accepted> accepted=new ArrayList<>();
        if (reason==null) {
            for (Target target:q.targets()) {
                AgreementSnapshot agreement=agreements.active(type.typeCode(),target.storeNo());
                if (agreement==null || !agreement.merchantNo().equals(target.merchantNo())) {
                    reason="POINTS_STORE_NOT_ACCEPTING"; break;
                }
                if (!Objects.equals(agreement.path(),target.path())
                        || !Objects.equals(agreement.channel(),target.channel())
                        || !Objects.equals(agreement.accountVersion(),target.accountVersion())) {
                    reason="POINTS_FUNDS_CONTEXT_CHANGED"; break;
                }
                FundsContext context=new FundsContext(FundsPath.valueOf(agreement.path()),agreement.channel(),
                        agreement.merchantNo(),agreement.accountVersion(),target.subMerchantId(),target.tradeNo(),
                        target.subOrderNo(),target.goodsMinor());
                var funds=gateway.getIfAvailable();
                if (funds==null) { reason="POINTS_CAPABILITY_UNSUPPORTED"; break; }
                var cap=funds.capabilities(context);
                boolean supported="PERIODIC".equals(agreement.settleMode())?cap.transfer():
                        context.path()==FundsPath.DIRECT?cap.internalReplenishment():cap.subsidy();
                if (!supported) { reason="POINTS_CAPABILITY_UNSUPPORTED"; break; }
                accepted.add(new Accepted(target,agreement,context));
            }
        }
        RedeemPolicy.Decision decision=reason==null?policy.calculate(q.wantPoints(),account.points(Bucket.AVAILABLE),
                type.deductRatioBp(),q.targets().stream().map(t -> new RedeemPolicy.Target(t.subOrderNo(),t.storeNo(),
                        t.goodsMinor(),true)).toList(),type.rate()):
                new RedeemPolicy.Decision(0,0,List.of());
        if (reason==null && decision.points()==0) { reason="POINTS_NO_REDEEMABLE_BALANCE"; }
        if (reason==null && read.pool(type.typeCode()).freeB()<decision.minor()) {
            reason="POINTS_B_RESERVE_INSUFFICIENT";
        }
        List<Share> shares=new ArrayList<>();
        if (reason==null) {
            for (var s:decision.shares()) {
                Target target=q.targets().stream().filter(t -> t.subOrderNo().equals(s.subOrderNo()))
                        .findFirst().orElseThrow();
                shares.add(new Share(s.subOrderNo(),target.merchantNo(),s.storeNo(),s.points(),s.minor()));
            }
        }
        return new Evaluation(new RedeemQuote(type.typeCode(),type.rate().pointsUnit(),
                type.rate().minorUnit(),
                reason==null?decision.points():0,reason==null?decision.minor():0,reason,shares),accepted);
    }
    private void validate(RedeemQuery q) {
        if (q.market()==null || q.userNo()==null || q.userNo().isBlank() || q.wantPoints()<0
                || q.payMode()==null || q.clientType()==null || q.targets().isEmpty()) {
            throw new IllegalArgumentException("POINTS_REDEEM_INPUT_INVALID");
        }
        policy.calculate(0,0,0,q.targets().stream().map(t -> new RedeemPolicy.Target(t.subOrderNo(),t.storeNo(),
                t.goodsMinor(),true)).toList(),new RedeemRate(1,1));
        for (Target t:q.targets()) {
            long sum=0;
            for (var item:t.items()) {
                if (item.itemId()==null || item.goodsMinor()<0) {
                    throw new IllegalArgumentException("POINTS_ITEMS_INVALID");
                }
                sum=Math.addExact(sum,item.goodsMinor());
            }
            if (sum!=t.goodsMinor()) { throw new IllegalArgumentException("POINTS_ITEM_TOTAL_MISMATCH"); }
        }
    }
    private PointsMove userMove(PointType type,String user,Bucket bucket,long points) {
        return new PointsMove(new AccountKey(type.typeCode(),HolderType.USER,user,bucket),points);
    }
    private HoldOutcome releaseOutcome(HoldSnapshot h) {
        HoldOutcome o=h.outcome();
        return new HoldOutcome(o.holdNo(),o.typeCode(),o.points(),o.minor(),"RELEASED",null,o.shares());
    }
    private String root(String order) { return PointsKeys.position("H","ORDER",order); }
    private long num(Map<String,Object> row,String key) { return ((Number)row.get(key)).longValue(); }
    public record HoldSnapshot(HoldCommand command,HoldOutcome outcome) { }
    public record RedeemSnapshot(String subOrderNo,String userNo,String orderNo,Share share,Target target,
                                 AgreementSnapshot agreement,FundsContext fundsContext,
                                 boolean finalSnapshotReceived) { }
    private record Accepted(Target target,AgreementSnapshot agreement,FundsContext context) { }
    private record Evaluation(RedeemQuote quote,List<Accepted> accepted) { }
}
