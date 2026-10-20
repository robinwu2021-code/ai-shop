package ai.neargo.shop.paybridge.impl;

import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.marketing.coupon.CouponService;
import ai.neargo.shop.marketing.coupon.dto.CouponIssueVO;
import ai.neargo.shop.marketing.coupon.dto.CouponSaveCmd;
import ai.neargo.shop.marketing.coupon.dto.CouponVO;
import ai.neargo.shop.marketing.coupon.dto.OpsCouponVO;
import ai.neargo.shop.marketing.coupon.dto.UserCouponVO;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.entitlement.dto.IssuePolicyViews;
import ai.neargo.shop.pay.migration.dto.LegacyCouponIssueViews;
import ai.neargo.shop.pay.migration.dto.LegacyCouponTemplateViews.DefinitionChange;
import ai.neargo.shop.pay.migration.dto.LegacyCouponTemplateViews.Reference;
import ai.neargo.shop.pay.migration.dto.LegacyCouponTemplateViews.Snapshot;
import ai.neargo.shop.paybridge.LegacyCouponIssueCoordinator;
import ai.neargo.shop.paybridge.LegacyCouponWalletProjectionService;
import ai.neargo.shop.payclient.EntitlementManagementAppService;
import ai.neargo.shop.payclient.LegacyCouponIssueAppService;
import ai.neargo.shop.payclient.LegacyCouponTemplateAppService;
import ai.neargo.shop.spi.entitlement.EntitlementCatalogPort;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.MigrationState;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.OwnerSystem;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.SourceIdentity;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.SourceKind;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.SourceSystem;
import ai.neargo.shop.spi.entitlement.LegacyCouponIdentityPort;
import ai.neargo.shop.spi.entitlement.LegacyCouponIssuedProjectionPort;
import ai.neargo.shop.spi.entitlement.LegacyCouponSourceCommitPort;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Preserve the original MKT contracts while financial ownership follows the immutable cutover decision. */
@Primary @Service
public class MktCouponCompatibilityServiceImpl implements CouponService {
    private final CouponService legacy;
    private final LegacyCouponSourceCommitPort decisions;
    private final EntitlementOwnerRegistryPort owners;
    private final LegacyCouponIdentityPort identities;
    private final LegacyCouponTemplateAppService templates;
    private final LegacyCouponIssueCoordinator issuer;
    private final LegacyCouponIssueAppService issues;
    private final LegacyCouponWalletProjectionService wallet;
    private final EntitlementManagementAppService management;
    private final EntitlementCatalogPort catalog;
    private final LegacyCouponIssuedProjectionPort projections;
    public MktCouponCompatibilityServiceImpl(@Qualifier("couponServiceImpl") CouponService legacy,
            LegacyCouponSourceCommitPort decisions,EntitlementOwnerRegistryPort owners,LegacyCouponIdentityPort identities,
            LegacyCouponTemplateAppService templates,LegacyCouponIssueCoordinator issuer,LegacyCouponIssueAppService issues,
            LegacyCouponWalletProjectionService wallet,EntitlementManagementAppService management,EntitlementCatalogPort catalog,
            LegacyCouponIssuedProjectionPort projections) {
        this.legacy=legacy;this.decisions=decisions;this.owners=owners;this.identities=identities;this.templates=templates;
        this.issuer=issuer;this.issues=issues;this.wallet=wallet;this.management=management;this.catalog=catalog;this.projections=projections;
    }
    private EntitlementContext context(String actor) {return new EntitlementContext("MAIN",actor,"LEGACY_MKT","0".repeat(64));}
    private String actor() {return SecurityUtils.currentUser().map(u->u.userNo()).orElse("PUBLIC_COUPON_VIEW");}
    private record Route(CatalogScope scope,Reference ref) { }
    private Route route(String no,String actor,boolean ops) {
        var ctx=context(actor);var source=new SourceIdentity(SourceSystem.MKT_LEGACY,SourceKind.TEMPLATE,no);
        var proof=decisions.decided(ctx,source);
        if(proof==null) throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
        if(proof.isEmpty()) return null;
        if(TransactionSynchronizationManager.isActualTransactionActive()) throw BizException.of(ErrorCode.CONFLICT);
        var owner=owners.resolve(ctx,source);var fact=proof.get();
        if(!source.equals(owner.source()) || !source.equals(fact.template()) || owner.ownerSystem()!=OwnerSystem.PAY
                || owner.status()!=MigrationState.CUTOVER || !fact.migrationBatchNo().equals(owner.migrationBatchNo())
                || !fact.planHash().equals(owner.cutoverWatermark())) throw BizException.of(ErrorCode.CONFLICT);
        String entity=identities.templateEntity(ctx,SourceSystem.MKT_LEGACY,no).orElseThrow(()->BizException.of(ErrorCode.CONFLICT));
        return new Route(new CatalogScope("MAIN",entity,actor,ops),new Reference(source,owner.targetNo(),owner.migrationBatchNo(),owner.cutoverWatermark()));
    }
    private OpsCouponVO original(String no) {
        return legacy.opsCoupons(null,true).stream().filter(v->no.equals(v.couponNo())).findFirst().orElseThrow(()->BizException.of(ErrorCode.NOT_FOUND));
    }
    private static String status(Snapshot s) {return "ACTIVE".equals(s.template().status()) && !s.template().issuePolicy().enabled()?"PAUSED":s.template().status();}
    private static long cap(EntitlementDefinition d) {return d.benefitCapMinor()==null?0:d.benefitCapMinor();}
    private static long threshold(EntitlementDefinition d) {return d.minAmountMinor()==null?0:d.minAmountMinor();}
    private static long start(EntitlementDefinition d) {return d.validity().startAt()==null?0:d.validity().startAt().toEpochMilli();}
    private static long end(EntitlementDefinition d) {return d.validity().endAt()==null?0:d.validity().endAt().minusMillis(1).toEpochMilli();}
    private static String type(EntitlementDefinition d) {return "CASH".equals(d.benefitMode())?"FULL_CUT":"DISCOUNT";}
    private static long stock(Snapshot s) {var p=s.template().issuePolicy();return p.limitCount()==null?Integer.MAX_VALUE:Math.max(0,p.limitCount()-p.heldCount()-p.issuedCount());}
    private static OpsCouponVO view(OpsCouponVO old,Snapshot s) {
        var d=s.definition();var p=s.template().issuePolicy();
        return new OpsCouponVO(old.couponNo(),d.title(),type(d),status(s),d.benefitValue(),threshold(d),d.funder(),old.merchantNo(),start(d),end(d),
                p.maxExposureMinor()==null?0:p.maxExposureMinor(),"CASH".equals(d.benefitMode())?p.usedExposureMinor():0,
                Math.toIntExact(p.issuedCount()),Math.toIntExact(s.usedCouponCount()),old.createdAt(),s.archivedAt()==null?null:s.archivedAt().toEpochMilli(),
                p.limitCount()==null?Integer.MAX_VALUE:Math.toIntExact(p.limitCount()),p.perUserLimit(),cap(d));
    }
    private static CouponVO consumer(String no,String entity,Snapshot s) {
        var d=s.definition();return new CouponVO(no,d.title(),type(d),"CASH".equals(d.benefitMode())?d.benefitValue():0,
                "PERCENT".equals(d.benefitMode())?Math.toIntExact(d.benefitValue()):0,threshold(d),cap(d),d.funder(),entity,start(d),end(d),
                (int)Math.min(Integer.MAX_VALUE,stock(s)),s.actorHeldCount()+s.actorIssuedCount()>=s.template().issuePolicy().perUserLimit(),status(s),null);
    }
    public boolean hasActiveBinding(String tenant,String order) {return legacy.hasActiveBinding(tenant,order);}
    public List<OpsCouponVO> opsCoupons(String status,boolean archived) {
        var result=new ArrayList<OpsCouponVO>();
        for(var old:legacy.opsCoupons(null,true)) {
            var r=route(old.couponNo(),actor(),true);var v=r==null?old:view(old,templates.get(r.scope(),r.ref()));
            if((status==null || status.isBlank() || status.equals(v.status())) && (archived || v.archivedAt()==null)) result.add(v);
        }
        return List.copyOf(result);
    }
    public CouponVO setCouponStatus(String no,String status,String reason,String operator) {
        var old=original(no);var r=route(no,operator,true);if(r==null) return legacy.setCouponStatus(no,status,reason,operator);
        if(reason==null || reason.isBlank()) throw BizException.of(ErrorCode.BAD_REQUEST);
        return consumer(no,old.merchantNo(),templates.status(r.scope(),r.ref(),status,"MS"+UUID.randomUUID()));
    }
    public OpsCouponVO setBudget(String no,long budget,String operator) {
        var old=original(no);var r=route(no,operator,true);if(r==null) return legacy.setBudget(no,budget,operator);
        if(budget<0) throw BizException.of(ErrorCode.BAD_REQUEST);
        var p=templates.get(r.scope(),r.ref()).template().issuePolicy();
        management.policy(r.scope(),r.ref().targetNo(),new IssuePolicyViews.SavePolicy(
                p.revision(),p.limitCount(),budget==0?null:budget,p.perUserLimit(),p.enabled()),"MB"+UUID.randomUUID());
        return view(old,templates.get(r.scope(),r.ref()));
    }
    public OpsCouponVO saveCoupon(CouponSaveCmd c,String operator) {
        if(c==null) throw BizException.of(ErrorCode.BAD_REQUEST);
        if(c.couponNo()==null || c.couponNo().isBlank()) return legacy.saveCoupon(c,operator);
        var old=original(c.couponNo());var r=route(c.couponNo(),operator,true);if(r==null) return legacy.saveCoupon(c,operator);
        var current=templates.get(r.scope(),r.ref()).definition();
        if(!Set.of("FULL_CUT","DISCOUNT").contains(c.type()) || c.startAt()==null || c.endAt()==null || c.totalCount()==null || c.totalCount()<=0
                || c.endAt()<=c.startAt() || !"ALL".equals(current.redemptionScope().mode()) || current.totalUnits()!=1) throw BizException.of(ErrorCode.BAD_REQUEST);
        boolean cash="FULL_CUT".equals(c.type());
        if(cash?c.faceMinor()==null || c.faceMinor()<=0:c.discountRate()==null || c.discountRate()<=0 || c.discountRate()>=10000 || c.maxDiscountMinor()==null || c.maxDiscountMinor()<=0)
            throw BizException.of(ErrorCode.BAD_REQUEST);
        var d=new EntitlementDefinition(c.title(),"ORDER_COUPON","MERCHANT","CNY",cash?"CASH":"PERCENT",cash?c.faceMinor():c.discountRate().longValue(),
                cash?null:c.maxDiscountMinor(),null,c.thresholdMinor()==null?0:c.thresholdMinor(),null,"COUNT",1,null,
                new EntitlementDefinition.Validity("ABSOLUTE",Instant.ofEpochMilli(c.startAt()),Instant.ofEpochMilli(c.endAt()).plusMillis(1),null,"GRANTED","ROLLING_DAYS",current.validity().zone()),
                new EntitlementDefinition.RedemptionScope("ALL",List.of()),List.of("CENTER","TARGETED"));
        return view(old,templates.save(r.scope(),r.ref(),new DefinitionChange(d,c.totalCount().longValue(),c.budgetMinor()==null || c.budgetMinor()==0?null:c.budgetMinor(),c.perUserLimit()==null?1:c.perUserLimit()),"ME"+UUID.randomUUID()));
    }
    public CouponIssueVO issue(String no,String target,String desc,String user,int count,String operator) {
        original(no);var r=route(no,operator,true);if(r==null) return legacy.issue(no,target,desc,user,count,operator);
        if(!"SINGLE_USER".equals(target) || user==null || user.isBlank() || count<1 || count>1000) throw BizException.of(ErrorCode.BAD_REQUEST);
        var result=issuer.issue(r.scope(),new LegacyCouponIssueViews.Command(r.ref(),"TARGETED",null,List.of(),user,count,desc),"MI"+UUID.randomUUID());
        if(result.issued()!=count) throw BizException.of(ErrorCode.COUPON_SOLD_OUT);
        return issueView(result);
    }
    private CouponIssueVO issueView(LegacyCouponIssueViews.View v) {
        String title=catalog.getPublishedVersion("MAIN",v.entityNo(),v.templateVersionNo()).definition().title();
        return new CouponIssueVO(v.issueNo(),v.sourceTemplateNo(),title,"SINGLE_USER",v.targetDesc(),v.issued(),v.amountMinor(),v.actorNo(),v.occurredAt().toEpochMilli());
    }
    public List<CouponIssueVO> issues(String no) {
        if(no!=null && !no.isBlank()) original(no);
        var out=new ArrayList<>(legacy.issues(no));
        for(var old:legacy.opsCoupons(null,true)) {
            if(no!=null && !no.isBlank() && !no.equals(old.couponNo())) continue;
            var r=route(old.couponNo(),actor(),true);if(r==null) continue;
            issues.list(r.scope(),"MKT_LEGACY",old.couponNo()).stream().filter(v->"TARGETED".equals(v.mode()) && v.issued()>0).forEach(v->out.add(issueView(v)));
        }
        out.sort(Comparator.comparingLong(CouponIssueVO::createdAt).reversed());return List.copyOf(out);
    }
    private boolean canonicalPromoEntry(Route route,String no,String user) {
        var source=new SourceIdentity(SourceSystem.PMT,SourceKind.TEMPLATE,no);var ctx=context(user);
        var decided=decisions.decided(ctx,source);
        if(decided==null) throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
        if(decided.isEmpty()) return false;
        var owner=owners.resolve(ctx,source);var fact=decided.get();
        if(!source.equals(owner.source()) || !source.equals(fact.template()) || owner.ownerSystem()!=OwnerSystem.PAY || owner.status()!=MigrationState.CUTOVER
                || !fact.migrationBatchNo().equals(owner.migrationBatchNo()) || !fact.planHash().equals(owner.cutoverWatermark())) throw BizException.of(ErrorCode.CONFLICT);
        return route.ref().targetNo().equals(owner.targetNo());
    }
    public List<CouponVO> center() {
        String user=actor();var originals=new LinkedHashMap<String,CouponVO>();legacy.center().forEach(v->originals.put(v.couponNo(),v));
        var candidates=new LinkedHashSet<>(originals.keySet());identities.committedTemplates(context(user),SourceSystem.MKT_LEGACY).forEach(v->candidates.add(v.templateNo()));
        var out=new ArrayList<CouponVO>();
        for(String no:candidates) {
            var r=route(no,user,false);if(r==null) {if(originals.containsKey(no)) out.add(originals.get(no));continue;}
            if(canonicalPromoEntry(r,no,user)) continue;
            var s=templates.get(r.scope(),r.ref());var d=s.definition();var now=Instant.now();
            if(s.archivedAt()!=null || !"ACTIVE".equals(status(s)) || !d.allowedIssueModes().contains("CENTER") || (!"RELATIVE".equals(d.validity().mode()) && (now.isBefore(d.validity().startAt()) || !now.isBefore(d.validity().endAt())))) continue;
            out.add(consumer(no,r.scope().entityNo(),s));
        }
        return List.copyOf(out);
    }
    public UserCouponVO receive(String no) {
        String user=SecurityUtils.currentUserNo();var r=route(no,user,false);if(r==null) return legacy.receive(no);
        var result=issuer.issue(r.scope(),new LegacyCouponIssueViews.Command(r.ref(),"CENTER",null,List.of()),"MC"+UUID.randomUUID());
        if(result.issued()==0) throw BizException.of(ErrorCode.COUPON_SOLD_OUT);
        String instance=result.results().stream().filter(i->"ISSUED".equals(i.status()) && user.equals(i.userNo())).findFirst().orElseThrow().instanceNo();
        return mine().stream().filter(v->instance.equals(v.userCouponNo())).findFirst().orElseThrow(()->BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE));
    }
    public UserCouponVO grantTo(String user,String no) {
        if(route(no,actor(),false)!=null) throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
        return legacy.grantTo(user,no);
    }
    public List<UserCouponVO> mine() {
        String user=SecurityUtils.currentUserNo();var result=new ArrayList<>(legacy.mine());var ids=new HashSet<String>();
        projections.owned(context(user),user).stream().filter(v->"MKT_LEGACY".equals(v.sourceSystem())).forEach(v->ids.add(v.instanceNo()));
        for(var held:wallet.issuedHeld(user)) {
            if(!ids.contains(held.userCouponNo())) continue;var c=held.coupon();
            var coupon=new CouponVO(c.couponNo(),c.title(),"CASH".equals(c.benefitMode())?"FULL_CUT":"DISCOUNT", "CASH".equals(c.benefitMode())?c.benefitValue():0,
                    "PERCENT".equals(c.benefitMode())?Math.toIntExact(c.benefitValue()):0,c.minAmountMinor(),c.capMinor(),c.funder(),c.entityNo(),c.startAt(),c.endAt(),c.remain(),true,c.status(),null);
            result.add(new UserCouponVO(held.userCouponNo(),coupon,held.status(),held.usableNow(),held.receivedAt(),held.usedAt()));
        }
        return wallet.checkoutWallet(user,result);
    }
    public BestResult best(List<Item> items) {return legacy.best(items);}
}
