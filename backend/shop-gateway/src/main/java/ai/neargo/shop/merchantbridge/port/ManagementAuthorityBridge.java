package ai.neargo.shop.merchantbridge.port;

import ai.neargo.shop.gateway.biz.ManagementAuthority;
import ai.neargo.shop.merchant.brand.ManagementModels.Actor;
import ai.neargo.shop.merchant.brand.ManagementRepository;
import ai.neargo.shop.merchant.brand.ManagementScopeService;
import java.util.HashSet;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 网关的 {@link ManagementAuthority} 接到商户模块现有的管理范围授权上。
 *
 * <p>只调商户模块已经公开的东西（{@link ManagementScopeService#can}、{@code space}、{@code valid}、{@code permissions}
 * 与 {@link ManagementRepository} 的查询），<b>不改商户模块</b>。「任一资源」的规则与 {@code can} 逐条相同，只是不限定资源号。
 */
@Component
public class ManagementAuthorityBridge implements ManagementAuthority {

    private final ManagementScopeService scopes;
    private final ManagementRepository db;

    public ManagementAuthorityBridge(ManagementScopeService scopes, ManagementRepository db) {
        this.scopes = scopes;
        this.db = db;
    }

    @Override
    public boolean can(String tenantNo, String actorKey, String managementNo, String resourceType, String resourceNo,
                       String permission) {
        return scopes.can(new Actor(tenantNo, actorKey), managementNo, resourceType, resourceNo, permission);
    }

    @Override
    public boolean canAny(String tenantNo, String actorKey, String managementNo, String resourceType,
                          String permission) {
        Actor actor = new Actor(tenantNo, actorKey);
        Map<String, Object> space = scopes.space(actor, managementNo);
        if (space == null || !"ACTIVE".equals(String.valueOf(space.get("status")))
                || !scopes.permissions(resourceType).contains(permission)) {
            return false;
        }
        // 属主的天然权限与 can 相同：空间本身，以及空间下已有的品牌
        if (actorKey.equals(String.valueOf(space.get("owner_actor_no")))) {
            if ("SPACE".equals(resourceType)) {
                return true;
            }
            if ("BRAND".equals(resourceType) && db.one("SELECT brand_no FROM mch_brand WHERE tenant_no=?"
                    + " AND management_no=? AND deleted=0 LIMIT 1", tenantNo, managementNo) != null) {
                return true;
            }
        }
        for (Map<String, Object> grant : db.rows("SELECT * FROM mch_management_grant WHERE tenant_no=?"
                + " AND management_no=? AND actor_no=? AND resource_type=? AND permission_code=? AND deleted=0",
                tenantNo, managementNo, actorKey, resourceType, permission)) {
            if (scopes.valid(actor, grant, new HashSet<>())) {
                return true;
            }
        }
        return false;
    }
}
