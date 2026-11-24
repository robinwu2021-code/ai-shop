package ai.neargo.shop.portal.biz.feature;

import ai.neargo.commerce.api.common.CommerceModel;
import ai.neargo.commerce.api.foundation.FoundationQueryFacade.StoreContext;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.merchant.dto.RoleVO;

import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/** Builds the complete role editor view and derives permission delegation from the same catalog. */
public final class RoleFeatureCatalog {
    private RoleFeatureCatalog() {}

    public record Directory(int featureSchemaVersion, String catalogReleaseNo, String storeNo,
                            Long entitlementVersion, String businessTypeCode, RoleVO role, List<Module> modules,
                            List<Permission> additionalPermissions) {}

    public record Module(String code, String label, List<Feature> features) {}

    public record Feature(String code, String label, StoreFeature.Lifecycle lifecycle, String clientRoute,
                          StoreFeature.Rule entitlement, String status, List<Action> actions) {}

    public record Action(String code, List<String> allOf, List<String> anyOf, List<Permission> permissions) {}

    public record Permission(String code, String label, boolean selectable, boolean selected, String reason) {}

    public static Directory forStore(PosFeatureCatalog release, StoreContext store, RoleVO role, Predicate<String> registered) {
        var active = StoreFeatureMatrix.activeRights(store);
        var grantable = grantable(release, store, registered, active);
        Set<String> selected = role == null ? Set.of() : Set.copyOf(role.perms());
        var mapped = release.features().stream().flatMap(f -> f.actions().stream())
                .flatMap(a -> a.rule().codes().stream()).collect(Collectors.toUnmodifiableSet());
        var modules = release.modules().stream()
                .map(m -> new Module(m.code().name(), m.label(), release.features().stream()
                        .filter(f -> f.module() == m.code())
                        .map(f -> feature(f, store, active, selected, grantable, registered)).toList()))
                .filter(m -> !m.features().isEmpty()).toList();
        var additional = BizPerms.LABELS.keySet().stream().filter(c -> !mapped.contains(c)).sorted()
                .map(c -> permission(c, selected, grantable, registered)).toList();
        return new Directory(release.schemaVersion(), release.releaseNo(), store.storeNo(), store.entitlementVersion(),
                store.businessTypeCode(), role, modules, additional);
    }

    private static Feature feature(StoreFeature f, StoreContext store, Set<String> active, Set<String> selected,
                                   Set<String> grantable, Predicate<String> registered) {
        String status = status(f, store, active);
        var actions = f.actions().stream().map(a -> new Action(a.code(), a.allOf(), a.anyOf(),
                a.rule().codes().stream().map(c -> {
                    var p = permission(c, selected, grantable, registered);
                    return "AVAILABLE".equals(status) || !p.selectable() ? p
                            : new Permission(c, p.label(), false, p.selected(), status);
                }).toList())).toList();
        return new Feature(f.code(), f.label(), f.lifecycle(), f.clientRoute(), f.entitlement(), status, actions);
    }

    private static Permission permission(String code, Set<String> selected, Set<String> grantable,
                                         Predicate<String> registered) {
        boolean canSelect = grantable.contains(code);
        String reason = canSelect ? null : !BizPerms.assignableCodes().contains(code) ? "PROTECTED"
                : !registered.test(code) ? "NOT_REGISTERED" : "NO_ACTIVE_FEATURE";
        return new Permission(code, BizPerms.LABELS.get(code), canSelect, selected.contains("*") || selected.contains(code),
                reason);
    }

    /** A shared duty is assignable if at least one feature using it is available in the selected store. */
    public static Set<String> grantable(PosFeatureCatalog release, StoreContext store, Predicate<String> registered) {
        return grantable(release, store, registered, StoreFeatureMatrix.activeRights(store));
    }

    private static Set<String> grantable(PosFeatureCatalog release, StoreContext store, Predicate<String> registered,
                                         Set<String> active) {
        var map = release.features().stream().flatMap(f -> f.actions().stream()
                        .flatMap(a -> a.rule().codes().stream().map(code -> java.util.Map.entry(code, f))))
                .collect(Collectors.groupingBy(java.util.Map.Entry::getKey,
                        Collectors.mapping(java.util.Map.Entry::getValue, Collectors.toList())));
        return BizPerms.assignableCodes().stream().filter(registered).filter(code -> !map.containsKey(code)
                        || map.get(code).stream().anyMatch(f -> status(f, store, active).equals("AVAILABLE")))
                .collect(Collectors.toUnmodifiableSet());
    }

    private static String status(StoreFeature feature, StoreContext store, Set<String> active) {
        if (feature.lifecycle() != StoreFeature.Lifecycle.ACTIVE) return feature.lifecycle().name();
        if (!feature.appliesTo(store.businessTypeCode())) return "NOT_APPLICABLE";
        if (store.commerceModel() == CommerceModel.STORE_V1) {
            if (!"ACTIVE".equals(store.storeStatus()) || !"ACTIVE".equals(store.entityStatus())) {
                return "STORE_DISABLED";
            }
            if (!feature.entitlement().matches(active)) return "NEED_PLAN";
        }
        return "AVAILABLE";
    }
}
