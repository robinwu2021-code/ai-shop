package ai.neargo.shop.pay.points.earn.impl;

import ai.neargo.shop.pay.points.earn.EarnService;
import ai.neargo.shop.pay.points.model.AccountKey;
import ai.neargo.shop.pay.points.model.Bucket;
import ai.neargo.shop.pay.points.model.FundBucket;
import ai.neargo.shop.pay.points.model.FundMove;
import ai.neargo.shop.pay.points.model.HolderType;
import ai.neargo.shop.pay.points.model.PointType;
import ai.neargo.shop.pay.points.model.PointsKeys;
import ai.neargo.shop.pay.points.model.PointsMove;
import ai.neargo.shop.pay.points.model.PositionMove;
import ai.neargo.shop.pay.points.model.Voucher;
import ai.neargo.shop.pay.points.policy.DefaultEarnPolicy;
import ai.neargo.shop.pay.points.policy.EarnPolicy;
import ai.neargo.shop.pay.points.posting.PointsPostingService;
import ai.neargo.shop.pay.points.store.PointsConfigurationStore;
import ai.neargo.shop.pay.points.store.PointsReadStore;
import ai.neargo.shop.spi.points.PointsEarnPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/** Pool locking makes the last-budget decision and its entire grant atomic, with a recorded skipped result. */
@Service
@Transactional("payTxManager")
public class EarnServiceImpl implements EarnService {
    private final PointsReadStore read;
    private final PointsConfigurationStore config;
    private final PointsPostingService posting;
    private final boolean enabled;
    private final EarnPolicy policy=new DefaultEarnPolicy();
    private final ObjectMapper json=new ObjectMapper();
    public EarnServiceImpl(PointsReadStore read,PointsConfigurationStore config,PointsPostingService posting,
                           @Value("${shop.points.enabled:false}") boolean enabled) {
        this.read=read; this.config=config; this.posting=posting; this.enabled=enabled;
    }
    @Override public PointsEarnPort.GrantOutcome grant(PointsEarnPort.GrantCommand c) {
        if (c.subOrderNo()==null || c.userNo()==null || c.typeCode()==null || c.sourceVersion()<0) {
            throw new IllegalArgumentException("POINTS_EARN_INPUT_INVALID");
        }
        config.lockTypeAndPool(c.typeCode());
        String ref="GRANT:"+c.subOrderNo();
        String position=PointsKeys.position("G",c.typeCode(),c.subOrderNo());
        var prior=read.position(PositionMove.Kind.GRANT.table(),position);
        if (prior!=null) {
            var stored=json.readTree((String)prior.get("payload_json"));
            var original=json.treeToValue(stored.get("command"),PointsEarnPort.GrantCommand.class);
            if (!original.userNo().equals(c.userNo()) || !original.typeCode().equals(c.typeCode())
                    || !original.lines().equals(c.lines()) || !java.util.Objects.equals(original.payChannel(),c.payChannel())) {
                throw new IllegalArgumentException("POINTS_IDEMPOTENCY_CONFLICT");
            }
            return outcome(ref);
        }
        PointType type=read.type(c.typeCode()).orElseThrow();
        var decision=policy.calculate(c.lines().stream().map(l -> new EarnPolicy.Line(l.itemId(),l.quantity(),
                l.goodsCashMinor(),l.refundedBeforeGrantMinor(),l.rule()==null?EarnPolicy.Mode.FIXED:
                EarnPolicy.Mode.valueOf(l.rule().mode().name()),l.rule()==null?0:l.rule().value())).toList(),type.rate());
        String reason=null;
        boolean finalized=false;
        var fact=read.lifecycle(type.typeCode(),c.subOrderNo());
        if (fact!=null) {
            var state=json.readTree((String)fact.get("payload_json"));
            if (state.path("cancelled").asBoolean(false)) { reason="POINTS_ORDER_CANCELLED"; }
            finalized=state.path("finalized").asBoolean(false);
        }
        if (reason==null && !enabled) { reason="POINTS_DISABLED"; }
        if (reason==null && !"ACTIVE".equals(type.status().name())) { reason="POINTS_TYPE_NOT_ACTIVE"; }
        if (reason==null && ("OFFLINE".equals(c.payChannel()) || c.payChannel()==null)) { reason="POINTS_OFFLINE"; }
        if (reason==null && read.account(c.typeCode(),HolderType.USER,c.userNo()).frozen()) { reason="POINTS_FROZEN"; }
        if (reason==null && decision.grantedPoints()==0) { reason="POINTS_RULE_ZERO"; }
        long amount=type.rate().toMinor(decision.grantedPoints());
        if (reason==null && read.pool(c.typeCode()).u()<amount) { reason="POINTS_POOL_INSUFFICIENT"; }
        boolean granted=reason==null;
        long actual=granted?decision.grantedPoints():0;
        var result=new PointsEarnPort.GrantOutcome(granted?PointsEarnPort.GrantStatus.GRANTED:
                PointsEarnPort.GrantStatus.SKIPPED,actual,decision.grantedPoints(),reason,null);
        String resultJson=json.writeValueAsString(result);
        String payload=json.writeValueAsString(new GrantSnapshot(c.subOrderNo(),c.userNo(),c,decision,result,
                finalized?actual:0,0,0,0,0));
        List<PointsMove> points=granted?List.of(new PointsMove(new AccountKey(type.typeCode(),HolderType.ISSUE,
                "PLATFORM",Bucket.ISSUABLE),-actual),new PointsMove(new AccountKey(type.typeCode(),HolderType.USER,
                c.userNo(),finalized?Bucket.AVAILABLE:Bucket.PENDING),actual)):List.of();
        List<FundMove> funds=granted?List.of(new FundMove(FundBucket.U,-amount),new FundMove(FundBucket.C,amount)):List.of();
        posting.post(new Voucher(ref,type.typeCode(),type.currency(),type.rate(),points,funds,
                List.of(new PositionMove(PositionMove.Kind.GRANT,position,-1,"NEW",granted?"GRANTED":"SKIPPED",
                        actual,0,payload)),0,List.of(),resultJson));
        return outcome(ref);
    }
    private PointsEarnPort.GrantOutcome outcome(String ref) {
        var voucher=read.voucher(ref);
        var r=json.readValue((String)voucher.get("result_json"),PointsEarnPort.GrantOutcome.class);
        return new PointsEarnPort.GrantOutcome(r.status(),r.grantedPoints(),r.expectedPoints(),r.reason(),
                (String)voucher.get("voucher_no"));
    }
    public record GrantSnapshot(String subOrderNo,String userNo,PointsEarnPort.GrantCommand command,
                                EarnPolicy.EarnDecision decision,PointsEarnPort.GrantOutcome outcome,
                                long activatedPoints,long reclaimedPending,long reclaimedAvailable,
                                long collectedMinor,long waivedPoints) { }
}
