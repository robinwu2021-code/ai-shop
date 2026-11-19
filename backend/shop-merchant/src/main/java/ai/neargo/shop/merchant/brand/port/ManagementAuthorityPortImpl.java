package ai.neargo.shop.merchant.brand.port;

import ai.neargo.shop.merchant.brand.ManagementModels.Actor;
import ai.neargo.shop.merchant.brand.ManagementScopeService;
import ai.neargo.shop.spi.merchant.ManagementAuthorityPort;
import org.springframework.stereotype.Component;

/** {@link ManagementAuthorityPort} 的实现：原样转给 {@link ManagementScopeService}，不加任何规则。 */
@Component
public class ManagementAuthorityPortImpl implements ManagementAuthorityPort {

    private final ManagementScopeService scopes;

    public ManagementAuthorityPortImpl(ManagementScopeService scopes) {
        this.scopes = scopes;
    }

    @Override
    public boolean can(String tenantNo, String actorKey, String managementNo, String resourceType, String resourceNo,
                       String permission) {
        return scopes.can(new Actor(tenantNo, actorKey), managementNo, resourceType, resourceNo, permission);
    }

    @Override
    public boolean canAny(String tenantNo, String actorKey, String managementNo, String resourceType,
                          String permission) {
        return scopes.canAny(new Actor(tenantNo, actorKey), managementNo, resourceType, permission);
    }
}
