package ai.neargo.shop.commerce.api.biz;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.commerce.api.CommerceRequestScope;
import ai.neargo.shop.commerce.config.ConditionalOnCommerceWebEnabled;
import ai.neargo.shop.commerce.dto.CatalogContracts.CatalogScopeVO;
import ai.neargo.shop.commerce.dto.CatalogContracts.CreateCatalogScopeReq;
import ai.neargo.shop.commerce.dto.CatalogContracts.SaveCatalogSourcesReq;
import ai.neargo.shop.commerce.service.CatalogScopeService;
import ai.neargo.shop.common.PageData;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

/** 来源只能来自原业务已核验目录；公开API不接受主体与商品价格。 */
@RestController
@Profile("api")
@ConditionalOnCommerceWebEnabled
@Validated
@PreAuthorize("@perm.canBiz('" + BizPerms.STORE_ADMIN + "')")
public class BizCatalogScopeController {
    private final CatalogScopeService service;
    private final CommerceRequestScope scope;

    public BizCatalogScopeController(CatalogScopeService service, CommerceRequestScope scope) {
        this.service = service;
        this.scope = scope;
    }

    @PostMapping("/biz/x/commerce/stores/{storeNo}/catalog-scopes")
    public CatalogScopeVO createScope(
            @PathVariable("storeNo") @NotBlank @Size(max = 32) String storeNo,
            @Valid @RequestBody CreateCatalogScopeReq req,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key,
            HttpServletRequest request) {
        CommerceRequestScope.query(request, Set.of());
        return service.createScope(
                scope.store(storeNo, request), storeNo, SecurityUtils.currentUserNo(), req, key);
    }

    @GetMapping("/biz/x/commerce/stores/{storeNo}/catalog-scopes")
    public PageData<CatalogScopeVO> listScopes(
            @PathVariable("storeNo") @NotBlank @Size(max = 32) String storeNo,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size,
            @RequestParam(value = "connectionNo", required = false) String connectionNo,
            @RequestParam(value = "status", required = false) String status,
            HttpServletRequest request) {
        CommerceRequestScope.query(request, Set.of("page", "size", "connectionNo", "status"));
        return service.listScopes(
                scope.store(storeNo, request), storeNo, page, size, connectionNo, status);
    }

    @GetMapping("/biz/x/commerce/catalog-scopes/{scopeNo}")
    public CatalogScopeVO getScope(
            @PathVariable("scopeNo") @NotBlank @Size(max = 32) String scopeNo,
            HttpServletRequest request) {
        CommerceRequestScope.query(request, Set.of());
        String store = BizContext.requireStoreNo();
        return service.getScope(scope.store(store, request), store, scopeNo);
    }

    @PutMapping("/biz/x/commerce/catalog-scopes/{scopeNo}")
    public CatalogScopeVO saveSources(
            @PathVariable("scopeNo") @NotBlank @Size(max = 32) String scopeNo,
            @Valid @RequestBody SaveCatalogSourcesReq req,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key,
            HttpServletRequest request) {
        CommerceRequestScope.query(request, Set.of());
        String store = BizContext.requireStoreNo();
        return service.saveSources(
                scope.store(store, request),
                store,
                scopeNo,
                SecurityUtils.currentUserNo(),
                req,
                key);
    }
}
