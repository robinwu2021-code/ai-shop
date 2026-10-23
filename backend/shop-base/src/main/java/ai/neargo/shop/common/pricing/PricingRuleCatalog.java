package ai.neargo.shop.common.pricing;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;
import ai.neargo.shop.common.pricing.RuleDefinition.BenefitKind;
import ai.neargo.shop.common.pricing.RuleDefinition.CountLimits;
import ai.neargo.shop.common.pricing.RuleDefinition.EvaluatorType;
import ai.neargo.shop.common.pricing.RuleDefinition.FixedPolicy;
import ai.neargo.shop.common.pricing.RuleDefinition.FixedPolicyParameters;
import ai.neargo.shop.common.pricing.RuleDefinition.ForbiddenCombination;
import ai.neargo.shop.common.pricing.RuleDefinition.LimitPath;
import ai.neargo.shop.common.pricing.RuleDefinition.Parameters;

/** Code-owned V1 policies. JSON validation and typed parameters derive from the same immutable specs. */
public final class PricingRuleCatalog {
    private static final Map<String, Spec> SPECS = Map.ofEntries(
            Map.entry("B01", new Spec(EvaluatorType.FORBID_COMBINATION, """
                        {"leftKinds":["ACTIVITY"],
                        "rightKinds":["PERCENT_COUPON",
                        "CASH_COUPON",
                        "DEDUCTION_COUPON"],
                        "basis":"ACTUALLY_APPLIED"}
                        """)),
            Map.entry("B02", new Spec(EvaluatorType.FORBID_COMBINATION, """
                        {"leftKinds":["PERCENT_COUPON"],
                        "rightKinds":["STORED_VALUE"]}
                        """)),
            Map.entry("B03", new Spec(EvaluatorType.REQUIRE_CAPABILITY, """
                        {"allSelectedOwnersMustAllow":true,"requireExactBasisAndOrder":true}
                        """)),
            Map.entry("B04", new Spec(EvaluatorType.COUNT_LIMIT, """
                        {"kind":"PERCENT_COUPON",
                        "limitPath":"couponLimits.percent",
                        "totalLimitPath":"couponLimits.total",
                        "countBy":["owner",
                        "assetNo"]}
                        """)),
            Map.entry("B05", new Spec(EvaluatorType.COUNT_LIMIT, """
                        {"kinds":["CASH_COUPON",
                        "DEDUCTION_COUPON"],
                        "limitPaths":["couponLimits.cash",
                        "couponLimits.deduction",
                        "couponLimits.amountTotal",
                        "couponLimits.total"]}
                        """)),
            Map.entry("B06", new Spec(EvaluatorType.SCOPE_OVERLAP, """
                        {"modePath":"discountCouponPolicy.overlapMode",
                        "identity":["sourceLineNo",
                        "componentRef",
                        "quantityRange"],
                        "rejectParentLeafDoubleCount":true}
                        """)),
            Map.entry("B07", new Spec(EvaluatorType.FORBID_COMBINATION, """
                        {"leftKinds":["MANUAL_PERCENT",
                        "MANUAL_AMOUNT"],
                        "rightKinds":["ACTIVITY",
                        "PERCENT_COUPON",
                        "CASH_COUPON",
                        "DEDUCTION_COUPON",
                        "STORED_VALUE",
                        "POINTS"]}
                        """)),
            Map.entry("B08", new Spec(EvaluatorType.FORBID_COMBINATION, """
                        {"leftKinds":["PERCENT_COUPON",
                        "CASH_COUPON",
                        "DEDUCTION_COUPON"],
                        "rightKinds":["MANUAL_WAIVER",
                        "CASH_ROUNDING"]}
                        """)),
            Map.entry("B09", new Spec(EvaluatorType.REQUIRE_CAPABILITY, """
                        {"combinations":[["ACTIVITY",
                        "STORED_VALUE"],
                        ["CASH_COUPON",
                        "STORED_VALUE"],
                        ["DEDUCTION_COUPON",
                        "STORED_VALUE"]],
                        "cannotOverride":["B02"]}
                        """)),
            Map.entry("B10", new Spec(EvaluatorType.ORDER_AND_REMAINDER, """
                        {"order":["STORED_VALUE_PLAN",
                        "POINTS",
                        "MANUAL_WAIVER"],
                        "capBy":"OWNER_ELIGIBLE_UNFUNDED_REMAINDER"}
                        """)),
            Map.entry("B11", new Spec(EvaluatorType.REQUIRE_APPROVAL, """
                        {"reasonCatalogPath":"manualOperations.reasonCategories",
                        "limitsPath":"manualOperations.limits",
                        "approvalMustMatch":["actor",
                        "store",
                        "sourceScope",
                        "amount",
                        "expiry"]}
                        """)),
            Map.entry("B12", new Spec(EvaluatorType.CONSERVATION, """
                        {"keepExcludedItemsCharged":true,
                        "excludePaidAndUnknownScopes":true,
                        "couponCountScope":"ROOT_CHECKOUT_SCOPE",
                        "currencyMustMatch":true}
                        """)),
            Map.entry("T01", new Spec(EvaluatorType.TAX_POLICY_VALIDITY, """
                        {"sourceFields":["supplierRegion",
                        "supplierTaxRegistration",
                        "placeOfSupply",
                        "taxPoint"],
                        "unknownPolicy":"BLOCK",
                        "rejectExpiredLegalPolicy":true}
                        """)),
            Map.entry("T02", new Spec(EvaluatorType.TAX_PRICE_MODE, """
                        {"supportedModes":["INCLUSIVE",
                        "EXCLUSIVE"],
                        "consumerDisplayMustFollowRegionalPolicy":true}
                        """)),
            Map.entry("T03", new Spec(EvaluatorType.TAX_CLASSIFICATION, """
                        {"classes":["STANDARD","ZERO_RATED","EXEMPT","OUT_OF_SCOPE"],
                        "unknownClass":"BLOCK"}
                        """)),
            Map.entry("T04", new Spec(EvaluatorType.TAX_ADJUSTMENT_CLASSIFICATION, """
                        {"treatments":["REDUCES_TAX_BASE",
                        "SETTLES_CONSIDERATION"],
                        "unknownTreatment":"BLOCK",
                        "requires":["owner",
                        "funder",
                        "evidenceRef"]}
                        """)),
            Map.entry("T05", new Spec(EvaluatorType.TAX_ROUNDING, """
                        {"roundingModePath":"taxProfile.roundingMode",
                        "roundingScopePath":"taxProfile.roundingScope",
                        "preserveInvoiceLineTaxOnAA":true}
                        """)),
            Map.entry("T06", new Spec(EvaluatorType.TAX_FINALIZATION, """
                        {"after":["POINTS",
                        "MANUAL_WAIVER",
                        "CASH_ROUNDING"],
                        "before":"CASH_TENDER",
                        "maxLocalFinalizationPasses":1,
                        "unrepresentableGrossAdjustment":"BLOCK"}
                        """)),
            Map.entry("T07", new Spec(EvaluatorType.TAX_PRESENTATION, """
                        {"showNetTaxGross":true,"taxInvoiceContextFromSupplier":true,
                         "kitchenTicketHasNoFiscalAuthority":true}
                        """)),
            Map.entry("T08", new Spec(EvaluatorType.TAX_HISTORY, """
                        {"freeze":["taxPolicyVersion",
                        "taxPoint",
                        "taxClass",
                        "rateBp",
                        "priceTaxMode",
                        "roundingAlgorithmVersion",
                        "lineTaxMinor"],
                        "creditNoteViaOriginalOwner":true}
                        """)));
    private static final List<RuleDefinition> RULES = new TreeMap<>(SPECS).entrySet().stream()
            .map(entry -> new RuleDefinition(entry.getKey(), 1, entry.getValue().type(),
                    parameters(entry.getKey(), entry.getValue()))).toList();

    private PricingRuleCatalog() {
    }

    /** Exposes immutable compiled definitions, never a mutable evaluator registry. */
    public static List<RuleDefinition> standardRules() {
        return RULES;
    }

    /** Validates seed input against code-owned mandatory policies before accepting any definition. */
    public static List<RuleDefinition> validateJson(String json) {
        var input = PricingJson.parse(json);
        if (!input.isArray() || input.size() != RULES.size()) {
            throw PricingJson.invalid("The complete mandatory rule catalog is required");
        }
        Set<String> seen = new HashSet<>();
        for (var row : input) {
            var fields = Set.of("ruleNo", "ruleVersion", "name", "evaluatorType", "mandatory", "parameters");
            PricingJson.fields(row, fields, fields);
            if (!row.path("ruleNo").isString() || !row.path("evaluatorType").isString()
                    || !row.path("ruleVersion").isIntegralNumber() || row.path("ruleVersion").asLong() != 1
                    || !row.path("mandatory").isBoolean() || !row.path("mandatory").booleanValue()
                    || !row.path("name").isString() || row.path("name").asString().isBlank()) {
                throw PricingJson.invalid("Invalid mandatory rule metadata");
            }
            String no = row.path("ruleNo").asString();
            Spec expected = SPECS.get(no);
            if (expected == null || !seen.add(no)
                    || !expected.type().name().equals(row.path("evaluatorType").asString())
                    || !PricingJson.canonical(PricingJson.parse(expected.parametersJson()))
                            .equals(PricingJson.canonical(row.get("parameters")))) {
                throw PricingJson.invalid("Mandatory rule version, evaluator, or parameters differ from the registry");
            }
        }
        return RULES;
    }

    static String semanticHash() {
        var contents = new TreeMap<String, Object>();
        SPECS.forEach((no, spec) -> contents.put(no,
                List.of(1, spec.type().name(), PricingJson.canonical(PricingJson.parse(spec.parametersJson())))));
        return PricingJson.hash(PricingJson.JSON.writeValueAsString(contents) + "PRICING_RULE_SPEC_V4");
    }

    private static Parameters parameters(String no, Spec spec) {
        var params = PricingJson.parse(spec.parametersJson());
        if (spec.type() == EvaluatorType.FORBID_COMBINATION) {
            return new ForbiddenCombination(kinds(params.get("leftKinds")), kinds(params.get("rightKinds")),
                    params.path("basis").asString("").equals("ACTUALLY_APPLIED"));
        }
        if (spec.type() == EvaluatorType.COUNT_LIMIT) {
            Set<BenefitKind> kinds = params.has("kind")
                    ? Set.of(BenefitKind.valueOf(params.get("kind").asString())) : kinds(params.get("kinds"));
            Set<LimitPath> paths = new HashSet<>();
            if (params.has("limitPaths")) {
                params.get("limitPaths").forEach(path -> paths.add(limit(path.asString())));
            } else {
                paths.add(limit(params.get("limitPath").asString()));
                paths.add(limit(params.get("totalLimitPath").asString()));
            }
            return new CountLimits(kinds, paths);
        }
        return new FixedPolicyParameters(FixedPolicy.valueOf(no));
    }

    private static Set<BenefitKind> kinds(JsonNode values) {
        return values.valueStream().map(value -> BenefitKind.valueOf(value.asString()))
                .collect(Collectors.toUnmodifiableSet());
    }

    private static LimitPath limit(String path) {
        return switch (path) {
            case "couponLimits.total" -> LimitPath.TOTAL;
            case "couponLimits.percent" -> LimitPath.PERCENT;
            case "couponLimits.cash" -> LimitPath.CASH;
            case "couponLimits.deduction" -> LimitPath.DEDUCTION;
            case "couponLimits.amountTotal" -> LimitPath.AMOUNT_TOTAL;
            default -> throw PricingJson.invalid("Unknown fixed limit path");
        };
    }

    private record Spec(EvaluatorType type, String parametersJson) {
    }
}
