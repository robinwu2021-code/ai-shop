package ai.neargo.shop.commerce.api.biz;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.commerce.api.CommerceRequestScope;
import ai.neargo.shop.commerce.config.ConditionalOnCommerceWebEnabled;
import ai.neargo.shop.commerce.dto.CatalogContracts.SaveSyncPolicyReq;
import ai.neargo.shop.commerce.dto.CatalogContracts.SyncPolicyVO;
import ai.neargo.shop.commerce.service.SyncPolicyService;

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
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

/** 自动策略沿当前门店权限维护；未获准的库存触发不伪装成已支持。 */
@RestController
@Profile("api")
@ConditionalOnCommerceWebEnabled
@Validated
@PreAuthorize("@perm.canBiz('" + BizPerms.STORE_ADMIN + "')")
public class BizSyncPolicyController {
    private final SyncPolicyService service;
    private final CommerceRequestScope scope;

    public BizSyncPolicyController(SyncPolicyService service, CommerceRequestScope scope) {
        this.service = service;
        this.scope = scope;
    }

    @GetMapping("/biz/x/commerce/catalog-scopes/{scopeNo}/sync-policy")
    public SyncPolicyVO getPolicy(
            @PathVariable("scopeNo") @NotBlank @Size(max = 32) String scopeNo,
            HttpServletRequest request) {
        CommerceRequestScope.query(request, Set.of());
        String store = BizContext.requireStoreNo();
        return service.getPolicy(scope.store(store, request), store, scopeNo);
    }

    @PutMapping("/biz/x/commerce/catalog-scopes/{scopeNo}/sync-policy")
    public SyncPolicyVO savePolicy(
            @PathVariable("scopeNo") @NotBlank @Size(max = 32) String scopeNo,
            @Valid @RequestBody SaveSyncPolicyReq req,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key,
            HttpServletRequest request) {
        CommerceRequestScope.query(request, Set.of());
        String store = BizContext.requireStoreNo();
        return service.savePolicy(
                scope.store(store, request),
                store,
                scopeNo,
                SecurityUtils.currentUserNo(),
                req,
                key);
    }
}
