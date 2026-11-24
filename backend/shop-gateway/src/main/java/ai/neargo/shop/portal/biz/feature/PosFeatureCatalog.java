package ai.neargo.shop.portal.biz.feature;

import ai.neargo.commerce.api.common.PlanTier;
import ai.neargo.shop.auth.BizPerms;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Validates the released manifest before any of its features or grant rules can be used. */
public final class PosFeatureCatalog {
    private static final String RESOURCE = "/config/pos/feature-catalog.yaml";
    private static final Set<String> INDUSTRIES = Set.of("RETAIL", "FNB", "SERVICE");
    private static final int PLAN_COUNT = INDUSTRIES.size() * PlanTier.catalogTiers("SERVICE").size();
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final Manifest manifest;
    private final String releaseNo;
    private final String canonicalJson;
    private final Map<String, StoreFeature> byCode;

    public record Module(StoreFeature.Group code, String label) {}

    public record Entitlement(String code, List<String> industries) {}

    public record Plan(String businessType, PlanTier tier, List<String> entitlements) {}

    public record Manifest(int schemaVersion, List<Module> modules, List<Entitlement> entitlements,
                           List<StoreFeature> features, List<Plan> plans) {}

    private PosFeatureCatalog(Manifest manifest, String canonicalJson) {
        this.manifest = manifest;
        this.canonicalJson = canonicalJson;
        this.releaseNo = digest(canonicalJson.getBytes(StandardCharsets.UTF_8));
        byCode = index(manifest.features(), StoreFeature::code);
        validate();
    }

    private static class Bundled {
        private static final PosFeatureCatalog INSTANCE = load();
    }

    public static PosFeatureCatalog bundled() {
        return Bundled.INSTANCE;
    }

    private static PosFeatureCatalog load() {
        try (var input = PosFeatureCatalog.class.getResourceAsStream(RESOURCE)) {
            return read(input);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read POS feature catalog", e);
        }
    }

    /** The duplicate-key check runs before Java binding so a typo cannot silently replace an earlier rule. */
    public static PosFeatureCatalog read(InputStream input) throws IOException {
        if (input == null) throw new IllegalArgumentException("Missing POS feature catalog");
        var options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(0);
        var data = new Yaml(new SafeConstructor(options)).load(input);
        var manifest = MAPPER.convertValue(data, Manifest.class);
        return new PosFeatureCatalog(manifest, MAPPER.writeValueAsString(manifest));
    }

    public static PosFeatureCatalog fromPublishedJson(String json, String releaseNo) throws IOException {
        var catalog = new PosFeatureCatalog(MAPPER.readValue(json, Manifest.class), json);
        if (!catalog.releaseNo().equals(releaseNo)) throw new IllegalArgumentException("POS catalog checksum mismatch");
        return catalog;
    }

    public String canonicalJson() {
        return canonicalJson;
    }

    public int schemaVersion() {
        return manifest.schemaVersion();
    }

    public String releaseNo() {
        return releaseNo;
    }

    public List<Module> modules() {
        return List.copyOf(manifest.modules());
    }

    public List<StoreFeature> features() {
        return List.copyOf(manifest.features());
    }

    public StoreFeature feature(String code) {
        return byCode.get(code);
    }

    public List<Plan> proposedPlans(String industry) {
        return manifest.plans().stream().filter(p -> p.businessType().equals(industry)).toList();
    }

    public List<String> proposedRights(String industry, PlanTier tier) {
        return manifest.plans().stream().filter(p -> p.businessType().equals(industry) && p.tier() == tier)
                .findFirst().map(p -> List.copyOf(p.entitlements())).orElse(List.of());
    }

    public static String digest(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void validate() {
        require(manifest.schemaVersion() == 1, "Unsupported schema version");
        var modules = index(manifest.modules(), Module::code);
        var entitlements = index(manifest.entitlements(), Entitlement::code);
        for (var module : modules.values()) require(text(module.label()), "Missing module label " + module.code());
        for (var end : entitlements.values()) {
            require(end.code().startsWith("END_") && validIndustries(end.industries()), "Invalid end " + end.code());
        }

        var routes = new HashSet<String>();
        for (var feature : features()) {
            require(feature.code().matches("[A-Z][A-Z0-9_]+") && text(feature.label())
                    && modules.containsKey(feature.module()) && feature.lifecycle() != null
                    && validIndustries(feature.industries()), "Invalid feature " + feature.code());
            require(feature.clientRoute() == null || text(feature.clientRoute()) && routes.add(feature.clientRoute()),
                    "Invalid or duplicate client route " + feature.clientRoute());
            require(!feature.entitlement().codes().isEmpty(), "Missing entitlement for " + feature.code());
            for (String code : feature.entitlement().codes()) {
                var end = entitlements.get(code);
                require(end != null && (end.industries().isEmpty()
                        || !feature.industries().isEmpty() && end.industries().containsAll(feature.industries())),
                        "Invalid entitlement " + code + " for " + feature.code());
            }
            var actions = index(feature.actions(), StoreFeature.Action::code);
            require(!feature.actions().isEmpty() && feature.actions().getFirst().code().equals("use"),
                    "Missing first use action for " + feature.code());
            for (var action : actions.values()) {
                require(action.code().matches("[a-z][a-z0-9_]*")
                        && BizPerms.LABELS.keySet().containsAll(action.rule().codes()),
                        "Invalid action or unknown permission " + feature.code() + "/" + action.code());
            }
        }

        for (String code : List.of("POS_CHECKOUT", "POS_CASH", "GOODS", "ORDERS", "MEMBERS_BASIC", "CAMPAIGN_BASIC")) {
            var feature = byCode.get(code);
            require(feature != null && feature.lifecycle() == StoreFeature.Lifecycle.ACTIVE
                            && feature.industries().isEmpty() && feature.entitlement().matches(Set.of("END_BIZ_APP")),
                    "Missing shared baseline feature " + code);
        }

        var plans = index(manifest.plans(), plan -> plan.businessType() + "/" + plan.tier());
        require(plans.size() == PLAN_COUNT, "Missing configured tier");
        for (var plan : plans.values()) {
            require(INDUSTRIES.contains(plan.businessType()) && PlanTier.catalogTiers(plan.businessType()).contains(plan.tier()),
                    "Invalid plan " + plan.businessType() + "/" + plan.tier());
            require(plan.entitlements().contains("END_BIZ_APP"), "Missing base end for " + plan.businessType());
            for (String code : index(plan.entitlements(), Function.identity()).keySet()) {
                var end = entitlements.get(code);
                require(end != null && (end.industries().isEmpty() || end.industries().contains(plan.businessType())),
                        "Invalid plan entitlement " + code + " for " + plan.businessType());
            }
        }
    }

    private static boolean text(String value) {
        return value != null && !value.isBlank();
    }

    private static boolean validIndustries(List<String> industries) {
        return INDUSTRIES.containsAll(industries) && Set.copyOf(industries).size() == industries.size();
    }

    private static <K, V> Map<K, V> index(List<V> values, Function<V, K> key) {
        return values.stream().collect(Collectors.toUnmodifiableMap(key, Function.identity()));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
