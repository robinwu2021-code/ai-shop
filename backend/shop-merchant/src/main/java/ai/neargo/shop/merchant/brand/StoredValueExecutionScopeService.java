package ai.neargo.shop.merchant.brand;

import ai.neargo.shop.spi.storedvalue.StoredValueWallets.ExecutionScope;

/** Original P5 business contract; implementation retains its authorization and transaction boundaries. */
public interface StoredValueExecutionScopeService {
    /** Resolve current live organization facts after the caller has established resource authority. */
    ExecutionScope current(String tenant,String actor,String entity,String store,String currency);
}
