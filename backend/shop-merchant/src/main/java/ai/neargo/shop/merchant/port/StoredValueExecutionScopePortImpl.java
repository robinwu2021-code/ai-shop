package ai.neargo.shop.merchant.port;

import ai.neargo.shop.merchant.brand.StoredValueExecutionScopeService;
import ai.neargo.shop.spi.storedvalue.StoredValueExecutionScopePort;
import ai.neargo.shop.spi.storedvalue.StoredValueWallets.ExecutionScope;
import org.springframework.stereotype.Component;

/** Keeps cross-domain organization fact transport separate from its local business implementation. */
@Component
public class StoredValueExecutionScopePortImpl implements StoredValueExecutionScopePort {
    private final StoredValueExecutionScopeService service;

    public StoredValueExecutionScopePortImpl(StoredValueExecutionScopeService service) {
        this.service = service;
    }

    @Override
    public ExecutionScope current(String tenant,String actor,String entity,String store,String currency) {
        return service.current(tenant,actor,entity,store,currency);
    }
}
