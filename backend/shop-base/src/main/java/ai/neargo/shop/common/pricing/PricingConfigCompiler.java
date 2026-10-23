package ai.neargo.shop.common.pricing;

import java.util.HashSet;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import ai.neargo.shop.common.pricing.RuleDefinition.BenefitKind;
import ai.neargo.shop.common.pricing.EffectivePricingConfig.CouponMode;
import ai.neargo.shop.common.pricing.EffectivePricingConfig.Definition;
import ai.neargo.shop.common.pricing.EffectivePricingConfig.OverlapMode;
import ai.neargo.shop.common.pricing.EffectivePricingConfig.IndustryRestriction;

/**
 * Compiles a trusted industry definition and a restricted store override into immutable values.
 * No database, evaluator execution, balance lookup, authorization grant, or mutable cache lives here.
 */
public final class PricingConfigCompiler {
    private static final Set<String> ROOT = Set.of("schemaVersion", "benefitCombinationMode", "couponMode",
            "couponLimits", "discountCouponPolicy", "allowedCouponCombinations", "activityAndCoupons",
            "storedValueEnabled", "pointsEnabled", "pointsAutoApply", "pointsStage", "optionalCapabilityPolicy",
            "formulaId", "formulaVersion", "manualOperations", "taxBinding", "ruleRefs", "industryRestrictions");
    private static final Map<String, Set<String>> OBJECTS = Map.of(
            "", ROOT,
            "couponLimits", Set.of("total", "percent", "amountTotal", "cash", "deduction"),
            "discountCouponPolicy", Set.of("maxUsedPerOrder", "overlapMode", "maxUsedPerItem", "stackingEnabled"),
            "manualOperations", Set.of("enabled", "reasonCategories", "limits", "amountBasis",
                    "manualAmountConflictPolicy", "cashRoundingEnabled", "minPayableRateBp"),
            "manualOperations.limits", Set.of("source", "missingPolicy"),
            "taxBinding", Set.of("taxProfileNo", "resolveFrom", "missingPolicy", "storeMayEditRate"));
    private static final Set<String> EDITABLE = Set.of("benefitCombinationMode", "couponMode",
            "couponLimits.total", "couponLimits.percent", "couponLimits.amountTotal", "couponLimits.cash",
            "couponLimits.deduction", "discountCouponPolicy.maxUsedPerOrder", "discountCouponPolicy.overlapMode",
            "discountCouponPolicy.maxUsedPerItem", "discountCouponPolicy.stackingEnabled",
            "allowedCouponCombinations", "storedValueEnabled", "pointsEnabled", "manualOperations.enabled",
            "manualOperations.reasonCategories", "manualOperations.amountBasis", "manualOperations.minPayableRateBp",
            "taxBinding.taxProfileNo");
    private static final Set<String> BOOLEANS = Set.of("storedValueEnabled", "pointsEnabled", "pointsAutoApply",
            "discountCouponPolicy.stackingEnabled", "manualOperations.enabled", "manualOperations.cashRoundingEnabled",
            "taxBinding.storeMayEditRate");
    private static final Set<String> INTEGERS = Set.of("schemaVersion", "formulaVersion", "couponLimits.total",
            "couponLimits.percent", "couponLimits.amountTotal", "couponLimits.cash", "couponLimits.deduction",
            "discountCouponPolicy.maxUsedPerOrder", "discountCouponPolicy.maxUsedPerItem",
            "manualOperations.minPayableRateBp");
    private static final Set<String> ARRAYS = Set.of("allowedCouponCombinations", "manualOperations.reasonCategories",
            "ruleRefs", "industryRestrictions");
    private static final Set<String> FORMULAS = Set.of("DEDUCT_THEN_DISCOUNT_V1", "DEDUCT_THEN_STORED_VALUE_V1",
            "LEGACY_OWNER_COMPAT");
    private static final Map<String, Set<String>> ENUM_NAMES = Map.of(
            "benefitCombinationMode", Set.of("MULTI_KIND", "SINGLE_KIND"),
            "couponMode", Set.of("SINGLE", "MULTI"),
            "discountCouponPolicy.overlapMode", Set.of("DISJOINT", "BEST_ONE", "STACK"),
            "manualOperations.amountBasis", Set.of("PAYABLE_GROSS", "TAX_EXCLUSIVE"),
            "industryRestrictions", Arrays.stream(IndustryRestriction.values()).map(Enum::name)
                    .collect(Collectors.toUnmodifiableSet()));

    /** Four-argument entry point for already stored overrides without reset operations. */
    public CompilationResult compile(String template, String override,
            SystemConstraints constraints, PricingCapabilities capabilities) {
        return compile(template, override, Set.of(), constraints, capabilities);
    }

    /** Resets remove explicit override leaves before inheritance; null never means deletion. */
    public CompilationResult compile(String template, String override, Set<String> resetPaths,
            SystemConstraints constraints, PricingCapabilities capabilities) {
        Objects.requireNonNull(constraints, "Trusted system constraints are required");
        Objects.requireNonNull(capabilities, "Trusted capability evidence is required");
        Objects.requireNonNull(resetPaths, "Explicit reset paths are required");
        try {
            JsonNode defaults = PricingJson.parse(template);
            validateShape(defaults, "");
            JsonNode patch = PricingJson.parse(override);
            validatePatch(patch, "");
            check(resetPaths.size() <= 64, "resetPaths", "INPUT_LIMIT");
            for (String path : resetPaths) {
                check(EDITABLE.contains(path), "resetPaths", "PROTECTED_OVERRIDE");
                remove((ObjectNode) patch, path);
            }
            JsonNode merged = merge(defaults, patch);
            validateShape(merged, "");
            Definition definition = PricingJson.JSON.treeToValue(merged, Definition.class);
            validateDefinition(definition, constraints, capabilities);
            String canonical = PricingJson.canonical(merged);
            String hash = PricingJson.hash("PRICING_CONFIG_V4\n" + constraints.semanticHash() + "\n" + canonical);
            return new CompilationResult(new EffectivePricingConfig(definition, constraints.rules(), canonical, hash),
                    List.of());
        } catch (InvalidConfig invalid) {
            return failure(invalid.code, invalid.path, invalid.getMessage());
        } catch (JacksonException invalid) {
            return failure("INVALID_JSON_OR_CONFIG", "$", "JSON structure or typed configuration is invalid");
        } catch (IllegalArgumentException invalid) {
            return failure("INVALID_CONFIG", "$", "Configuration contains unsupported or incomplete values");
        }
    }

    private static void validateShape(JsonNode node, String path) {
        if (OBJECTS.containsKey(path)) {
            Set<String> required = new HashSet<>(OBJECTS.get(path));
            if (path.equals("manualOperations")) {
                required.remove("minPayableRateBp");
            }
            PricingJson.fields(node, OBJECTS.get(path), required);
            node.properties().forEach(entry -> validateShape(entry.getValue(), join(path, entry.getKey())));
        } else if (path.equals("taxBinding.taxProfileNo") && node.isNull()) {
            // An unresolved template binding is legal at compilation, but cannot authorize a taxable quote.
        } else if (BOOLEANS.contains(path)) {
            check(node.isBoolean(), path, "INVALID_TYPE");
        } else if (INTEGERS.contains(path)) {
            check(node.isIntegralNumber() && node.canConvertToInt(), path, "INVALID_TYPE");
        } else if (ARRAYS.contains(path)) {
            check(node.isArray() && node.size() <= 64, path, "INVALID_ARRAY");
            Set<String> seen = new HashSet<>();
            for (JsonNode element : node) {
                if (path.equals("allowedCouponCombinations")) {
                    check(element.isArray() && element.size() >= 2 && element.size() <= 3, path, "INVALID_COMBINATION");
                    Set<String> kinds = new HashSet<>();
                    for (JsonNode kind : element) {
                        requireText(kind, path);
                        String value = kind.asString();
                        check(Set.of("PERCENT_COUPON", "CASH_COUPON", "DEDUCTION_COUPON").contains(value)
                                && kinds.add(value), path, "INVALID_COMBINATION");
                    }
                    check(seen.add(new TreeSet<>(kinds).toString()), path, "DUPLICATE_COMBINATION");
                } else {
                    requireText(element, path);
                    validateEnumName(element, path);
                    if (path.equals("manualOperations.reasonCategories")) {
                        requireReference(element, path);
                    }
                    check(seen.add(element.asString()), path, "DUPLICATE_VALUE");
                }
            }
        } else {
            requireText(node, path);
            validateEnumName(node, path);
            if (path.equals("taxBinding.taxProfileNo")) {
                requireReference(node, path);
            }
        }
    }

    private static void validatePatch(JsonNode node, String path) {
        if (path.isEmpty()) {
            check(node.isObject(), "$", "INVALID_OVERRIDE");
        }
        if (node.isObject()) {
            check(OBJECTS.containsKey(path), path, "UNKNOWN_FIELD");
            node.properties().forEach(entry -> validatePatch(entry.getValue(), join(path, entry.getKey())));
        } else {
            check(EDITABLE.contains(path), path, "PROTECTED_OVERRIDE");
            rejectNull(node, path);
        }
    }

    private static void rejectNull(JsonNode node, String path) {
        check(!node.isNull(), path, "NULL_OVERRIDE");
        if (node.isArray() || node.isObject()) {
            node.forEach(child -> rejectNull(child, path));
        }
    }

    private static JsonNode merge(JsonNode defaults, JsonNode patch) {
        if (!defaults.isObject() || !patch.isObject()) {
            return patch.deepCopy();
        }
        ObjectNode result = (ObjectNode) defaults.deepCopy();
        patch.properties().forEach(entry -> {
            JsonNode prior = result.get(entry.getKey());
            result.set(entry.getKey(), prior == null ? entry.getValue().deepCopy() : merge(prior, entry.getValue()));
        });
        return result;
    }

    private static void remove(ObjectNode patch, String path) {
        String[] parts = path.split("\\.");
        ObjectNode current = patch;
        for (int i = 0; i < parts.length - 1; i++) {
            JsonNode next = current.get(parts[i]);
            if (next == null || !next.isObject()) {
                return;
            }
            current = (ObjectNode) next;
        }
        current.remove(parts[parts.length - 1]);
    }

    private static void validateDefinition(Definition config,
            SystemConstraints constraints, PricingCapabilities capabilities) {
        check(config.schemaVersion() == 2, "schemaVersion", "UNSUPPORTED_VERSION");
        check(config.formulaVersion() == 1 && FORMULAS.contains(config.formulaId()),
                "formulaId", "UNSUPPORTED_FORMULA");
        check(config.ruleRefs().size() == constraints.mandatoryRuleNos().size()
                && Set.copyOf(config.ruleRefs()).equals(constraints.mandatoryRuleNos()), "ruleRefs", "MANDATORY_RULES");
        var limits = config.couponLimits();
        for (int value : new int[] {limits.total(), limits.percent(), limits.amountTotal(), limits.cash(),
                limits.deduction(), config.discountCouponPolicy().maxUsedPerOrder(),
                config.discountCouponPolicy().maxUsedPerItem()}) {
            check(value >= 0 && value <= 64, "couponLimits", "COUNT_RANGE");
        }
        check(limits.percent() <= limits.total() && limits.amountTotal() <= limits.total()
                && limits.cash() <= limits.amountTotal() && limits.deduction() <= limits.amountTotal(),
                "couponLimits", "COUNT_CONFLICT");
        check(config.couponMode() != CouponMode.SINGLE || limits.total() == 1, "couponMode", "COUNT_CONFLICT");
        var policy = config.discountCouponPolicy();
        check(policy.maxUsedPerOrder() <= limits.percent(), "discountCouponPolicy", "COUNT_CONFLICT");
        if (policy.overlapMode() == OverlapMode.STACK) {
            check(policy.stackingEnabled() && policy.maxUsedPerItem() >= 2
                    && policy.maxUsedPerItem() <= policy.maxUsedPerOrder(), "discountCouponPolicy", "STACK_CONFLICT");
            check(capabilities.stacking(), "discountCouponPolicy", "OWNER_CAPABILITY_UNSUPPORTED");
        } else {
            check(!policy.stackingEnabled() && policy.maxUsedPerItem() == 1,
                    "discountCouponPolicy", "STACK_CONFLICT");
        }
        check(limits.total() <= capabilities.maxCouponsPerOrder()
                && limits.percent() <= capabilities.maxPercentCouponsPerOrder(),
                "couponLimits", "OWNER_CAPABILITY_UNSUPPORTED");
        boolean combines = config.allowedCouponCombinations().stream()
                .anyMatch(kinds -> kinds.contains(BenefitKind.PERCENT_COUPON));
        check(!combines || limits.total() < 2 || limits.percent() == 0 || limits.amountTotal() == 0
                || capabilities.percentAndAmount(), "allowedCouponCombinations", "OWNER_CAPABILITY_UNSUPPORTED");
        check(!config.storedValueEnabled() || capabilities.storedValue(),
                "storedValueEnabled", "OWNER_CAPABILITY_UNSUPPORTED");
        check(config.activityAndCoupons().equals("EXCLUSIVE"), "activityAndCoupons", "MANDATORY_POLICY");
        check(!config.pointsAutoApply() && config.pointsStage().equals("AFTER_STORED_VALUE")
                && config.optionalCapabilityPolicy().equals("DISABLED_UNLESS_VERIFIED_SOURCE_AND_OWNER"),
                "pointsStage", "MANDATORY_POLICY");
        var manual = config.manualOperations();
        check(manual.limits().source().equals("EXISTING_PERMISSION_AND_APPROVAL_AUTHORITY")
                && manual.limits().missingPolicy().equals("DENY")
                && manual.manualAmountConflictPolicy().equals("SAME_AS_MANUAL_PERCENT")
                && !manual.cashRoundingEnabled(), "manualOperations", "MANDATORY_POLICY");
        check(!manual.enabled() || !manual.reasonCategories().isEmpty(), "manualOperations", "REASON_REQUIRED");
        check(manual.minPayableRateBp() == null
                || manual.minPayableRateBp() >= 0 && manual.minPayableRateBp() <= 10000,
                "manualOperations.minPayableRateBp", "RATE_RANGE");
        var tax = config.taxBinding();
        check(tax.resolveFrom().equals("VERIFIED_STORE_REGION_AND_TRANSACTION_CONTEXT")
                && tax.missingPolicy().equals("BLOCK") && !tax.storeMayEditRate(), "taxBinding", "MANDATORY_POLICY");
    }

    private static void requireText(JsonNode node, String path) {
        check(node.isString() && !node.asString().isBlank() && node.asString().length() <= 128
                && node.asString().equals(node.asString().strip()), path, "INVALID_TEXT");
    }

    private static void validateEnumName(JsonNode node, String path) {
        Set<String> names = ENUM_NAMES.get(path);
        check(names == null || names.contains(node.asString()), path, "UNSUPPORTED_ENUM");
    }

    private static void requireReference(JsonNode node, String path) {
        String value = node.asString();
        check(value.length() <= 32 && value.codePoints().noneMatch(character -> Character.isWhitespace(character)
                || Character.isSpaceChar(character) || Character.isISOControl(character)), path, "INVALID_REFERENCE");
    }

    private static String join(String parent, String field) {
        return parent.isEmpty() ? field : parent + "." + field;
    }

    private static void check(boolean valid, String path, String code) {
        if (!valid) {
            throw new InvalidConfig(code, path);
        }
    }

    private static CompilationResult failure(String code, String path, String message) {
        return new CompilationResult(null, List.of(new CompilationResult.Issue(code, path, message)));
    }

    private static final class InvalidConfig extends IllegalArgumentException {
        private final String code;
        private final String path;

        private InvalidConfig(String code, String path) {
            super("Pricing configuration violates a typed constraint");
            this.code = code;
            this.path = path;
        }
    }
}
