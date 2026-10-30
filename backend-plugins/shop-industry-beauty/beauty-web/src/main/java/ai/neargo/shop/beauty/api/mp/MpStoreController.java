package ai.neargo.shop.beauty.api.mp;

import ai.neargo.shop.beauty.dto.PublicStoreVO;
import ai.neargo.shop.beauty.service.ServiceCatalogQueryService;
import ai.neargo.shop.beauty.config.ConditionalOnBeautyWebEnabled;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Public read-only catalog; owner resolution happens in the service, never from identity headers. */
@RestController("beautyMpStoreController")
@Validated
@ConditionalOnBeautyWebEnabled
@Profile("api")
public class MpStoreController {
    private final ObjectProvider<ServiceCatalogQueryService> services;
    public MpStoreController(ObjectProvider<ServiceCatalogQueryService> services) { this.services = services; }
    @GetMapping("/mp/x/beauty/stores/{storeNo}")
    public PublicStoreVO getPublicStore(@PathVariable("storeNo") String storeNo) {
        return services.getObject().getPublicStore(storeNo);
    }
}
