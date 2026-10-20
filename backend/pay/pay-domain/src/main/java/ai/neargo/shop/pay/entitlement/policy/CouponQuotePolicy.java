package ai.neargo.shop.pay.entitlement.policy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.promotion.CouponCalculation;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.CouponAllocation;
import ai.neargo.shop.spi.entitlement.EntitlementUseSourcePort.UseSourceFact;
import java.util.ArrayList;
import java.util.List;

/** Uses only core-confirmed post-activity amounts. Goods discount and freight discount use separate bases. */
public final class CouponQuotePolicy {
    public static final String FREIGHT_LINE = "__FREIGHT__";

    public List<CouponAllocation> calculate(String instanceNo, EntitlementDefinition rule, UseSourceFact source) {
        if (!"ORDER_COUPON".equals(rule.kind())) throw BizException.of(ErrorCode.CONFLICT);
        var eligible = source.items().stream().filter(i -> switch (rule.redemptionScope().mode()) {
            case "ALL" -> true;
            case "STORES" -> rule.redemptionScope().refs().contains(source.storeNo());
            case "GOODS" -> rule.redemptionScope().refs().contains(i.itemNo());
            case "CATEGORIES" -> source.categoryRefsByLine().getOrDefault(i.orderLineNo(), List.of()).stream()
                    .anyMatch(rule.redemptionScope().refs()::contains);
            default -> false;
        }).toList();
        long base = 0;
        long quantity = 0;
        for (var item : eligible) {
            base = Math.addExact(base, item.amountMinor());
            quantity = Math.addExact(quantity, item.quantity());
        }
        if (eligible.isEmpty() || base < (rule.minAmountMinor() == null ? 0 : rule.minAmountMinor())
                || quantity < (rule.minQty() == null ? 0 : rule.minQty())) throw BizException.of(ErrorCode.CONFLICT);
        if ("FREE_SHIP".equals(rule.benefitMode())) {
            long discount = rule.benefitCapMinor() == null || rule.benefitCapMinor() <= 0
                    ? source.freightMinor() : Math.min(rule.benefitCapMinor(), source.freightMinor());
            return discount == 0 ? List.of() : List.of(new CouponAllocation(instanceNo, FREIGHT_LINE, discount));
        }
        long total = CouponCalculation.discount(rule.benefitMode(), rule.benefitValue(), rule.benefitCapMinor(), base);
        var parts = CouponCalculation.allocate(total, eligible.stream().map(i -> i.amountMinor()).toList());
        var result = new ArrayList<CouponAllocation>();
        for (int i = 0; i < eligible.size(); i++) {
            if (parts.get(i) > 0) {
                result.add(new CouponAllocation(instanceNo, eligible.get(i).orderLineNo(), parts.get(i)));
            }
        }
        return List.copyOf(result);
    }
}
