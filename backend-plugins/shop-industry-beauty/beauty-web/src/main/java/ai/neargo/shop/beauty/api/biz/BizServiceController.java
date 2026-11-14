package ai.neargo.shop.beauty.api.biz;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizContextFilter;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.auth.StoreRequestScopeValidator;
import ai.neargo.shop.beauty.config.ConditionalOnBeautyWebEnabled;
import ai.neargo.shop.beauty.dto.ServiceDetailQuery;
import ai.neargo.shop.beauty.dto.ServiceDetailVO;
import ai.neargo.shop.beauty.service.ServiceCatalogQueryService;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.user.StoreDirectoryPort;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Merchant preview inherits current account/store authority rather than the public link's coordinates. */
@RestController("beautyBizServiceController")
@Validated
@ConditionalOnBeautyWebEnabled
@Profile("api")
public class BizServiceController {
    private final ObjectProvider<ServiceCatalogQueryService> services;
    private final ObjectProvider<StoreDirectoryPort> stores;
    public BizServiceController(ObjectProvider<ServiceCatalogQueryService> services,
            ObjectProvider<StoreDirectoryPort> stores) {
        this.services = services;
        this.stores = stores;
    }
    @GetMapping("/biz/x/beauty/services/{skuNo}")
    @PreAuthorize("@perm.canBiz('biz:store')")
    public ServiceDetailVO getService(@PathVariable("skuNo") String skuNo,
            @Valid @ModelAttribute ServiceDetailQuery query,
            @RequestHeader(value = BizContextFilter.STORE_HEADER, required = false) String requestedStore) {
        var scope = BizContext.current();
        String entity = BizContext.requireMerchantNo();
        String store = BizContext.requireStoreNo();
        var card = stores.getObject().cards(List.of(store)).get(store);
        if (card == null) { throw BizException.of(ErrorCode.NOT_FOUND); }
        StoreRequestScopeValidator.requireStoreTarget(entity, store, card.entityNo(), card.storeNo(),
                requestedStore, scope.storeNos());
        return services.getObject().getService(entity, store, SecurityUtils.currentUserNo(), skuNo, query);
    }
}
