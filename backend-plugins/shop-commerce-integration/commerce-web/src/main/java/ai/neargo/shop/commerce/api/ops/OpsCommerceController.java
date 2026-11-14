package ai.neargo.shop.commerce.api.ops;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.commerce.api.CommerceOperatorScope;
import ai.neargo.shop.commerce.api.CommerceRequestScope;
import ai.neargo.shop.commerce.config.ConditionalOnCommerceWebEnabled;
import ai.neargo.shop.commerce.dto.ChannelStatisticsContracts;
import ai.neargo.shop.commerce.dto.OperationVO;
import ai.neargo.shop.commerce.dto.ProductProfileVO;
import ai.neargo.shop.commerce.dto.SaveProductProfileReq;
import ai.neargo.shop.commerce.service.CommerceSupportService;
import ai.neargo.shop.commerce.service.ProviderService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

/**
 * Platform operations provide scoped support and protocol configuration; merchant menus and
 * operating policies remain merchant-owned.
 */
@RestController
@Profile("ops")
@ConditionalOnCommerceWebEnabled
@Validated
public class OpsCommerceController {
    private final CommerceSupportService support;
    private final ProviderService profiles;
    private final CommerceOperatorScope scope;

    public OpsCommerceController(
            CommerceSupportService support, ProviderService profiles, CommerceOperatorScope scope) {
        this.support = support;
        this.profiles = profiles;
        this.scope = scope;
    }

    @GetMapping("/ops/x/commerce/support/health")
    @PreAuthorize("@perm.can('" + Perms.SYSTEM_COMMERCE_READ + "')")
    public ChannelStatisticsContracts.Health health(
            @RequestParam @NotBlank @Size(max = 32) String entityNo,
            @RequestParam @NotBlank @Size(max = 32) String storeNo,
            HttpServletRequest request) {
        CommerceRequestScope.query(request, Set.of("entityNo", "storeNo"));
        return support.supportHealth(scope.entity(entityNo, storeNo), storeNo);
    }

    @GetMapping("/ops/x/commerce/support/operations/{operationNo}")
    @PreAuthorize("@perm.can('" + Perms.SYSTEM_COMMERCE_READ + "')")
    public OperationVO operation(
            @PathVariable @NotBlank @Size(max = 32) String operationNo,
            @RequestParam @NotBlank @Size(max = 32) String entityNo,
            @RequestParam(required = false) @Size(max = 32) String storeNo,
            HttpServletRequest request) {
        CommerceRequestScope.query(request, Set.of("entityNo", "storeNo"));
        return support.supportOperation(scope.entity(entityNo, storeNo), storeNo, operationNo);
    }

    @PutMapping("/ops/x/commerce/product-profiles/{profileNo}")
    @PreAuthorize("@perm.can('" + Perms.SYSTEM_COMMERCE_UPDATE + "')")
    public ProductProfileVO profile(
            @PathVariable @NotBlank @Size(max = 32) String profileNo,
            @Valid @RequestBody SaveProductProfileReq body,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key,
            HttpServletRequest request) {
        CommerceRequestScope.query(request, Set.of());
        return profiles.saveProfile(profileNo, scope.platform(), body, key);
    }
}
