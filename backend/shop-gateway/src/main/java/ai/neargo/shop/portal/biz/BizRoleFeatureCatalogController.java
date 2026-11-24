package ai.neargo.shop.portal.biz;

import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.portal.biz.feature.RoleFeatureAccess;
import ai.neargo.shop.portal.biz.feature.RoleFeatureCatalog.Directory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Complete catalog for a role in the selected store, including inactive choices. */
@Profile("api")
@RestController
@ConditionalOnProperty(name = "commerce.enabled", havingValue = "true")
public class BizRoleFeatureCatalogController {
    private final RoleFeatureAccess roleFeatures;

    public BizRoleFeatureCatalogController(RoleFeatureAccess roleFeatures) {
        this.roleFeatures = roleFeatures;
    }

    /** Administrators see active grants and the reason any other grant is disabled. */
    @PreAuthorize("@perm.canBiz('" + BizPerms.STORE_ADMIN + "')")
    @GetMapping("/biz/role-feature-catalog")
    public Directory directory(@RequestParam(required = false) String roleCode) {
        return roleFeatures.directory(roleCode);
    }
}
