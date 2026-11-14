package ai.neargo.shop.commerce.api.biz;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.commerce.api.CommerceRequestScope;
import ai.neargo.shop.commerce.config.ConditionalOnCommerceWebEnabled;
import ai.neargo.shop.commerce.dto.CatalogContracts.CatalogPreviewVO;
import ai.neargo.shop.commerce.dto.CatalogContracts.PreviewCatalogReq;
import ai.neargo.shop.commerce.service.CatalogPublicationService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

/** 预览冻结原业务证据，客户端只能引用配置版本，不能覆盖金额和库存。 */
@RestController
@Profile("api")
@ConditionalOnCommerceWebEnabled
@Validated
@PreAuthorize("@perm.canBiz('" + BizPerms.STORE_ADMIN + "')")
public class BizCatalogPreviewController {
    private final CatalogPublicationService service;
    private final CommerceRequestScope scope;

    public BizCatalogPreviewController(
            CatalogPublicationService service, CommerceRequestScope scope) {
        this.service = service;
        this.scope = scope;
    }

    @PostMapping("/biz/x/commerce/catalog-scopes/{scopeNo}:preview")
    public CatalogPreviewVO preview(
            @PathVariable("scopeNo") @NotBlank @Size(max = 32) String scopeNo,
            @Valid @RequestBody PreviewCatalogReq req,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key,
            HttpServletRequest request) {
        CommerceRequestScope.query(request, Set.of());
        String store = BizContext.requireStoreNo();
        return service.preview(
                scope.store(store, request),
                store,
                scopeNo,
                SecurityUtils.currentUserNo(),
                req,
                key);
    }
}
