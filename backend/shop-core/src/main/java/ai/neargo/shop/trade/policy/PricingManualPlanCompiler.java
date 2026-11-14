package ai.neargo.shop.trade.policy;

import ai.neargo.shop.common.pricing.BenefitSelection;
import ai.neargo.shop.common.pricing.EffectivePricingConfig.AmountBasis;
import ai.neargo.shop.common.pricing.MoneyMinor;
import ai.neargo.shop.common.pricing.PriceTaxMode;
import ai.neargo.shop.common.pricing.PricingBenefitBasis;
import ai.neargo.shop.common.pricing.PricingEngine;
import ai.neargo.shop.common.pricing.PricingFeeTaxEngine;
import ai.neargo.shop.common.pricing.PricingFormula;
import ai.neargo.shop.common.pricing.PricingFormula.Algorithm;
import ai.neargo.shop.common.pricing.PricingFormula.FundedAllocation;
import ai.neargo.shop.common.pricing.PricingFormula.FundingShare;
import ai.neargo.shop.common.pricing.PricingFormula.Step;
import ai.neargo.shop.common.pricing.PricingFormula.StepKey;
import ai.neargo.shop.common.pricing.PricingPortion;
import ai.neargo.shop.common.pricing.PricingResult;
import ai.neargo.shop.common.pricing.PricingRuleCandidate.ApprovalEvidence;
import ai.neargo.shop.common.pricing.PricingRuleCandidate.AssetRef;
import ai.neargo.shop.common.pricing.PricingRuleCandidate.OwnerEvidence;
import ai.neargo.shop.common.pricing.PricingRuleFacts;
import ai.neargo.shop.common.pricing.PricingRuleFacts.AccessContext;
import ai.neargo.shop.common.pricing.PricingRuleFacts.PricingRole;
import ai.neargo.shop.common.pricing.PricingRuleFacts.ScopeStatus;
import ai.neargo.shop.common.pricing.PricingRuleFacts.UnitPolicy;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.common.pricing.PricingUnit;
import ai.neargo.shop.common.pricing.RuleDefinition.BenefitKind;
import ai.neargo.shop.common.pricing.TaxEngine;
import ai.neargo.shop.common.promotion.CouponCalculation;
import ai.neargo.shop.spi.product.GoodsPricingPolicyPort.Policy;
import ai.neargo.shop.spi.product.GoodsPricingPolicyPort.SkuRef;
import ai.neargo.shop.spi.trade.PricingManualAdjustmentPort.ManualKind;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Kind;
import ai.neargo.shop.trade.dto.PricingPreparation.Prepared;
import ai.neargo.shop.trade.dto.PricingQuoteCommands;
import ai.neargo.shop.trade.dto.PricingQuoteCommands.Allocation;
import ai.neargo.shop.trade.dto.PricingQuoteCommands.Amounts;
import ai.neargo.shop.trade.dto.PricingQuoteCommands.Funder;
import ai.neargo.shop.trade.dto.PricingQuoteCommands.Funding;
import ai.neargo.shop.trade.dto.PricingQuoteCommands.Industry;
import ai.neargo.shop.trade.dto.PricingQuoteCommands.Intent;
import ai.neargo.shop.trade.dto.PricingQuoteCommands.Line;
import ai.neargo.shop.trade.dto.PricingQuoteCommands.LineKind;
import ai.neargo.shop.trade.dto.PricingQuoteCommands.Phase;
import ai.neargo.shop.trade.dto.PricingQuoteCommands.SaveReq;
import ai.neargo.shop.trade.entity.OrdManualPriceAdjustment;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

/**
 * Adapts genuine seller concessions to existing arithmetic kernels; it never invents approval
 * provenance.
 */
public final class PricingManualPlanCompiler {
    public static final String OWNER = "SHOP_MANUAL";

    public record Assessment(long discountMinor, PricingRuleFacts facts, Step step) {}

    /**
     * Draft arithmetic is not an execution grant; no approval evidence or executable quote is
     * returned.
     */
    public Assessment assess(
            Prepared prepared,
            String actor,
            ManualKind kind,
            Long requestedMinor,
            Integer rateBp,
            String reason,
            String adjustmentNo,
            Instant evaluatedAt) {
        require(prepared != null && kind != null && kind != ManualKind.ROUNDING);
        var source = prepared.source();
        var config = prepared.rules().config();
        var definition = config.definition();
        var manual = definition.manualOperations();
        require(
                "FOOD".equals(source.source().sourceSystem())
                        && "CART".equals(source.source().sourceType())
                        && "FOOD".equals(prepared.rules().industry())
                        && source.prices().units().size() <= 64
                        && !source.prices().units().isEmpty()
                        && source.composition().stream()
                                .allMatch(c -> c.kind() == Kind.MAIN || c.kind() == Kind.ADDON)
                        && manual.enabled()
                        && manual.reasonCategories().contains(reason)
                        && "EXISTING_PERMISSION_AND_APPROVAL_AUTHORITY"
                                .equals(manual.limits().source())
                        && "DENY".equals(manual.limits().missingPolicy())
                        && ((prepared.tax().priceTaxMode() == PriceTaxMode.INCLUSIVE
                                        && manual.amountBasis() == AmountBasis.PAYABLE_GROSS)
                                || (prepared.tax().priceTaxMode() == PriceTaxMode.EXCLUSIVE
                                        && manual.amountBasis() == AmountBasis.TAX_EXCLUSIVE)));
        var policies = new HashMap<SkuRef, Policy>();
        prepared.goodsPolicies()
                .policies()
                .forEach(p -> policies.put(new SkuRef(p.goodsNo(), p.skuNo()), p));
        var eligibility =
                source.prices().units().stream()
                        .map(
                                unit -> {
                                    var policy =
                                            policies.get(new SkuRef(unit.goodsNo(), unit.skuNo()));
                                    require(policy != null);
                                    return new UnitPolicy(
                                            PricingPortion.from(unit),
                                            policy.promotionEligible(),
                                            ScopeStatus.OPEN,
                                            null,
                                            PricingRole.NONE);
                                })
                        .toList();
        var facts =
                new PricingRuleFacts(
                        source.prices(),
                        new AccessContext(
                                actor,
                                source.scope().storeNo(),
                                source.source().scopeNo() == null
                                        ? source.source().sourceNo()
                                        : source.source().scopeNo(),
                                manual.amountBasis().name(),
                                evaluatedAt),
                        eligibility,
                        List.of(),
                        List.of());
        var bases =
                PricingBenefitBasis.eligiblePortions(
                        PricingBenefitBasis.freeze(source.prices(), eligibility));
        require(!bases.isEmpty());
        long base = bases.stream().mapToLong(b -> b.amount().minor()).reduce(0, Math::addExact);
        final PricingFormula.Operation operation;
        final long amount;
        if (kind == ManualKind.PERCENT) {
            require(requestedMinor == null && rateBp != null && rateBp >= 0 && rateBp < 10000);
            operation = new PricingFormula.Percent(rateBp, null, 0);
            amount = CouponCalculation.discount("PERCENT", rateBp.longValue(), null, base);
        } else {
            require(
                    rateBp == null
                            && requestedMinor != null
                            && requestedMinor > 0
                            && requestedMinor <= base);
            operation = new PricingFormula.Amount(requestedMinor, 0);
            amount = CouponCalculation.discount("CASH", requestedMinor, null, base);
        }
        require(amount > 0);
        var asset = new AssetRef(OWNER, adjustmentNo);
        var benefitKind = benefitKind(kind);
        var owner =
                new OwnerEvidence(
                        asset,
                        Set.of(benefitKind),
                        definition.formulaId(),
                        definition.formulaVersion(),
                        manual.amountBasis().name(),
                        new MoneyMinor(source.prices().currency(), base));
        var shares =
                CouponCalculation.allocate(
                        amount, bases.stream().map(b -> b.amount().minor()).toList());
        var allocations = new ArrayList<FundedAllocation>();
        // This owner is the verified selling entity bearing its own price concession, not an
        // external funder.
        String funder = "MERCHANT:" + source.scope().entityNo();
        for (int index = 0; index < bases.size(); index++) {
            var money = new MoneyMinor(source.prices().currency(), shares.get(index));
            allocations.add(
                    new FundedAllocation(
                            bases.get(index).portion(),
                            money,
                            List.of(new FundingShare(funder, money))));
        }
        var basis =
                PricingFormula.basisHash(
                        facts,
                        config,
                        List.of(new StepKey(asset, benefitKind)),
                        0,
                        operation,
                        Algorithm.SHARED_COUPON_V1,
                        bases);
        var quote =
                new PricingFormula.Quote(
                        owner, adjustmentNo, basis, Algorithm.SHARED_COUPON_V1, bases, allocations);
        return new Assessment(amount, facts, new Step(benefitKind, operation, quote));
    }

    /**
     * The calling authority must first verify the persisted row, live policy, identities and source
     * versions.
     */
    public SaveReq compile(Prepared prepared, OrdManualPriceAdjustment row, Instant now) {
        require(
                "APPROVED".equals(row.getStatus())
                        && row.getApprovedBy() != null
                        && row.getExpiresAt() != null
                        && now.isBefore(row.getExpiresAt().toInstant(ZoneOffset.UTC))
                        && now.isBefore(prepared.expiresAt()));
        var assessment =
                assess(
                        prepared,
                        row.getActorNo(),
                        ManualKind.valueOf(row.getManualKind()),
                        row.getRequestedMinor(),
                        row.getPayableRateBp(),
                        row.getReasonCode(),
                        row.getAdjustmentNo(),
                        row.getEvaluatedAt().toInstant(ZoneOffset.UTC));
        require(assessment.discountMinor() == row.getDiscountMinor());
        var step = assessment.step();
        var source = prepared.source();
        String currency = source.prices().currency();
        var evidence =
                new ApprovalEvidence(
                        step.asset(),
                        step.kind(),
                        row.getActorNo(),
                        source.scope().storeNo(),
                        assessment.facts().context().scopeNo(),
                        new MoneyMinor(currency, assessment.discountMinor()),
                        row.getReasonCode(),
                        row.getExpiresAt().toInstant(ZoneOffset.UTC),
                        row.getAdjustmentNo());
        var calculated =
                new PricingEngine()
                        .calculate(
                                new PricingFormula.Input(
                                        assessment.facts(),
                                        prepared.rules().config(),
                                        List.of(step),
                                        List.of(evidence)));
        require(calculated.status() == PricingResult.Status.CALCULATED);
        var policies = new HashMap<SkuRef, Policy>();
        prepared.goodsPolicies()
                .policies()
                .forEach(p -> policies.put(new SkuRef(p.goodsNo(), p.skuNo()), p));
        var invoiceBases = new HashMap<String, Long>();
        var adjustedUnits = new ArrayList<PricingUnit>();
        for (var line : calculated.lines()) {
            var original = line.source();
            var adjusted =
                    new PricingUnit(
                            original.sourceLineNo(),
                            original.componentRef(),
                            original.goodsNo(),
                            original.skuNo(),
                            original.priceRevision(),
                            original.taxInvoiceLineRef(),
                            original.quantityStart(),
                            original.quantityEnd(),
                            original.unitPrice(),
                            line.remaining());
            adjustedUnits.add(adjusted);
            invoiceBases.merge(
                    original.taxInvoiceLineRef(), line.remaining().minor(), Math::addExact);
        }
        var taxInput =
                adjustedUnits.stream()
                        .map(
                                unit ->
                                        new TaxEngine.Line(
                                                unit,
                                                policies.get(
                                                                new SkuRef(
                                                                        unit.goodsNo(),
                                                                        unit.skuNo()))
                                                        .taxClass(),
                                                prepared.tax().priceTaxMode(),
                                                new MoneyMinor(
                                                        currency,
                                                        invoiceBases.get(
                                                                unit.taxInvoiceLineRef()))))
                        .toList();
        var feeTax =
                new PricingFeeTaxEngine()
                        .calculate(
                                taxInput,
                                prepared.feePlan(),
                                prepared.tax(),
                                source.prices().sourceSnapshotHash(),
                                source.source().sourceVersion());
        var taxByPortion = feeTax.taxes();
        var composition = new HashMap<PricingPortion, Kind>();
        source.composition()
                .forEach(part -> composition.put(PricingPortion.from(part.unit()), part.kind()));
        var lines = new ArrayList<Line>();
        var allocations = new ArrayList<Allocation>();
        var shares = new HashMap<PricingPortion, Long>();
        step.quote().allocations().forEach(a -> shares.put(a.portion(), a.amount().minor()));
        for (int index = 0; index < calculated.lines().size(); index++) {
            var calculatedLine = calculated.lines().get(index);
            var unit = calculatedLine.source();
            var portion = PricingPortion.from(unit);
            long discount = shares.getOrDefault(portion, 0L);
            long tax = taxByPortion.get(portion);
            long fee = feeTax.fees().getOrDefault(portion, 0L);
            long remaining = Math.addExact(calculatedLine.remaining().minor(), fee);
            long net =
                    prepared.tax().priceTaxMode() == PriceTaxMode.INCLUSIVE
                            ? remaining - tax
                            : remaining;
            var amount =
                    new Amounts(
                            unit.baseAmount().minor(),
                            0,
                            0,
                            0,
                            step.kind() == BenefitKind.MANUAL_WAIVER ? 0 : discount,
                            0,
                            step.kind() == BenefitKind.MANUAL_WAIVER ? discount : 0,
                            fee,
                            net,
                            tax,
                            Math.addExact(net, tax),
                            0);
            boolean addon = composition.get(portion) == Kind.ADDON;
            lines.add(
                    new Line(
                            unit,
                            addon ? unit.sourceLineNo() : null,
                            addon ? LineKind.ADDON : LineKind.GOODS,
                            policies.get(new SkuRef(unit.goodsNo(), unit.skuNo())).taxClass(),
                            amount,
                            invoiceBases.get(unit.taxInvoiceLineRef()),
                            assessment.facts().unitPolicies().get(index)));
            if (shares.containsKey(portion))
                allocations.add(
                        new Allocation(
                                portion,
                                step.kind() == BenefitKind.MANUAL_WAIVER
                                        ? PricingQuoteCommands.Kind.WAIVER
                                        : PricingQuoteCommands.Kind.MANUAL_DISCOUNT,
                                0,
                                row.getAdjustmentNo(),
                                OWNER,
                                row.getAdjustmentNo(),
                                row.getAdjustmentNo(),
                                "B11",
                                prepared.rules().ruleCatalogVersion(),
                                unit.baseAmount().minor(),
                                discount,
                                row.getPayableRateBp(),
                                Algorithm.SHARED_COUPON_V1.name(),
                                List.of(
                                        new Funder(
                                                "MERCHANT:" + source.scope().entityNo(),
                                                discount))));
        }
        int lifetime =
                (int)
                        Math.min(
                                120,
                                Math.min(
                                        Duration.between(now, prepared.expiresAt()).getSeconds(),
                                        Duration.between(
                                                        now,
                                                        row.getExpiresAt()
                                                                .toInstant(ZoneOffset.UTC))
                                                .getSeconds()));
        require(lifetime >= 1);
        return new SaveReq(
                source.source(),
                Industry.FOOD,
                source.channel(),
                Phase.LIVE,
                prepared.rules().revisionNo(),
                prepared.rules().templateRevisionNo(),
                prepared.rules().engineSpecVersion(),
                source.prices(),
                prepared.tax(),
                new Intent(
                        new BenefitSelection(
                                BenefitSelection.Mode.EXPLICIT,
                                List.of(),
                                calculated.candidate().benefits()),
                        new Funding(0, 0, 0, null)),
                lines,
                allocations,
                lifetime,
                orchestrationHash(prepared, row),
                prepared.feePlan());
    }

    public static String orchestrationHash(Prepared prepared, OrdManualPriceAdjustment row) {
        return PricingSnapshotHash.of(
                List.of(
                        "MANUAL_PRICE_AUTHORITY_V1",
                        prepared.preparationHash(),
                        row.getAdjustmentNo(),
                        row.getPolicyNo() == null ? "" : row.getPolicyNo(),
                        row.getPolicyVersion() == null ? -1L : row.getPolicyVersion(),
                        row.getActorNo(),
                        row.getApprovedBy(),
                        row.getApprovalMode() == null ? "" : row.getApprovalMode(),
                        row.getReasonCode(),
                        row.getDiscountMinor()));
    }

    private static BenefitKind benefitKind(ManualKind kind) {
        return switch (kind) {
            case PERCENT -> BenefitKind.MANUAL_PERCENT;
            case AMOUNT -> BenefitKind.MANUAL_AMOUNT;
            case WAIVER -> BenefitKind.MANUAL_WAIVER;
            case ROUNDING ->
                    throw new IllegalArgumentException(
                            "Cash rounding has no approved configuration");
        };
    }

    private static void require(boolean condition) {
        if (!condition)
            throw new IllegalArgumentException(
                    "Manual concession requires complete current owner evidence");
    }
}
