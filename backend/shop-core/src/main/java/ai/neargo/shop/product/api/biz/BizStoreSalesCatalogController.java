package ai.neargo.shop.product.api.biz;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizContextFilter;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.auth.StoreRequestScopeValidator;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.product.service.StoreSalesCatalogService;
import ai.neargo.shop.spi.product.StoreSalesCatalogPort.PublishReq;
import ai.neargo.shop.spi.product.StoreSalesCatalogPort.Snapshot;
import ai.neargo.shop.spi.user.StoreDirectoryPort;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Retail, food and beauty share one authenticated catalog owner and current-store permission boundary. */
@RestController
@Profile("api")
@RequestMapping("/biz/store-sales-catalog")
public class BizStoreSalesCatalogController {
    private final StoreSalesCatalogService service;
    private final StoreDirectoryPort stores;
    public BizStoreSalesCatalogController(StoreSalesCatalogService service, StoreDirectoryPort stores) {
        this.service = service;
        this.stores = stores;
    }
    @GetMapping
    @PreAuthorize("@perm.canBiz('biz:goods')")
    public Snapshot read(@RequestParam(required = false) OffsetDateTime at,
            @RequestHeader(value = BizContextFilter.STORE_HEADER, required = false) String requestedStore) {
        requireStore(requestedStore);
        return service.read(BizContext.requireMerchantNo(), BizContext.requireStoreNo(),
                at == null ? Instant.now() : at.toInstant());
    }
    @PostMapping("/versions")
    @PreAuthorize("@perm.canBiz('biz:goods')")
    public Snapshot publish(@RequestBody PublishReq request, @RequestHeader("Idempotency-Key") String key,
            @RequestHeader(value = BizContextFilter.STORE_HEADER, required = false) String requestedStore) {
        requireStore(requestedStore);
        return service.publish(BizContext.requireMerchantNo(), BizContext.requireStoreNo(),
                SecurityUtils.currentUserNo(), request, key);
    }
    private void requireStore(String requestedStore) {
        String store = BizContext.requireStoreNo();
        var card = stores.cards(List.of(store)).get(store);
        if (card == null) { throw BizException.of(ErrorCode.NOT_FOUND); }
        StoreRequestScopeValidator.requireStoreTarget(BizContext.requireMerchantNo(), store,
                card.entityNo(), card.storeNo(), requestedStore, BizContext.current().storeNos());
    }
}
