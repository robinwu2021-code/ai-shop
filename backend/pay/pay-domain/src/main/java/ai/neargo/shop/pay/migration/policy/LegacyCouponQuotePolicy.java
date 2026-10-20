package ai.neargo.shop.pay.migration.policy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.promotion.CouponCalculation;
import ai.neargo.shop.pay.entitlement.policy.EntitlementRuleValidator;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition;
import ai.neargo.shop.spi.entitlement.LegacyCouponCheckoutSourcePort.MerchantGroup;
import ai.neargo.shop.spi.entitlement.LegacyCouponUsePort.Allocation;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Original merchant/store arithmetic; quote facts still come from core and eligibility is checked by the owner service. */
public final class LegacyCouponQuotePolicy {
    public Result calculate(EntitlementDefinition rule,String entityNo,List<MerchantGroup> groups) {
        new EntitlementRuleValidator().validate(rule);
        if(!"ORDER_COUPON".equals(rule.kind()) || !"MERCHANT".equals(rule.funder()) || !"COUNT".equals(rule.usageModel())
                || !"ALL".equals(rule.redemptionScope().mode()) || rule.minQty()!=null
                || !Set.of("CASH","PERCENT").contains(rule.benefitMode())
                || ("PERCENT".equals(rule.benefitMode()) && (rule.benefitCapMinor()==null || rule.benefitCapMinor()<=0)))
            throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
        if(entityNo==null || entityNo.isBlank() || groups==null || groups.isEmpty()
                || groups.stream().map(MerchantGroup::groupKey).distinct().count()!=groups.size())
            throw BizException.of(ErrorCode.BAD_REQUEST);
        var eligible=groups.stream().filter(g->entityNo.equals(g.merchantNo())).toList();
        long base=0;
        try { for(var group:eligible) base=Math.addExact(base,group.goodsAmountMinor()); }
        catch(ArithmeticException overflow) { throw BizException.of(ErrorCode.BAD_REQUEST); }
        if(eligible.isEmpty() || base<(rule.minAmountMinor()==null?0:rule.minAmountMinor()))
            throw BizException.of(ErrorCode.COUPON_NOT_APPLICABLE);
        long discount=CouponCalculation.discount(rule.benefitMode(),rule.benefitValue(),rule.benefitCapMinor(),base);
        if(discount<=0) throw BizException.of(ErrorCode.COUPON_NOT_APPLICABLE);
        var amounts=CouponCalculation.allocate(discount,eligible.stream().map(MerchantGroup::goodsAmountMinor).toList());
        var parts=new ArrayList<Allocation>();
        for(int i=0;i<eligible.size();i++) {
            var group=eligible.get(i);
            if(amounts.get(i)>0) parts.add(new Allocation(group.groupKey(),group.merchantNo(),group.storeNo(),amounts.get(i)));
        }
        return new Result(discount,List.copyOf(parts));
    }
    public record Result(long discountMinor,List<Allocation> allocations) {
        public Result { allocations=List.copyOf(allocations); }
    }
}
