package ai.neargo.shop.pay.storedvalue.issuer;

import ai.neargo.shop.common.CursorPage;
import ai.neargo.shop.spi.storedvalue.StoredValueWallets.AvailableAccount;
import ai.neargo.shop.spi.storedvalue.StoredValueWallets.ExecutionScope;
import ai.neargo.shop.spi.storedvalue.StoredValueWallets.Query;
import ai.neargo.shop.spi.storedvalue.StoredValueWallets.Selected;
import java.util.List;

/** Stable PAY business contract; original owner checks and transaction boundaries remain in its implementation. */
public interface StoredValueIssuerQueryService {
    List<AvailableAccount> accounts(Query query);

    ai.neargo.shop.spi.storedvalue.StoredValueIssuers.StoreOptions storeOptions(ExecutionScope scope);

    Selected select(Query query,String issuerRef,String accountRef);

    Selected readSelection(Query query,String issuerRef,String accountRef);

    CursorPage<ai.neargo.shop.spi.storedvalue.StoredValueIssuerRecharges.FundedCustomer> funded(ExecutionScope scope,String cursor,int size);
}
