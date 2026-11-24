package ai.neargo.shop.portal.biz.feature;

import ai.neargo.commerce.api.common.CommerceModel;
import ai.neargo.commerce.api.common.StoreRef;
import ai.neargo.commerce.api.foundation.FoundationQueryFacade;
import ai.neargo.commerce.api.foundation.FoundationQueryFacade.StoreContext;
import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.commercebridge.ShopCommerceActorAdapter;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.gateway.core.function.FunctionRegistry;
import ai.neargo.shop.merchant.dto.RoleVO;
import ai.neargo.shop.merchant.service.MerchantRoleService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

@Component
@ConditionalOnProperty(name = "commerce.enabled", havingValue = "true")
public class RoleFeatureAccess {
    private final FoundationQueryFacade queries;
    private final MerchantRoleService roles;
    private final FunctionRegistry registry;
    private final PosFeatureCatalogStore releases;

    public RoleFeatureAccess(FoundationQueryFacade queries, MerchantRoleService roles, FunctionRegistry registry,
                             PosFeatureCatalogStore releases) {
        this.queries = queries;
        this.roles = roles;
        this.registry = registry;
        this.releases = releases;
    }

    /** Role preview uses the selected store and the authenticated entity. */
    public RoleFeatureCatalog.Directory directory(String roleCode) {
        var release = releases.current(ShopCommerceActorAdapter.current().tenantNo());
        return RoleFeatureCatalog.forStore(release, selectedStore(), roleCode == null ? null : role(roleCode),
                this::registered);
    }

    /** Returns the exact previous role version used for validation, also for legacy API clients on STORE_V1. */
    public Long validate(String roleCode, List<String> requested, String releaseNo, Long expectedVersion) {
        var release = releases.current(ShopCommerceActorAdapter.current().tenantNo());
        if (releaseNo != null && !releaseNo.equals(release.releaseNo())) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        StoreContext store = selectedStore();
        if (store.commerceModel() != CommerceModel.STORE_V1 && releaseNo == null) return expectedVersion;
        if (requested == null || requested.stream().anyMatch(java.util.Objects::isNull)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        RoleVO before = roleCode == null ? null : role(roleCode);
        if (before != null && expectedVersion != null && !expectedVersion.equals(before.version())) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        Set<String> existing = before == null ? Set.of() : Set.copyOf(before.perms());
        Set<String> grantable = RoleFeatureCatalog.grantable(release, store, this::registered);
        if (requested.stream().anyMatch(c -> !existing.contains(c) && !grantable.contains(c))) {
            throw BizException.of(ErrorCode.ROLE_PERM_NOT_ASSIGNABLE);
        }
        return before == null ? null : before.version();
    }

    private StoreContext selectedStore() {
        var actor = ShopCommerceActorAdapter.current();
        return queries.store(actor, new StoreRef(actor.tenantNo(), BizContext.requireStoreNo()));
    }

    private RoleVO role(String code) {
        return roles.list(BizContext.requireMerchantNo()).stream().filter(r -> r.roleCode().equalsIgnoreCase(code))
                .findFirst().orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND));
    }

    private boolean registered(String code) {
        return registry.registered(Realm.MERCHANT, code);
    }
}
