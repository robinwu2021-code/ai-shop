package ai.neargo.shop.paybridge.impl;

import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.marketing.coupon.dto.CouponVO;
import ai.neargo.shop.marketing.coupon.dto.UserCouponVO;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.migration.dto.LegacyCouponTemplateViews;
import ai.neargo.shop.paybridge.LegacyCouponWalletProjectionService;
import ai.neargo.shop.payclient.LegacyCouponTemplateAppService;
import ai.neargo.shop.promotion.dto.CouponVOs;
import ai.neargo.shop.promotion.dto.CouponVOs.MyCouponVO;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.OwnerSystem;
import ai.neargo.shop.spi.entitlement.EntitlementQueryPort.AssetState;
import ai.neargo.shop.spi.entitlement.LegacyCouponIssuedProjectionPort;
import ai.neargo.shop.spi.entitlement.LegacyCouponUsePort;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class LegacyCouponWalletProjectionServiceImpl implements LegacyCouponWalletProjectionService {
    @Autowired(required=false) private LegacyCouponIssuedProjectionPort issued;
    @Autowired(required=false) private MerchantQueryPort merchants;
    @Autowired(required=false) private LegacyCouponTemplateAppService templates;
    private final LegacyCouponRouteResolver routes;
    private final LegacyCouponUsePort pay;
    public LegacyCouponWalletProjectionServiceImpl(LegacyCouponRouteResolver routes,LegacyCouponUsePort pay) {this.routes=routes;this.pay=pay;}
    private EntitlementContext context() {return new EntitlementContext("MAIN",SecurityUtils.currentUser().map(u->u.userNo()).orElse("CORE_VIEW"),"LEGACY_WALLET","0".repeat(64));}
    private LegacyCouponUsePort.Snapshot snapshot(EntitlementContext ctx,String user,String no) {
        var route=routes.resolve(ctx,user,no);
        if(route.ownerSystem()==OwnerSystem.CORE_LEGACY) return null;
        if(route.entityNo()==null) throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
        return pay.snapshot(ctx,new LegacyCouponUsePort.Owned(user,route.entityNo(),route.source(),route.targetNo(),route.migrationBatchNo(),route.cutoverProofHash()));
    }
    @Override public List<MyCouponVO> wallet(String user,List<MyCouponVO> rows) {
        var ctx=context();var out=new LinkedHashMap<String,MyCouponVO>();
        for(var row:rows) {
            var s=snapshot(ctx,user,row.userCouponNo());
            if(s==null) out.putIfAbsent(row.userCouponNo(),row);
            else {
                long end=expiry(s);
                out.putIfAbsent(row.userCouponNo(),new MyCouponVO(row.userCouponNo(),row.couponNo(),s.definition().title(),row.benefitText(),
                        row.entityNo(),"ORDER",null,s.definition().minAmountMinor(),s.totalUnits(),s.totalUnits()-s.remainingUnits(),
                        s.remainingUnits(),end,status(s),s.usableNow(),row.merchantName(),s.definition().redemptionScope().mode()));
            }
        }
        return List.copyOf(out.values());
    }
    @Override public List<UserCouponVO> checkoutWallet(String user,List<UserCouponVO> rows) {
        var ctx=context();var out=new LinkedHashMap<String,UserCouponVO>();
        for(var row:rows) {
            var s=snapshot(ctx,user,row.userCouponNo());
            if(s==null) out.putIfAbsent(row.userCouponNo(),row);
            else {
                var old=row.coupon();var d=s.definition();boolean percent="PERCENT".equals(d.benefitMode());
                if(s.effectiveFrom()==null) throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
                var terms=new CouponVO(old.couponNo(),d.title(),percent?"DISCOUNT":"FULL_CUT",percent?0:d.benefitValue(),
                        percent?Math.toIntExact(d.benefitValue()):0,d.minAmountMinor()==null?0:d.minAmountMinor(),
                        d.benefitCapMinor()==null?0:d.benefitCapMinor(),d.funder(),old.merchantNo(),s.effectiveFrom().toEpochMilli(),expiry(s),
                        old.remain(),old.received(),old.status(),old.scopeDesc());
                Long used=s.remainingUnits()==s.totalUnits()?null:s.lastUsedAt()==null?row.usedAt():s.lastUsedAt().toEpochMilli();
                out.putIfAbsent(row.userCouponNo(),new UserCouponVO(row.userCouponNo(),terms,status(s),s.usableNow(),row.receivedAt(),used));
            }
        }
        return List.copyOf(out.values());
    }
    @Override public List<String> usable(String user,List<String> candidates) {
        var ctx=context();var out=new LinkedHashSet<String>();
        for(String no:candidates) {var s=snapshot(ctx,user,no);if(s==null || s.usableNow()) out.add(no);}
        return List.copyOf(out);
    }
    @Override public List<MyCouponVO> issuedWallet(String user) {
        if(issued==null) return List.of();var ctx=context();var result=new ArrayList<MyCouponVO>();
        for(var right:issued.owned(ctx,user)) {
            var s=snapshot(ctx,user,right.instanceNo());if(s==null) throw BizException.of(ErrorCode.CONFLICT);
            var d=s.definition();String benefit="PERCENT".equals(d.benefitMode())
                    ?java.math.BigDecimal.valueOf(d.benefitValue(),3).stripTrailingZeros().toPlainString()+" 折"
                    :"减 "+java.math.BigDecimal.valueOf(d.benefitValue(),2).stripTrailingZeros().toPlainString()+" 元";
            String name=merchants==null?null:merchants.find(right.entityNo()).map(m->m.merchantName()).orElse(null);
            result.add(new MyCouponVO(right.instanceNo(),right.sourceTemplateNo(),d.title(),benefit,right.entityNo(),"ORDER",null,d.minAmountMinor(),
                    s.totalUnits(),s.totalUnits()-s.remainingUnits(),s.remainingUnits(),expiry(s),status(s),s.usableNow(),name,d.redemptionScope().mode()));
        }
        return List.copyOf(result);
    }
    @Override public List<CouponVOs.HeldCoupon> issuedHeld(String user) {
        if(issued==null) return List.of();var ctx=context();var result=new ArrayList<CouponVOs.HeldCoupon>();
        for(var right:issued.owned(ctx,user)) {
            var s=snapshot(ctx,user,right.instanceNo());if(s==null || templates==null || s.effectiveFrom()==null) throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
            var d=s.definition();var template=templates.get(new CatalogScope("MAIN",right.entityNo(),ctx.actorNo(),false),
                    new LegacyCouponTemplateViews.Reference(new EntitlementOwnerRegistryPort.SourceIdentity(
                            EntitlementOwnerRegistryPort.SourceSystem.valueOf(right.sourceSystem()),EntitlementOwnerRegistryPort.SourceKind.TEMPLATE,right.sourceTemplateNo()),
                            right.targetTemplateNo(),right.migrationBatchNo(),right.planHash()));
            var policy=template.template().issuePolicy();long stock=policy.limitCount()==null?Integer.MAX_VALUE:Math.max(0,policy.limitCount()-policy.heldCount()-policy.issuedCount());
            var coupon=new CouponVOs.CustomerCoupon(right.sourceTemplateNo(),d.title(),d.benefitMode(),d.benefitValue(),
                    d.benefitCapMinor()==null?0:d.benefitCapMinor(),d.minAmountMinor()==null?0:d.minAmountMinor(),right.entityNo(),d.funder(),s.effectiveFrom().toEpochMilli(),expiry(s),
                    null,(int)Math.min(Integer.MAX_VALUE,stock),true,template.template().status());
            result.add(new CouponVOs.HeldCoupon(right.instanceNo(),coupon,status(s),s.usableNow(),right.grantedAt().toEpochMilli(),
                    s.lastUsedAt()==null?null:s.lastUsedAt().toEpochMilli(),expiry(s)));
        }
        return List.copyOf(result);
    }
    private static long expiry(LegacyCouponUsePort.Snapshot s) {
        if(s.effectiveUntil()==null) throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
        return s.effectiveUntil().minusMillis(1).toEpochMilli();
    }
    private static String status(LegacyCouponUsePort.Snapshot s) {
        if(s.state()==AssetState.EXPIRED) return "EXPIRED";
        return s.remainingUnits()==0 || s.state()==AssetState.REVOKED?"USED":"UNUSED";
    }
}
