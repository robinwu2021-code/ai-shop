package ai.neargo.shop.spi.storedvalue;
/** Core/merchant facts must be resolved before any Pay transaction. The BFF separately verifies resource permission. */
public interface StoredValueExecutionScopePort {
    StoredValueWallets.ExecutionScope current(String tenant,String actor,String entity,String store,String currency);
}
