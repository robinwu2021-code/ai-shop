package ai.neargo.shop.paybridge.impl;

import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.migration.dto.LegacyCouponIssueViews;
import ai.neargo.shop.pay.migration.dto.LegacyCouponTemplateViews.DefinitionChange;
import ai.neargo.shop.pay.migration.dto.LegacyCouponTemplateViews.Reference;
import ai.neargo.shop.pay.migration.dto.LegacyCouponTemplateViews.Snapshot;
import ai.neargo.shop.paybridge.LegacyCouponIssueCoordinator;
import ai.neargo.shop.paybridge.LegacyCouponWalletProjectionService;
import ai.neargo.shop.payclient.LegacyCouponIssueAppService;
import ai.neargo.shop.payclient.LegacyCouponTemplateAppService;
import ai.neargo.shop.promotion.dto.CouponVOs.CouponIssueVO;
import ai.neargo.shop.promotion.dto.CouponVOs.CouponSaveCmd;
import ai.neargo.shop.promotion.dto.CouponVOs.CouponVO;
import ai.neargo.shop.promotion.dto.CouponVOs.CustomerCoupon;
import ai.neargo.shop.promotion.dto.CouponVOs.HeldCoupon;
import ai.neargo.shop.promotion.dto.CouponVOs.MyCouponVO;
import ai.neargo.shop.promotion.service.CouponService;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.MigrationState;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.OwnerSystem;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.SourceIdentity;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.SourceKind;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.SourceSystem;
import ai.neargo.shop.spi.entitlement.LegacyCouponIdentityPort;
import ai.neargo.shop.spi.entitlement.LegacyCouponSourceCommitPort;
import ai.neargo.shop.spi.member.MemberQueryPort.AudienceItem;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Original contract, exact immutable owner routing, no writes to the frozen legacy source. */
@Primary @Service
public class LegacyCouponCompatibilityServiceImpl implements CouponService {
    @Autowired private LegacyCouponIssueCoordinator issueCoordinator;
    @Autowired private LegacyCouponIssueAppService issueOwner;
    @Autowired private LegacyCouponWalletProjectionService wallet;
    @Autowired private LegacyCouponIdentityPort identities;
    private final CouponService legacy;
    private final LegacyCouponSourceCommitPort decisions;
    private final EntitlementOwnerRegistryPort owners;
    private final LegacyCouponTemplateAppService pay;
    public LegacyCouponCompatibilityServiceImpl(@Qualifier("pmtCouponServiceImpl") CouponService legacy,
            LegacyCouponSourceCommitPort decisions,EntitlementOwnerRegistryPort owners,LegacyCouponTemplateAppService pay) {
        this.legacy=legacy;this.decisions=decisions;this.owners=owners;this.pay=pay;
    }
    private record Routed(CatalogScope scope,Reference ref) { }
    private Routed route(String entity,String no) {
        var ctx=new EntitlementContext("MAIN",SecurityUtils.currentUser().map(u->u.userNo()).orElse("LEGACY_TEMPLATE"),"LEGACY_TEMPLATE","0".repeat(64));
        var source=new SourceIdentity(SourceSystem.PMT,SourceKind.TEMPLATE,no);
        var result=decisions.decided(ctx,source);
        if(result==null) throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
        if(result.isEmpty()) return null;
        if(TransactionSynchronizationManager.isActualTransactionActive()) throw BizException.of(ErrorCode.CONFLICT);
        var proof=result.get();var owner=owners.resolve(ctx,source);
        if(!source.equals(proof.template()) || !source.equals(owner.source()) || owner.ownerSystem()!=OwnerSystem.PAY
                || owner.status()!=MigrationState.CUTOVER || !proof.migrationBatchNo().equals(owner.migrationBatchNo())
                || !proof.planHash().equals(owner.cutoverWatermark())) throw BizException.of(ErrorCode.CONFLICT);
        return new Routed(new CatalogScope("MAIN",entity,ctx.actorNo(),false),new Reference(source,owner.targetNo(),owner.migrationBatchNo(),owner.cutoverWatermark()));
    }
    public List<CouponVO> list(String entity,boolean ended) {
        return legacy.list(entity,ended).stream().map(v->project(entity,v)).filter(v->ended || !"ENDED".equals(v.status())).toList();
    }
    public CouponVO detail(String entity,String no) {return project(entity,legacy.detail(entity,no));}
    private CouponVO project(String entity,CouponVO original) {
        var route=route(entity,original.couponNo());return route==null?original:view(original,pay.get(route.scope(),route.ref()));
    }
    public CouponVO setStatus(String entity,String no,String status) {
        var original=legacy.detail(entity,no);var route=route(entity,no);
        if(route==null) return legacy.setStatus(entity,no,status);
        return view(original,pay.status(route.scope(),route.ref(),status,"LS"+UUID.randomUUID()));
    }
    private static CouponVO view(CouponVO old,Snapshot s) {
        var d=s.definition();var p=s.template().issuePolicy();var validity=d.validity();
        String status="ACTIVE".equals(s.template().status()) && !p.enabled()?"PAUSED":s.template().status();
        Long max=p.limitCount()==null?null:Math.multiplyExact(p.limitCount(),Math.multiplyExact((long)d.totalUnits(),
                "CASH".equals(d.benefitMode())?d.benefitValue():d.benefitCapMinor()));
        return new CouponVO(old.couponNo(),d.title(),d.benefitMode(),d.benefitValue(),d.benefitCapMinor(),d.benefitRef(),
                d.minAmountMinor(),d.minQty(),d.redemptionScope().mode(),d.redemptionScope().refs(),old.scopeDesc(),validity.mode(),
                validity.startAt()==null?null:validity.startAt().toEpochMilli(),validity.endAt()==null?null:validity.endAt().toEpochMilli(),validity.validDays(),
                d.allowedIssueModes().contains("CENTER")?"CENTER":d.allowedIssueModes().contains("ACTIVITY")?"ACTIVITY":"TARGETED","ORDER",d.totalUnits(),
                p.limitCount()==null?null:Math.toIntExact(p.limitCount()),Math.toIntExact(p.issuedCount()),p.perUserLimit(),
                p.maxExposureMinor()==null?0L:p.maxExposureMinor(),max,status,Math.toIntExact(s.usedTimes()),s.spentMinor());
    }
    public CouponVO save(String e,CouponSaveCmd c,String actor) {
        if(c==null) throw BizException.of(ErrorCode.BAD_REQUEST);
        if(c.couponNo()==null || c.couponNo().isBlank()) return legacy.save(e,c,actor);
        var original=legacy.detail(e,c.couponNo());var routed=route(e,c.couponNo());
        if(routed==null) return legacy.save(e,c,actor);
        if(!"ORDER".equals(c.redeemMode()) || !"ALL".equals(c.scopeType())
                || c.issueMode()==null || !Set.of("CENTER","TARGETED","ACTIVITY").contains(c.issueMode())) throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
        var current=pay.get(routed.scope(),routed.ref()).definition();
        boolean relative="RELATIVE".equals(c.validityMode());
        var validity=new EntitlementDefinition.Validity(c.validityMode(),relative || c.startAt()==null?null:java.time.Instant.ofEpochMilli(c.startAt()),
                relative || c.endAt()==null?null:java.time.Instant.ofEpochMilli(c.endAt()),relative?c.validDays():null,"GRANTED","ROLLING_DAYS",current.validity().zone());
        var definition=new EntitlementDefinition(c.title(),"ORDER_COUPON","MERCHANT","CNY",c.benefitMode(),c.benefitValue(),
                c.benefitCapMinor(),c.benefitRef(),c.minAmountMinor(),c.minQty(),"COUNT",c.timesTotal()==null?1:c.timesTotal(),null,validity,
                new EntitlementDefinition.RedemptionScope("ALL",c.scopeRefs()==null?List.of():c.scopeRefs()),
                "CENTER".equals(c.issueMode())?List.of("CENTER","TARGETED"):"ACTIVITY".equals(c.issueMode())?List.of("TARGETED","ACTIVITY"):List.of("TARGETED"));
        var scope=new CatalogScope("MAIN",e,actor,false);
        return view(original,pay.save(scope,routed.ref(),new DefinitionChange(definition,c.totalCount()==null?null:c.totalCount().longValue(),
                c.budgetMinor()==null || c.budgetMinor()==0?null:c.budgetMinor(),c.perUserLimit()==null?1:c.perUserLimit()),"LS"+UUID.randomUUID()));
    }
    public CouponIssueVO issue(String e,String no,String segment,List<AudienceItem> a,String actor) {
        legacy.detail(e,no);var routed=route(e,no);if(routed==null) return legacy.issue(e,no,segment,a,actor);
        var result=issueCoordinator.issue(new CatalogScope("MAIN",e,actor,false),
                new LegacyCouponIssueViews.Command(routed.ref(),"TARGETED",segment,a),"LI"+UUID.randomUUID());
        return issueView(result);
    }
    private static CouponIssueVO issueView(LegacyCouponIssueViews.View v) {
        return new CouponIssueVO(v.issueNo(),v.sourceTemplateNo(),v.segmentNo(),v.planned(),v.issued(),v.skipped(),
                v.skipReasons().entrySet().stream().map(e->new CouponIssueVO.SkipReason(e.getKey(),e.getValue())).toList(),
                v.amountMinor(),v.actorNo(),v.occurredAt().toEpochMilli(),v.audiences(),v.usedCount(),v.usedAmountMinor());
    }
    public List<CouponIssueVO> issues(String e,String no) {
        var rows=new ArrayList<>(legacy.issues(e,no));
        if(no!=null && !no.isBlank()) legacy.detail(e,no);
        var scope=new CatalogScope("MAIN",e,SecurityUtils.currentUser().map(u->u.userNo()).orElse("LEGACY_TEMPLATE"),false);
        issueOwner.list(scope,"PMT",no).stream().filter(v->"TARGETED".equals(v.mode())).forEach(v->rows.add(issueView(v)));
        rows.sort(Comparator.comparingLong(CouponIssueVO::issuedAt).reversed());return List.copyOf(rows);
    }
    public List<MyCouponVO> myCoupons(String user) {
        var rows=new ArrayList<>(legacy.myCoupons(user));rows.addAll(wallet.issuedWallet(user));return List.copyOf(rows);
    }
    public List<CustomerCoupon> center(String user) {
        var out=new ArrayList<CustomerCoupon>();var originals=new LinkedHashMap<String,CustomerCoupon>();
        legacy.center(user).forEach(c->originals.put(c.couponNo(),c));
        var candidates=new LinkedHashMap<String,String>();originals.values().forEach(c->candidates.put(c.couponNo(),c.entityNo()));
        var ctx=new EntitlementContext("MAIN",user==null?"PUBLIC_COUPON_VIEW":user,"LEGACY_CENTER_VIEW","0".repeat(64));
        identities.committedTemplates(ctx,SourceSystem.PMT).forEach(t->candidates.put(t.templateNo(),t.entityNo()));
        for(var candidate:candidates.entrySet()) {
            String no=candidate.getKey(),entity=candidate.getValue();
            var routed=route(entity,no);if(routed==null) {var old=originals.get(no);if(old!=null) out.add(old);continue;}
            var scope=new CatalogScope("MAIN",entity,user==null?"PUBLIC_COUPON_VIEW":user,false);
            var snapshot=pay.get(scope,routed.ref());var head=snapshot.template();var d=snapshot.definition();var p=head.issuePolicy();
            if(snapshot.archivedAt()!=null || !"ACTIVE".equals(head.status()) || !p.enabled() || !d.allowedIssueModes().contains("CENTER")) continue;
            boolean relative="RELATIVE".equals(d.validity().mode());var now=java.time.Instant.now();
            if(!relative && (now.isBefore(d.validity().startAt()) || !now.isBefore(d.validity().endAt()))) continue;
            long stock=p.limitCount()==null?Integer.MAX_VALUE:Math.max(0,p.limitCount()-p.heldCount()-p.issuedCount());
            out.add(new CustomerCoupon(no,d.title(),d.benefitMode(),d.benefitValue(),d.benefitCapMinor()==null?0:d.benefitCapMinor(),
                    d.minAmountMinor()==null?0:d.minAmountMinor(),entity,d.funder(),relative?0:d.validity().startAt().toEpochMilli(),
                    relative?0:d.validity().endAt().minusMillis(1).toEpochMilli(),d.validity().validDays(),(int)Math.min(Integer.MAX_VALUE,stock),
                    snapshot.actorHeldCount()+snapshot.actorIssuedCount()>=p.perUserLimit(),"ACTIVE"));
        }
        return List.copyOf(out);
    }
    public HeldCoupon receive(String user,String no) {
        var ctx=new EntitlementContext("MAIN",user,"LEGACY_CENTER","0".repeat(64));
        var entity=identities.templateEntity(ctx,SourceSystem.PMT,no).orElseThrow(()->BizException.of(ErrorCode.NOT_FOUND));
        var routed=route(entity,no);if(routed==null) return legacy.receive(user,no);
        var result=issueCoordinator.issue(new CatalogScope("MAIN",entity,user,false),
                new LegacyCouponIssueViews.Command(routed.ref(),"CENTER",null,List.of()),"LC"+UUID.randomUUID());
        if(result.issued()==0) throw BizException.of(ErrorCode.COUPON_SOLD_OUT);
        String instance=result.results().stream().filter(i->"ISSUED".equals(i.status()) && user.equals(i.userNo())).findFirst().orElseThrow().instanceNo();
        return wallet.issuedHeld(user).stream().filter(c->instance.equals(c.userCouponNo())).findFirst().orElseThrow(()->BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE));
    }
    public List<HeldCoupon> held(String user) {
        var rows=new ArrayList<>(legacy.held(user));rows.addAll(wallet.issuedHeld(user));return List.copyOf(rows);
    }
    public boolean ownsTemplate(String no) {return legacy.ownsTemplate(no);}
}
