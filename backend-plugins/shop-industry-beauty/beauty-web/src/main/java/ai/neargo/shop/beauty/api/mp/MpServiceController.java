package ai.neargo.shop.beauty.api.mp;

import ai.neargo.shop.beauty.dto.PublicServiceVO;
import ai.neargo.shop.beauty.service.ServiceCatalogQueryService;
import ai.neargo.shop.beauty.config.ConditionalOnBeautyWebEnabled;
import ai.neargo.shop.beauty.dto.ServiceDetailQuery;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Public read-only catalog; owner resolution happens in the service, never from identity headers. */
@RestController("beautyMpServiceController")
@Validated
@ConditionalOnBeautyWebEnabled
@Profile("api")
public class MpServiceController {
    private final ObjectProvider<ServiceCatalogQueryService> services;
    public MpServiceController(ObjectProvider<ServiceCatalogQueryService> services) { this.services = services; }
    @GetMapping("/mp/x/beauty/services/{skuNo}")
    public PublicServiceVO getPublicService(@PathVariable("skuNo") String skuNo,
            @Valid @ModelAttribute ServiceDetailQuery query) {
        return services.getObject().getPublicService(skuNo, query);
    }
}
