package ai.neargo.shop.spi.entitlement;

import ai.neargo.shop.spi.entitlement.ServiceWorkOrderUseFactsPort.Fact;
import ai.neargo.shop.spi.entitlement.ServiceWorkOrderUsePort.Lookup;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

public interface ServiceWorkOrderUseFactsInternalApi {
    @PostExchange(ServiceWorkOrderUseInternalApi.FACTS)
    Fact inspect(@RequestBody Lookup lookup);
}
