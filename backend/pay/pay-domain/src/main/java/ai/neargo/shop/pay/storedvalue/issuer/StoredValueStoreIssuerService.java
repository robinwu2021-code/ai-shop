package ai.neargo.shop.pay.storedvalue.issuer;

import ai.neargo.shop.spi.storedvalue.StoredValueIssuers.AccessAck;
import ai.neargo.shop.spi.storedvalue.StoredValueIssuers.StoreAuthority;
import ai.neargo.shop.spi.storedvalue.StoredValueIssuers.View;






/** Stable business API; implementation keeps the original transaction and owner checks. */
public interface StoredValueStoreIssuerService {
    View prepare(StoreAuthority scope);

    View refresh(StoreAuthority scope);

    AccessAck refreshAck(StoreAuthority scope);
}
