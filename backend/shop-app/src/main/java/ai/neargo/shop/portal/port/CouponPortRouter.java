package ai.neargo.shop.portal.port;

import ai.neargo.shop.marketing.port.CouponPortImpl;
import ai.neargo.shop.promotion.service.CouponAllocService;
import ai.neargo.shop.spi.marketing.CouponPort;
import org.springframework.context.annotation.Primary;
import org.springframework.beans.factory.annotation.Autowired;
import ai.neargo.shop.trade.service.LegacyCouponCheckoutJournal;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.paybridge.LegacyCouponWalletProjectionService;
import ai.neargo.shop.paybridge.impl.LegacyCouponRouteResolver;
import ai.neargo.shop.spi.entitlement.LegacyCouponUsePort;
import ai.neargo.shop.spi.entitlement.LegacyCouponCheckoutSourcePort.MerchantGroup;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.OwnerSystem;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 券在下单时走老模型还是新模型 —— <b>按这张券在哪张表里分流，不按开关</b>（P4）。
 *
 * <p>两套表要并存到 P9。期间用户手上同时有两种券：存量的在 {@code mkt_user_coupon}，
 * 商家新发的在 {@code pmt_user_coupon}。
 *
 * <p><b>为什么不用一个全局开关</b>：开关切错的那一刻，用户手上**一整类券会同时失效**，
 * 而失效的表现是「这张券用不了」，与门槛不够、过期长得一模一样 ——
 * 客服问不出、用户也说不清。按数据分流没有这个状态：券在哪张表，就走哪条路。
 *
 * <p><b>为什么放在 app 层而不是某个域里</b>：它要同时认识两个域的实现，
 * 而域之间不许互相依赖（ArchitectureTest 守着）。app 层本来就是接线的地方。
 * P9 老表退场时，这个类连同 {@code marketing.port.CouponPortImpl} 一起删掉，
 * 新实现直接实现 {@link CouponPort} 即可 —— 到那时它是纯粹的多余。
 */
@Primary
@Component
public class CouponPortRouter implements CouponPort {

    @Autowired private LegacyCouponCheckoutJournal checkoutJournal;
    @Autowired private ai.neargo.shop.trade.service.LegacyCouponHistoricalReturnService historicalReturns;
    @Autowired private LegacyCouponRouteResolver ownerRoutes;
    @Autowired private LegacyCouponUsePort legacyPay;
    @Autowired private LegacyCouponWalletProjectionService wallet;
    private final CouponPortImpl legacy;
    private final CouponAllocService promo;

    public CouponPortRouter(CouponPortImpl legacy, CouponAllocService promo) {
        this.legacy = legacy;
        this.promo = promo;
    }

    @Override
    public Allocation allocate(String userNo, String userCouponNo, List<MerchantAmount> groups) {
        if(userCouponNo==null || userCouponNo.isBlank()) return Allocation.none();
        if(ownerRoutes!=null) {
            var ctx=new EntitlementContext("MAIN",SecurityUtils.currentUser().map(u->u.userNo()).orElse("CORE_QUOTE"),"LEGACY_QUOTE","0".repeat(64));
            LegacyCouponRouteResolver.Route route;
            try {route=ownerRoutes.resolve(ctx,userNo,userCouponNo);}
            catch(BizException failure) {
                if(failure.errorCode()==ErrorCode.NOT_FOUND) throw BizException.of(ErrorCode.COUPON_NOT_APPLICABLE);
                throw failure;
            }
            if(route.ownerSystem()==OwnerSystem.PAY) {
                if(route.entityNo()==null) throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
                var amounts=groups.stream().map(g->{
                    String store=g.storeNo()==null || g.storeNo().isBlank()?null:g.storeNo();
                    return new MerchantGroup(store==null?g.merchantNo():store,g.merchantNo(),store,g.goodsAmount());
                }).toList();
                var estimate=legacyPay.estimate(ctx,new LegacyCouponUsePort.EstimateCommand(userNo,route.entityNo(),route.source(),
                        route.targetNo(),route.migrationBatchNo(),route.cutoverProofHash(),"CNY",amounts));
                return new Allocation(estimate.discountMinor(),true,estimate.allocations().stream()
                        .map(a->new MerchantDiscount(a.merchantNo(),a.discountMinor(),a.storeNo())).toList(),estimate.title());
            }
        }
        return promo.owns(userNo, userCouponNo)
                ? promo.allocate(userNo, userCouponNo, groups)
                : legacy.allocate(userNo, userCouponNo, groups);
    }

    @Override
    public void markUsed(String userNo, String userCouponNo, String orderNo,
                         Allocation allocation) {
        if (promo.owns(userNo, userCouponNo)) {
            promo.markUsed(userNo, userCouponNo, orderNo, allocation);
        } else {
            legacy.markUsed(userNo, userCouponNo, orderNo, allocation);
        }
    }

    @Override
    public void release(String orderNo) {
        if(checkoutJournal!=null && checkoutJournal.returnCancelledOrder(
                new EntitlementContext("MAIN",SecurityUtils.currentUser().map(u->u.userNo()).orElse("CORE_LIFECYCLE"),orderNo,"0".repeat(64)),orderNo)) return;
        if(historicalReturns!=null && historicalReturns.cancelled(
                new EntitlementContext("MAIN",SecurityUtils.currentUser().map(u->u.userNo()).orElse("CORE_LIFECYCLE"),orderNo,"0".repeat(64)),orderNo)) return;
        /*
         * 退券**两边都要跑**，不能像上面那样二选一：这里只有订单号，
         * 拿不到用户券号，无从判断这一单当时用的是哪一套。
         * 两边各自按 order_no 查，没有就是空转 —— 漏退一张券，
         * 用户会认为券被平台吞了，那是券功能第二大客诉。
         */
        legacy.release(orderNo);
        promo.release(orderNo);
    }

    /**
     * 去向（P3）。**路由必须转发** —— 接口上是默认实现，不转发就永远落到默认的 null，
     * 而那恰好是「没有券要说」，页面安安静静地什么都不显示。2026-09-21 第一版就漏在这里。
     */
    @Override
    public String returnedTitleOf(String orderNo) {
        String migrated=checkoutJournal==null?null:checkoutJournal.originalTitle("MAIN",orderNo,true);
        if(migrated!=null) return migrated;
        String historical=historicalReturns==null?null:historicalReturns.returnedTitle("MAIN",orderNo);
        if(historical!=null) return historical;
        String t = legacy.returnedTitleOf(orderNo);
        return t != null ? t : promo.returnedTitleOf(orderNo);
    }

    /** 同上：不转发就永远是 null（P2c · B 端售后单「会退回哪张券」） */
    @Override
    public String usedTitleOf(String orderNo) {
        String migrated=checkoutJournal==null?null:checkoutJournal.originalTitle("MAIN",orderNo,false);
        if(migrated!=null) return migrated;
        String t = legacy.usedTitleOf(orderNo);
        return t != null ? t : promo.usedTitleOf(orderNo);
    }

    /** 两套各自的可用券合在一起（批 2 枚举最省组合用） */
    @Override
    public List<String> heldUsable(String userNo) {
        List<String> all = new java.util.ArrayList<>(legacy.heldUsable(userNo));
        all.addAll(promo.heldUsable(userNo));
        return wallet==null?all:wallet.usable(userNo,all);
    }
}
