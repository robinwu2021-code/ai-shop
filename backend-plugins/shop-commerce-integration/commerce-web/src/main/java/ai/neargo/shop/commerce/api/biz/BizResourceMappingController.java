package ai.neargo.shop.commerce.api.biz;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.commerce.api.CommerceRequestScope;
import ai.neargo.shop.commerce.config.ConditionalOnCommerceWebEnabled;
import ai.neargo.shop.commerce.dto.MappingContracts.CreateResourceMappingReq;
import ai.neargo.shop.commerce.dto.MappingContracts.MappingRevisionVO;
import ai.neargo.shop.commerce.dto.MappingContracts.ResourceMappingVO;
import ai.neargo.shop.commerce.dto.MappingContracts.ReviseResourceMappingReq;
import ai.neargo.shop.commerce.service.ResourceMappingService;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.spi.commerce.ChannelResourceIdentityGateway.ObjectType;

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

/** 商家只维护映射意图；证明和历史解析走内部合同，不能通过JSON授权导入。 */
@RestController
@Profile("api")
@ConditionalOnCommerceWebEnabled
@Validated
@PreAuthorize("@perm.canBiz('" + BizPerms.STORE_ADMIN + "')")
public class BizResourceMappingController {
    private final ResourceMappingService service;
    private final CommerceRequestScope scope;

    public BizResourceMappingController(
            ResourceMappingService service, CommerceRequestScope scope) {
        this.service = service;
        this.scope = scope;
    }

    @PostMapping("/biz/x/commerce/resource-mappings")
    public ResourceMappingVO createMapping(
            @Valid @RequestBody CreateResourceMappingReq req,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key,
            HttpServletRequest request) {
        CommerceRequestScope.query(request, Set.of());
        String store = BizContext.requireStoreNo();
        return service.createMapping(
                scope.store(store, request), store, SecurityUtils.currentUserNo(), req, key);
    }

    @PutMapping("/biz/x/commerce/resource-mappings/{mappingNo}")
    public ResourceMappingVO reviseMapping(
            @PathVariable("mappingNo") @NotBlank @Size(max = 32) String mappingNo,
            @Valid @RequestBody ReviseResourceMappingReq req,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key,
            HttpServletRequest request) {
        CommerceRequestScope.query(request, Set.of());
        String store = BizContext.requireStoreNo();
        return service.reviseMapping(
                scope.store(store, request),
                store,
                mappingNo,
                SecurityUtils.currentUserNo(),
                req,
                key);
    }

    @GetMapping("/biz/x/commerce/stores/{storeNo}/resource-mappings")
    public PageData<ResourceMappingVO> listMappings(
            @PathVariable("storeNo") @NotBlank @Size(max = 32) String storeNo,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size,
            @RequestParam(value = "connectionNo", required = false) String connectionNo,
            @RequestParam(value = "objectType", required = false) ObjectType objectType,
            @RequestParam(value = "externalRef", required = false) String externalRef,
            @RequestParam(value = "sourceRef", required = false) String sourceRef,
            HttpServletRequest request) {
        CommerceRequestScope.query(
                request,
                Set.of("page", "size", "connectionNo", "objectType", "externalRef", "sourceRef"));
        return service.listMappings(
                scope.store(storeNo, request),
                storeNo,
                page,
                size,
                connectionNo,
                objectType,
                externalRef,
                sourceRef);
    }

    @GetMapping("/biz/x/commerce/resource-mappings/{mappingNo}/revisions")
    public PageData<MappingRevisionVO> listRevisions(
            @PathVariable("mappingNo") @NotBlank @Size(max = 32) String mappingNo,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size,
            HttpServletRequest request) {
        CommerceRequestScope.query(request, Set.of("page", "size"));
        String store = BizContext.requireStoreNo();
        return service.listRevisions(scope.store(store, request), store, mappingNo, page, size);
    }
}
