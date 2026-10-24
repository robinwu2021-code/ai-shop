package ai.neargo.shop.pay.storedvalue.issuer;

import ai.neargo.shop.spi.storedvalue.StoredValueIssuers.AuthorizedScope;
import ai.neargo.shop.spi.storedvalue.StoredValueIssuers.CollectionRoute;
import ai.neargo.shop.spi.storedvalue.StoredValueIssuers.Config;
import ai.neargo.shop.spi.storedvalue.StoredValueIssuers.ConfigurationImpact;
import ai.neargo.shop.spi.storedvalue.StoredValueIssuers.ScopeRef;
import ai.neargo.shop.spi.storedvalue.StoredValueIssuers.StoreOptions;
import ai.neargo.shop.spi.storedvalue.StoredValueIssuers.StoreQuery;
import ai.neargo.shop.spi.storedvalue.StoredValueIssuers.View;
import java.util.Optional;






/** Stable business API; implementation keeps the original transaction and owner checks. */
public interface StoredValueIssuerService {
    ScopeRef locate(String tenant,String no);

    ConfigurationImpact check(AuthorizedScope scope,String issuerNo,Config command);

    StoreOptions storeOptions(StoreQuery query);

    Optional<View> find(AuthorizedScope scope);

    View configure(AuthorizedScope scope, Config command, String key);

    CollectionRoute collectionRoute(AuthorizedScope scope, String issuerNo, String channel, String scene, String store);

    View requireIssuable(AuthorizedScope scope, String issuerNo);
}
