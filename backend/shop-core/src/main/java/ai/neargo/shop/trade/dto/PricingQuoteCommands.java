package ai.neargo.shop.trade.dto;

import java.util.List;
import ai.neargo.shop.common.pricing.BenefitSelection;
import ai.neargo.shop.common.pricing.MerchantTaxSnapshot;
import ai.neargo.shop.common.pricing.PricingFacts;
import ai.neargo.shop.common.pricing.PricingRuleFacts.UnitPolicy;
import ai.neargo.shop.common.pricing.PricingPortion;
import ai.neargo.shop.common.pricing.PricingUnit;
import ai.neargo.shop.common.pricing.TaxClass;
import ai.neargo.shop.spi.trade.PricingSourceRef;

/** Backend-only owner-admitted contracts. None is an HTTP request, customer VO, or debit authorization. */
public final class PricingQuoteCommands {
    private PricingQuoteCommands() { }

    public record Context(String tenantNo, String entityNo, String storeNo, String subjectNo, String actorNo) { }
    public enum Industry { FOOD, RETAIL, BEAUTY_SERVICE }
    public enum Phase { LIVE, FROZEN_UNPAID }
    public enum LineKind { GOODS, ADDON, BUNDLE_COMPONENT, FEE }
    public enum Kind {
        ACTIVITY_DISCOUNT, COUPON_DISCOUNT, MANUAL_DISCOUNT, POINTS_DISCOUNT, WAIVER,
        POINTS_CONSIDERATION, STORED_PLAN, OTHER_PLAN, CASH_PLAN
    }
    public record Amounts(long goodsMinor, long priceAdjustmentMinor, long activityDiscountMinor,
            long couponDiscountMinor, long manualDiscountMinor, long pointsDiscountMinor, long waiverMinor,
            long feeMinor, long netMinor, long taxMinor, long receivableMinor, long considerationOffsetMinor) { }
    public record Funding(long storedPlannedMinor, long otherPlannedMinor, long cashDueMinor, Long cashTenderMinor) { }
    public record Intent(BenefitSelection selection, Funding funding) { }
    public record Line(PricingUnit source, String parentSourceLineNo, LineKind kind, TaxClass taxClass,
            Amounts amounts, long invoiceBaseMinor, UnitPolicy eligibility) { }
    public record Funder(String funderRef, long amountMinor) { }
    public record Allocation(PricingPortion portion, Kind kind, int stepOrder, String benefitRef, String owner,
            String ownerQuoteRef, String applicationRef, String ruleNo, String ruleVersion,
            long basisMinor, long amountMinor, Integer payableRateBp, String algorithmVersion,
            List<Funder> funders) {
        public Allocation { funders = funders == null ? null : List.copyOf(funders); }
    }
    public record SaveReq(PricingSourceRef source, Industry industry, String channel, Phase phase,
            String storeRevisionNo, String templateRevisionNo, String engineSpecVersion, PricingFacts facts,
            MerchantTaxSnapshot tax, Intent intent, List<Line> lines, List<Allocation> allocations,
            int lifetimeSeconds) {
        public SaveReq {
            lines = lines == null ? null : List.copyOf(lines);
            allocations = allocations == null ? null : List.copyOf(allocations);
        }
    }
    /** Full internal evidence, deliberately not returned by a consumer controller. */
    public record QuoteVO(String quoteNo, String status, String expiresAt, String factsHash, String requestHash,
            String economicsHash, String factsSnapshot, String configSnapshot, String planSnapshot,
            String resultSnapshot, String retainedRef) { }
    public record Receipt(String quoteNo) { }
}
