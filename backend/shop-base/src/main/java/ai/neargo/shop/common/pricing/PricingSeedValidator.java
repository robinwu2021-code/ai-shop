package ai.neargo.shop.common.pricing;

import java.time.LocalDate;
import java.time.DateTimeException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/** Admits design seeds as drafts only; validation never authenticates owner capabilities or legal tax approval. */
public final class PricingSeedValidator {
    private static final Set<String> ROOT = Set.of("status", "schemaVersion", "seedVersion", "engineSpecVersion",
            "date", "importPolicy", "systemRules", "regionalTaxProfiles", "industryTemplates", "storeDraftExamples",
            "ownerCapabilityFixtureExamples", "calculationVectors", "sourceReferences");
    private static final Map<String, String> TEMPLATES = Map.of("PRICING_FOOD_DEFAULT", "FOOD",
            "PRICING_RETAIL_DEFAULT", "RETAIL", "PRICING_BEAUTY_SERVICE_DEFAULT", "BEAUTY_SERVICE");
    private static final Set<String> TAX_FIELDS = Set.of("taxProfileNo", "countryCode", "currency", "profileVersion",
            "policyStatus", "taxType", "taxPointPolicy", "effectiveFrom", "effectiveTo", "priceTaxMode",
            "consumerDisplayMode", "allowedContexts", "roundingScope", "roundingMode", "roundingAlgorithmVersion",
            "taxClasses", "unknownClassPolicy", "storeMayEditRate", "allowManualTaxOverride", "allowCashRounding",
            "adjustmentTreatmentSource", "sourceRefs", "requiresContextEvidence");

    /** The registry version is backend-selected, not an instruction to load a dynamic evaluator. */
    public SeedPlan validate(String source, String registryVersion) {
        try {
            return validateTree(PricingJson.parse(source), registryVersion);
        } catch (JacksonException | DateTimeException malformed) {
            throw new IllegalArgumentException("Invalid bounded seed JSON", malformed);
        }
    }

    /** Selects a fixed code-owned registry; the request cannot name or load an evaluator. */
    public String registryVersion(String source) {
        try {
            return PricingRuleCatalog.registryVersion(schemaVersion(PricingJson.parse(source)));
        } catch (JacksonException malformed) {
            throw new IllegalArgumentException("Invalid bounded seed JSON", malformed);
        }
    }

    private static int schemaVersion(JsonNode seed) {
        long schema = integer(seed, "schemaVersion");
        require(schema == 2 || schema == 3);
        return (int) schema;
    }

    private SeedPlan validateTree(JsonNode seed, String registryVersion) {
        int schema = schemaVersion(seed);
        var fields = new HashSet<>(ROOT);
        if (schema == 3) fields.add("platformTaxDefaultExample");
        PricingJson.fields(seed, fields, fields);
        require(PricingRuleCatalog.registryVersion(schema).equals(registryVersion));
        require(text(seed, "engineSpecVersion").equals(registryVersion));
        require(text(seed, "status").equals("DESIGN_EXAMPLE_NOT_EXECUTABLE_MIGRATION"));
        LocalDate.parse(text(seed, "date"));
        String version = text(seed, "seedVersion");
        require(version.matches("[A-Z][A-Z0-9_]{0,63}"));
        var policy = seed.path("importPolicy");
        var policyFields = Set.of("defaultMode", "createsAssetBalances", "autoPublish", "autoUpgradeStores",
                "examplesNeverImportedAsRealStores", "requireSupportedEvaluatorRegistry",
                "requireValidatedLegalTaxProfiles", "onSameVersionDifferentHash");
        PricingJson.fields(policy, policyFields, policyFields);
        require(text(policy, "defaultMode").equals("DRY_RUN"));
        require(text(policy, "onSameVersionDifferentHash").equals("REJECT"));
        for (String key : List.of("createsAssetBalances", "autoPublish", "autoUpgradeStores")) bool(policy, key, false);
        for (String key : List.of("examplesNeverImportedAsRealStores", "requireSupportedEvaluatorRegistry")) {
            bool(policy, key, true);
        }
        bool(policy, "requireValidatedLegalTaxProfiles", schema == 2);
        PricingRuleCatalog.validateJson(seed.path("systemRules").toString(), schema);
        var rows = seed.path("industryTemplates");
        require(rows.isArray() && rows.size() == 3);
        var seen = new HashSet<String>();
        var templates = new ArrayList<TemplateDraft>();
        for (var row : rows) {
            var keys = Set.of("templateNo", "industryCode", "revision", "publicationStatus", "engineSpecVersion",
                    "definition");
            PricingJson.fields(row, keys, keys);
            String alias = text(row, "templateNo");
            String industry = text(row, "industryCode");
            require(industry.equals(TEMPLATES.get(alias)) && seen.add(industry));
            require(integer(row, "revision") == 1 && text(row, "publicationStatus").equals("DRAFT"));
            require(text(row, "engineSpecVersion").equals(registryVersion));
            // This is a syntax ceiling only, never a provider capability grant or publication context.
            var result = new PricingConfigCompiler().compile(row.path("definition").toString(), "{}",
                    SystemConstraints.standard(), new PricingCapabilities(8, 4, true, true, true));
            require(result.valid() && result.config().definition().schemaVersion() == schema);
            templates.add(new TemplateDraft(alias, industry, result.config().canonicalJson(), schema));
        }
        int taxDraftCount;
        if (schema == 2) {
            validateTaxes(seed.path("regionalTaxProfiles"), seed.path("sourceReferences"));
            taxDraftCount = 2;
        } else {
            require(seed.path("regionalTaxProfiles").isArray() && seed.path("regionalTaxProfiles").isEmpty());
            var defaults = seed.path("platformTaxDefaultExample");
            var keys = Set.of("rateBp", "priceTaxMode");
            PricingJson.fields(defaults, keys, keys);
            long rate = integer(defaults, "rateBp");
            require(rate >= 0 && rate <= 10000);
            PriceTaxMode.valueOf(text(defaults, "priceTaxMode"));
            require(seed.path("sourceReferences").isObject());
            seed.path("sourceReferences").properties().forEach(entry -> require(entry.getValue().isString()));
            taxDraftCount = 0;
        }
        require(seed.path("storeDraftExamples").isArray());
        require(seed.path("ownerCapabilityFixtureExamples").isArray());
        require(seed.path("calculationVectors").isArray());
        var provenance = PricingJson.JSON.createObjectNode();
        for (String name : List.of("schemaVersion", "seedVersion", "engineSpecVersion", "systemRules",
                "regionalTaxProfiles", "sourceReferences")) provenance.set(name, seed.get(name));
        if (schema == 3) provenance.set("platformTaxDefaultExample", seed.get("platformTaxDefaultExample"));
        return new SeedPlan(version, PricingJson.hash(PricingJson.canonical(seed)), 20, templates, taxDraftCount,
                PricingJson.canonical(provenance));
    }

    private static void validateTaxes(JsonNode profiles, JsonNode references) {
        require(references.isObject() && !references.isEmpty());
        references.properties().forEach(entry -> require(entry.getValue().isString()));
        require(profiles.isArray() && profiles.size() == 2);
        var seen = new HashSet<String>();
        for (var profile : profiles) {
            var required = new HashSet<>(TAX_FIELDS);
            required.remove("requiresContextEvidence");
            PricingJson.fields(profile, TAX_FIELDS, required);
            String no = text(profile, "taxProfileNo");
            require(Set.of("AE_VAT_CONSUMER_V1", "AE_VAT_NET_PRICE_V1").contains(no) && seen.add(no));
            require(text(profile, "countryCode").equals("AE") && text(profile, "currency").equals("AED"));
            require(integer(profile, "profileVersion") == 1);
            require(text(profile, "policyStatus").equals("REQUIRES_PLATFORM_REVIEW_BEFORE_PUBLICATION"));
            require(text(profile, "taxType").equals("VAT"));
            require(text(profile, "taxPointPolicy").equals("REQUIRE_TRUSTED_SOURCE"));
            require(text(profile, "roundingScope").equals("INVOICE_LINE"));
            require(text(profile, "roundingMode").equals("HALF_UP"));
            require(text(profile, "roundingAlgorithmVersion").equals("AE_LINE_FILS_V1"));
            require(text(profile, "unknownClassPolicy").equals("BLOCK"));
            require(text(profile, "adjustmentTreatmentSource").equals("VERIFIED_OWNER_AND_REGIONAL_POLICY"));
            for (String field : List.of("storeMayEditRate", "allowManualTaxOverride", "allowCashRounding")) {
                bool(profile, field, false);
            }
            LocalDate from = LocalDate.parse(text(profile, "effectiveFrom"));
            if (!profile.path("effectiveTo").isNull()) {
                require(LocalDate.parse(text(profile, "effectiveTo")).isAfter(from));
            }
            var mode = PriceTaxMode.valueOf(text(profile, "priceTaxMode"));
            require(text(profile, "consumerDisplayMode").equals("INCLUSIVE"));
            require((mode == PriceTaxMode.INCLUSIVE) == no.equals("AE_VAT_CONSUMER_V1"));
            if (mode == PriceTaxMode.EXCLUSIVE) bool(profile, "requiresContextEvidence", true);
            else require(!profile.has("requiresContextEvidence"));
            var contexts = profile.path("allowedContexts");
            require(contexts.isArray() && !contexts.isEmpty() && contexts.size() <= 16);
            for (var value : contexts) require(value.isString() && !value.asString().isBlank());
            var classes = profile.path("taxClasses");
            require(classes.isArray() && classes.size() == 4);
            var classNames = new HashSet<TaxClass>();
            for (var taxClass : classes) {
                PricingJson.fields(taxClass, Set.of("code", "rateBp"), Set.of("code", "rateBp"));
                var kind = TaxClass.valueOf(text(taxClass, "code"));
                require(kind != TaxClass.UNKNOWN && classNames.add(kind));
                if (kind == TaxClass.STANDARD) require(integer(taxClass, "rateBp") > 0
                        && integer(taxClass, "rateBp") <= 10000);
                else if (kind == TaxClass.ZERO_RATED) require(integer(taxClass, "rateBp") == 0);
                else require(taxClass.path("rateBp").isNull());
            }
            var sourceRefs = profile.path("sourceRefs");
            require(sourceRefs.isArray() && !sourceRefs.isEmpty() && sourceRefs.size() <= 16);
            for (var value : sourceRefs) require(value.isString() && references.has(value.asString()));
        }
    }

    private static String text(JsonNode node, String key) {
        require(node.path(key).isString() && !node.path(key).asString().isBlank());
        return node.path(key).asString();
    }
    private static long integer(JsonNode node, String key) {
        require(node.path(key).isIntegralNumber() && node.path(key).canConvertToLong());
        return node.path(key).asLong();
    }
    private static void bool(JsonNode node, String key, boolean expected) {
        require(node.path(key).isBoolean() && node.path(key).booleanValue() == expected);
    }
    private static void require(boolean condition) {
        if (!condition) throw new IllegalArgumentException("Unsupported or unsafe draft seed");
    }

    public record TemplateDraft(String sourceTemplateNo, String industryCode, String definition, int schemaVersion) { }
    public record SeedPlan(String seedVersion, String seedHash, int ruleCount, List<TemplateDraft> templates,
            int taxDraftCount, String provenanceSnapshot) {
        public SeedPlan { templates = List.copyOf(templates); }
    }
}
