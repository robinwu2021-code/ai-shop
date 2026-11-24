package ai.neargo.shop.portal.biz.feature;

import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/** A catalog feature projects registered duties into a stable client code. */
public record StoreFeature(String code, String label, Group module, List<String> industries, Rule entitlement,
                           Lifecycle lifecycle, String clientRoute, boolean requiresPayActivation,
                           List<Action> actions) {

    public enum Group { BASE, POS, RETAIL, FNB, SERVICE, MARKETING, CHAIN, OTHER }

    public enum Lifecycle { ACTIVE, DRAFT, RETIRED }

    /** The same expression type is used for rights and duties. An empty use rule means store membership. */
    public record Rule(List<String> allOf, List<String> anyOf) {
        public Rule {
            allOf = allOf == null ? List.of() : List.copyOf(allOf);
            anyOf = anyOf == null ? List.of() : List.copyOf(anyOf);
        }

        public boolean matches(Set<String> granted) {
            return granted.containsAll(allOf) && (anyOf.isEmpty() || anyOf.stream().anyMatch(granted::contains));
        }

        public List<String> codes() {
            return Stream.concat(allOf.stream(), anyOf.stream()).distinct().toList();
        }
    }

    /** Permission codes are the grantable leaves. A duty reused by multiple actions remains a single grant. */
    public record Action(String code, List<String> allOf, List<String> anyOf) {
        public Action {
            var rule = new Rule(allOf, anyOf);
            allOf = rule.allOf();
            anyOf = rule.anyOf();
        }

        public Rule rule() {
            return new Rule(allOf, anyOf);
        }

        public boolean permits(Set<String> perms) {
            return perms.contains("*") || rule().matches(perms);
        }
    }

    public StoreFeature {
        industries = List.copyOf(industries);
        actions = List.copyOf(actions);
    }

    public static StoreFeature[] values() {
        return PosFeatureCatalog.bundled().features().toArray(StoreFeature[]::new);
    }

    public Group group() {
        return module;
    }

    /** Compatibility projection for old clients. New clients can read the full rule in the role directory. */
    public String entitlementCode() {
        return entitlement.codes().getFirst();
    }

    public boolean appliesTo(String industry) {
        return industry == null || industries.isEmpty() || industries.contains(canonicalIndustry(industry));
    }

    static String canonicalIndustry(String industry) {
        return "FNB".equals(industry) ? "FOOD" : industry;
    }

    public List<String> usePerms() {
        return actions.getFirst().rule().codes();
    }

    public List<String> managePerms() {
        return actions.stream().filter(a -> a.code().equals("manage")).findFirst()
                .map(a -> a.rule().codes()).orElse(List.of());
    }

    public boolean canUse(Set<String> perms) {
        return actions.getFirst().permits(perms);
    }

    public boolean canManage(Set<String> perms) {
        return actions.stream().anyMatch(a -> a.code().equals("manage") && a.permits(perms));
    }
}
