package ai.neargo.shop.portal.biz.feature;

import ai.neargo.commerce.api.access.AccessDecision;
import ai.neargo.commerce.api.access.AccessStatus;
import ai.neargo.commerce.api.common.CommerceModel;
import ai.neargo.commerce.api.common.PlanTier;
import ai.neargo.commerce.api.foundation.FoundationQueryFacade.Interval;
import ai.neargo.commerce.api.foundation.FoundationQueryFacade.PlanCard;
import ai.neargo.commerce.api.foundation.FoundationQueryFacade.PlanCatalog;
import ai.neargo.commerce.api.foundation.FoundationQueryFacade.Right;
import ai.neargo.commerce.api.foundation.FoundationQueryFacade.Source;
import ai.neargo.commerce.api.foundation.FoundationQueryFacade.StoreContext;
import ai.neargo.shop.auth.BizPerms;

import java.util.Comparator;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 功能矩阵的计算（TDD-B端门店功能矩阵与方案准入）。纯函数：取数在控制器，这里只算。
 *
 * <p>一个功能对当前用户的状态按顺序判，第一条不满足的就是它的状态：
 * <ol>
 *   <li>经营类型的方案是否提供该权益，或门店是否有生效的独立授权</li>
 *   <li>门店该权益码的商业状态（旧模型门店跳过）</li>
 *   <li>当前用户有没有任一使用权限码</li>
 *   <li>要收款前置的，门店收款是否已开通</li>
 * </ol>
 * 先商业后角色，与端准入（{@code EndAccessFacade}）的先后一致；下一步动作沿用 {@link AccessDecision#nextActionFor}。
 */
public final class StoreFeatureMatrix {

    /** 收款开通状态；{@code UNKNOWN} = 读失败，不能当成未开通 */
    public enum PayActivation { ACTIVE, NOT_ACTIVE, UNKNOWN }

    /** 预置角色，按「店主 → 店长 → 一线」排 */
    static final List<String> ROLES = List.of(BizPerms.OWNER, BizPerms.MANAGER, BizPerms.CLERK, BizPerms.PICKER,
            BizPerms.COURIER, BizPerms.CS);

    public record TierCard(PlanTier tier, String planNo, String displayName, boolean purchaseAvailable) {}

    /**
     * @param tiers 各档是否包含，键为档位名
     * @param roles 预置角色 → {@code NONE} / {@code USE} / {@code MANAGE}
     */
    public record MatrixFeature(String featureCode, String group, String entitlementCode,
                                boolean requiresPayActivation, PlanTier minTier, Map<String, Boolean> tiers,
                                List<String> usePerms, List<String> managePerms, Map<String, String> roles,
                                Map<String, Boolean> proposedTiers) {}

    public record Matrix(String businessTypeCode, Long catalogVersion, String status, List<TierCard> tiers,
                         List<String> roles, List<MatrixFeature> features, String catalogReleaseNo,
                         List<PosFeatureCatalog.Plan> proposedPlans) {}

    /**
     * @param requiredTier   已发布方案里包含该权益码的最低档；旧模型门店为 null
     * @param effectiveUntil 该权益码当前连续有效期的结束（毫秒）；null = 长期或未生效
     */
    public record FeatureState(String featureCode, String group, String entitlementCode, String status,
                               String nextAction, PlanTier requiredTier, Long effectiveUntil, boolean canUse,
                               boolean canManage, List<ActionState> actions) {}

    public record ActionState(String actionCode, boolean allowed) {}

    public record StoreFeatures(String storeNo, String businessTypeCode, CommerceModel commerceModel,
                                String currentTier, Long entitlementVersion, long serverNow, Long nextBoundaryAt,
                                PayActivation payActivation, List<FeatureState> features, int featureSchemaVersion,
                                String catalogReleaseNo, String authzVersion) {}

    private StoreFeatureMatrix() {
    }

    // ------------------------------------------------------------------ 经营类型的矩阵

    public static Matrix forBusinessType(PlanCatalog catalog) {
        return forBusinessType(catalog, PosFeatureCatalog.bundled());
    }

    public static Matrix forBusinessType(PlanCatalog catalog, PosFeatureCatalog release) {
        var plans = sortedPlans(catalog);
        var tiers = plans.stream()
                .map(p -> new TierCard(p.tier(), p.planNo(), p.displayName(), p.purchaseAvailable()))
                .toList();
        Map<String, Set<String>> rolePerms = ROLES.stream()
                .collect(Collectors.toMap(Function.identity(), r -> BizPerms.of(Set.of(r))));
        var features = release.features().stream()
                .filter(f -> f.lifecycle() == StoreFeature.Lifecycle.ACTIVE && f.appliesTo(catalog.businessTypeCode()))
                .filter(f -> minTier(plans, f).isPresent())
                .map(f -> {
                    Map<String, Boolean> inTier = new LinkedHashMap<>();
                    plans.forEach(p -> inTier.put(p.tier().name(), f.entitlement().matches(Set.copyOf(p.entitlementCodes()))));
                    Map<String, String> roles = new LinkedHashMap<>();
                    ROLES.forEach(r -> roles.put(r, roleLevel(f, rolePerms.get(r))));
                    Map<String, Boolean> proposed = new LinkedHashMap<>();
                    PlanTier.catalogTiers(catalog.businessTypeCode()).forEach(tier -> {
                        var rights = release.proposedRights(catalog.businessTypeCode(), tier);
                        if (!rights.isEmpty()) proposed.put(tier.name(), f.entitlement().matches(Set.copyOf(rights)));
                    });
                    return new MatrixFeature(f.code(), f.group().name(), f.entitlementCode(),
                            f.requiresPayActivation(), minTier(plans, f).orElse(null), inTier, f.usePerms(),
                            f.managePerms(), roles, proposed);
                })
                .toList();
        return new Matrix(catalog.businessTypeCode(), catalog.catalogVersion(), catalog.status(), tiers, ROLES,
                features, release.releaseNo(), release.proposedPlans(catalog.businessTypeCode()));
    }

    private static String roleLevel(StoreFeature f, Set<String> perms) {
        if (f.canManage(perms)) return "MANAGE";
        return f.canUse(perms) ? "USE" : "NONE";
    }

    // ------------------------------------------------------------------ 当前用户在当前门店

    /**
     * @param catalog 门店经营类型的已发布方案；取不到时传 null（此时不按行业过滤，只按门店已有的权益码）
     * @param perms   当前用户在当前门店的有效权限码（{@code BizContext#effectivePerms}）
     */
    public static StoreFeatures forStore(StoreContext store, PlanCatalog catalog, Set<String> perms,
                                         PayActivation pay) {
        return forStore(store, catalog, perms, pay, PosFeatureCatalog.bundled());
    }

    public static StoreFeatures forStore(StoreContext store, PlanCatalog catalog, Set<String> perms,
                                         PayActivation pay, PosFeatureCatalog release) {
        boolean legacy = store.commerceModel() != CommerceModel.STORE_V1;
        var plans = sortedPlans(catalog);
        Map<String, Right> rights = store.entitlements() == null ? Map.of()
                : store.entitlements().items().stream()
                        .collect(Collectors.toMap(Right::entitlementCode, Function.identity(), (a, b) -> a));
        var activeRights = activeRights(rights);
        var features = release.features().stream()
                .filter(f -> f.lifecycle() == StoreFeature.Lifecycle.ACTIVE && f.appliesTo(store.businessTypeCode()))
                .filter(f -> {
                    boolean inCatalog = catalog == null || minTier(plans, f).isPresent();
                    if (legacy) return inCatalog;
                    return f.entitlement().matches(activeRights)
                            || inCatalog && f.entitlement().codes().stream().anyMatch(rights::containsKey);
                })
                .map(f -> state(f, legacy ? AccessStatus.ALLOWED : commercialStatus(f, rights, activeRights),
                        legacy ? null : effectiveUntil(f, rights, activeRights),
                        legacy ? null : minTier(plans, f).orElse(null), store.canProcure(), perms, pay))
                .toList();
        return new StoreFeatures(store.storeNo(), store.businessTypeCode(), store.commerceModel(),
                legacy ? null : currentTier(store), store.entitlementVersion(), store.serverNow(),
                store.nextBoundaryAt(), pay, features, release.schemaVersion(), release.releaseNo(),
                PosFeatureCatalog.digest(String.join("\n", perms.stream().sorted().toList())
                        .getBytes(StandardCharsets.UTF_8)));
    }

    /** Effective commercial rights for role delegation. Channel activation and employee duties are checked separately. */
    public static Set<String> activeRights(StoreContext store) {
        if (store.entitlements() == null) return Set.of();
        return activeRights(store.entitlements().items().stream()
                .collect(Collectors.toMap(Right::entitlementCode, Function.identity(), (first, second) -> first)));
    }

    private static Set<String> activeRights(Map<String, Right> rights) {
        return rights.values().stream().filter(r -> commercialStatus(r.commercialStatus()) == AccessStatus.ALLOWED)
                .map(Right::entitlementCode).collect(Collectors.toUnmodifiableSet());
    }

    private static AccessStatus commercialStatus(StoreFeature feature, Map<String, Right> rights, Set<String> active) {
        var rule = feature.entitlement();
        if (rule.matches(active)) return AccessStatus.ALLOWED;
        return rule.codes().stream().filter(c -> !active.contains(c)).map(rights::get)
                .filter(Objects::nonNull).map(r -> commercialStatus(r.commercialStatus()))
                .findFirst().orElse(AccessStatus.NOT_PURCHASED);
    }

    private static Long effectiveUntil(StoreFeature feature, Map<String, Right> rights, Set<String> active) {
        return feature.entitlement().codes().stream().filter(active::contains).map(rights::get)
                .map(Right::current).filter(Objects::nonNull).map(Interval::endsAt)
                .filter(Objects::nonNull).min(Long::compareTo).orElse(null);
    }

    private static FeatureState state(StoreFeature f, AccessStatus commercial, Long until, PlanTier requiredTier,
                                      boolean canProcure, Set<String> perms, PayActivation pay) {
        String status;
        String next;
        if (commercial != AccessStatus.ALLOWED) {
            status = switch (commercial) {
                case NOT_PURCHASED -> "NEED_PLAN";
                case EXPIRED -> "PLAN_EXPIRED";
                case NOT_STARTED, REVOKED, STORE_DISABLED, FORBIDDEN -> commercial.name();
                default -> "CHECK_UNAVAILABLE";
            };
            next = AccessDecision.nextActionFor(commercial, canProcure);
        } else if (!f.canUse(perms)) {
            status = "NO_PERMISSION";
            next = "CONTACT_OWNER";
        } else if (f.requiresPayActivation() && pay != PayActivation.ACTIVE) {
            if (pay == PayActivation.UNKNOWN) {
                status = "CHECK_UNAVAILABLE";
                next = "RETRY_CHECK";
            } else {
                status = "NEED_PAY_ACTIVATION";
                next = (perms.contains("*") || perms.contains(BizPerms.FINANCE)) ? "APPLY_PAYMENT" : "CONTACT_OWNER";
            }
        } else {
            status = "AVAILABLE";
            next = "ENTER";
        }
        boolean canUse = "AVAILABLE".equals(status);
        return new FeatureState(f.code(), f.group().name(), f.entitlementCode(), status, next, requiredTier, until,
                canUse, canUse && f.canManage(perms), f.actions().stream()
                        .map(a -> new ActionState(a.code(), commercial == AccessStatus.ALLOWED
                                && (!f.requiresPayActivation() || pay == PayActivation.ACTIVE) && a.permits(perms)))
                        .toList());
    }

    /** {@code Right.commercialStatus}：ACTIVE 或 {@link AccessStatus} 的名字；认不出的按「检查不可用」 */
    private static AccessStatus commercialStatus(String s) {
        if ("ACTIVE".equals(s)) return AccessStatus.ALLOWED;
        try {
            return s == null ? AccessStatus.CHECK_UNAVAILABLE : AccessStatus.valueOf(s);
        } catch (IllegalArgumentException e) {
            return AccessStatus.CHECK_UNAVAILABLE;
        }
    }

    private static String currentTier(StoreContext store) {
        var sub = store.subscription();
        if (sub == null) return null;
        if (sub.currentPlan() != null) return sub.currentPlan().tier();
        Source free = sub.free();
        return free == null ? null : PlanTier.FREE.name();
    }

    // ------------------------------------------------------------------ 共用

    private static List<PlanCard> sortedPlans(PlanCatalog catalog) {
        if (catalog == null || catalog.plans() == null) return List.of();
        return catalog.plans().stream().sorted(Comparator.comparing(PlanCard::tier)).toList();
    }

    private static Optional<PlanTier> minTier(List<PlanCard> plans, StoreFeature f) {
        return plans.stream().filter(p -> f.entitlement().matches(Set.copyOf(p.entitlementCodes())))
                .map(PlanCard::tier).findFirst();
    }
}
