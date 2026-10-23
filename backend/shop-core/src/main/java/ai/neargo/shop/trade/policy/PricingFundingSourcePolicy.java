package ai.neargo.shop.trade.policy;

import ai.neargo.shop.common.pricing.BenefitSelection;
import ai.neargo.shop.common.pricing.PricingBenefitBasis;
import ai.neargo.shop.trade.dto.PricingFundingBasis;
import ai.neargo.shop.common.pricing.PricingPortion;
import ai.neargo.shop.common.pricing.PricingRuleFacts.PricingRole;
import ai.neargo.shop.common.pricing.PricingRuleFacts.ScopeStatus;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Kind;
import ai.neargo.shop.spi.trade.PricingSourceRef;
import ai.neargo.shop.trade.assembler.PricingQuoteAssembler;
import ai.neargo.shop.trade.dto.PricingPreparation.Prepared;
import ai.neargo.shop.trade.dto.PricingQuoteCommands.Context;
import ai.neargo.shop.trade.dto.PricingQuoteCommands.Industry;
import ai.neargo.shop.trade.dto.PricingQuoteCommands.Intent;
import ai.neargo.shop.trade.dto.PricingQuoteCommands.Line;
import ai.neargo.shop.trade.dto.PricingQuoteCommands.LineKind;
import ai.neargo.shop.trade.dto.PricingQuoteCommands.Phase;
import ai.neargo.shop.trade.dto.PricingQuoteCommands.SaveReq;
import ai.neargo.shop.trade.entity.OrdPriceQuote;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import tools.jackson.databind.json.JsonMapper;

/** Conservative, unfunded baseline admission. Composite goods and benefit execution remain separate work. */
public final class PricingFundingSourcePolicy {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final PricingQuoteAssembler ASSEMBLER = new PricingQuoteAssembler();
    private PricingFundingSourcePolicy() { }

    public static PricingSourceRef source(OrdPriceQuote quote) {
        require("FOOD".equals(quote.getIndustryCode()) && "FOOD".equals(quote.getSourceSystem())
                && "CART".equals(quote.getSourceType()) && "LIVE".equals(quote.getQuotePhase()));
        var source = JSON.treeToValue(JSON.readTree(quote.getFactsSnapshot()).get("source"), PricingSourceRef.class);
        require(source.sourceSystem().equals(quote.getSourceSystem()) && source.sourceType().equals(quote.getSourceType())
                && source.sourceNo().equals(quote.getSourceNo()) && source.sourceVersion() == quote.getSourceVersion()
                && Objects.equals(source.scopeNo(), quote.getRootScopeNo())
                && Objects.equals(source.scopeVersion(), quote.getScopeVersion()));
        return source;
    }

    public static PricingFundingBasis verify(Context context, OrdPriceQuote quote, Prepared prepared) {
        require(prepared.source().scope().tenantNo().equals(context.tenantNo())
                && prepared.source().scope().entityNo().equals(context.entityNo())
                && prepared.source().scope().storeNo().equals(context.storeNo())
                && Objects.equals(prepared.source().subjectNo(), context.subjectNo())
                && source(quote).equals(prepared.source().source())
                && "H5".equals(prepared.source().channel()) && "H5".equals(quote.getChannel()));
        var rules = prepared.rules();
        require("FOOD".equals(rules.industry()) && rules.scope().equals(prepared.source().scope())
                && quote.getStoreRevisionNo().equals(rules.revisionNo())
                && quote.getTemplateRevisionNo().equals(rules.templateRevisionNo())
                && quote.getEngineSpecVersion().equals(rules.engineSpecVersion()));
        var factsNode = JSON.readTree(quote.getFactsSnapshot());
        require(ASSEMBLER.canonical(prepared.source().prices()).equals(ASSEMBLER.canonical(factsNode.get("facts"))));
        var result = JSON.readTree(quote.getResultSnapshot());
        require(ASSEMBLER.canonical(prepared.tax()).equals(ASSEMBLER.canonical(result.get("tax"))));
        var plan = JSON.readTree(quote.getPlanSnapshot());
        var intent = JSON.treeToValue(plan.get("intent"), Intent.class);
        require(intent.selection().mode() == BenefitSelection.Mode.EXPLICIT
                && intent.selection().coupons().isEmpty() && intent.selection().fixedBenefits().isEmpty()
                && intent.funding().storedPlannedMinor() == 0 && intent.funding().otherPlannedMinor() == 0
                && intent.funding().cashDueMinor() == 0 && intent.funding().cashTenderMinor() == null
                && plan.get("allocations").isEmpty());
        List<Line> lines = new ArrayList<>();
        for (var node : result.get("lines")) lines.add(JSON.treeToValue(node, Line.class));
        require(lines.size() == prepared.source().composition().size());
        for (var line : lines) {
            var component = prepared.source().composition().stream()
                    .filter(c -> c.unit().equals(line.source())).findFirst().orElseThrow();
            var policy = prepared.goodsPolicies().policies().stream()
                    .filter(p -> p.goodsNo().equals(line.source().goodsNo())
                            && p.skuNo().equals(line.source().skuNo())).findFirst().orElseThrow();
            require(component.kind() == Kind.MAIN && line.kind() == LineKind.GOODS
                    && line.parentSourceLineNo() == null && line.eligibility().role() == PricingRole.NONE
                    && line.eligibility().status() == ScopeStatus.OPEN
                    && line.eligibility().portion().equals(PricingPortion.from(line.source()))
                    && line.eligibility().promotionEligible() == policy.promotionEligible()
                    && line.taxClass() == policy.taxClass());
            line.source().quantity().intValueExact();
            var a = line.amounts();
            require(a.priceAdjustmentMinor() == 0 && a.activityDiscountMinor() == 0
                    && a.couponDiscountMinor() == 0 && a.manualDiscountMinor() == 0 && a.pointsDiscountMinor() == 0
                    && a.waiverMinor() == 0 && a.feeMinor() == 0 && a.considerationOffsetMinor() == 0);
        }
        var rebuilt = ASSEMBLER.prepare(context, new SaveReq(prepared.source().source(), Industry.FOOD,
                prepared.source().channel(), Phase.LIVE, rules.revisionNo(), rules.templateRevisionNo(),
                rules.engineSpecVersion(), prepared.source().prices(), prepared.tax(), intent, lines, List.of(), 60),
                rules.config().canonicalJson());
        require(quote.getFactsHash().equals(rebuilt.factsHash())
                && quote.getRequestHash().equals(rebuilt.requestHash())
                && quote.getEconomicsHash().equals(rebuilt.economicsHash())
                && quote.getReceivableMinor() == rebuilt.amounts().receivableMinor()
                && quote.getCurrency().equals(prepared.source().prices().currency()));
        return new PricingFundingBasis(1, PricingBenefitBasis.freeze(prepared.source().prices(),
                lines.stream().map(Line::eligibility).toList()), prepared.tax(), rebuilt.amounts().netMinor(),
                rebuilt.amounts().taxMinor(), rebuilt.amounts().receivableMinor(), quote.getEconomicsHash());
    }

    private static void require(boolean value) {
        if (!value) throw new IllegalArgumentException("Current unfunded food quote required");
    }
}
