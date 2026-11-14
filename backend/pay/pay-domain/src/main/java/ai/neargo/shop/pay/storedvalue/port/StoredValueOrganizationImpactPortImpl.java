package ai.neargo.shop.pay.storedvalue.port;

import ai.neargo.shop.pay.storedvalue.service.StoredValueOrganizationImpactService;
import ai.neargo.shop.spi.storedvalue.StoredValueOrganizationImpactPort;
import org.springframework.stereotype.Component;

/** Keeps the cross-domain organization contract separate from the local transactional query service. */
@Component
public class StoredValueOrganizationImpactPortImpl implements StoredValueOrganizationImpactPort {
    private final StoredValueOrganizationImpactService service;

    public StoredValueOrganizationImpactPortImpl(StoredValueOrganizationImpactService service) {
        this.service = service;
    }

    @Override
    public Impact inspect(String tenant, String entity, String store) {
        return service.inspect(tenant, entity, store);
    }
}
