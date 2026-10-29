package ai.neargo.shop.merchant.api.biz;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizContextFilter;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.auth.StoreRequestScopeValidator;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.merchant.dto.ResourceQuery;
import ai.neargo.shop.merchant.dto.ResourceVO;
import ai.neargo.shop.merchant.dto.SaveResourceReq;
import ai.neargo.shop.merchant.service.ResourceService;
import ai.neargo.shop.spi.user.StoreDirectoryPort;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Current-store authorization protects shared resource maintenance without exposing occupancy credentials. */
@RestController
@Validated
@Profile("api")
@RequestMapping("/biz/resources")
public class BizResourceController {
    private final ResourceService service;
    private final StoreDirectoryPort stores;
    public BizResourceController(ResourceService service, StoreDirectoryPort stores) {
        this.service = service;
        this.stores = stores;
    }
    /** Internal personnel data is not a publicly enumerable staff directory. */
    @GetMapping
    @PreAuthorize("@perm.canBiz('biz:store:admin')")
    public PageData<ResourceVO> listResources(@Valid @ModelAttribute ResourceQuery query,
            @RequestHeader(value = BizContextFilter.STORE_HEADER, required = false) String requestedStore) {
        requireStore(requestedStore);
        return service.listResources(BizContext.requireMerchantNo(), BizContext.requireStoreNo(),
                SecurityUtils.currentUserNo(), query);
    }
    /** Physical resource maintenance cannot create a second staff identity or move existing commitments. */
    @PostMapping
    @PreAuthorize("@perm.canBiz('biz:store:admin')")
    public ResourceVO saveResource(@Valid @RequestBody SaveResourceReq request,
            @RequestHeader("Idempotency-Key") String key,
            @RequestHeader(value = BizContextFilter.STORE_HEADER, required = false) String requestedStore) {
        requireStore(requestedStore);
        return service.saveResource(BizContext.requireMerchantNo(), BizContext.requireStoreNo(),
                SecurityUtils.currentUserNo(), request, key);
    }
    private void requireStore(String requestedStore) {
        var context = BizContext.current();
        String entity = BizContext.requireMerchantNo();
        String store = BizContext.requireStoreNo();
        var card = stores.cards(List.of(store)).get(store);
        if (card == null) { throw BizException.of(ErrorCode.NOT_FOUND); }
        StoreRequestScopeValidator.requireStoreTarget(entity, store, card.entityNo(), card.storeNo(),
                requestedStore, context.storeNos());
    }
}
