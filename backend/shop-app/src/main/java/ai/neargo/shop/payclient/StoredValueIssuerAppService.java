package ai.neargo.shop.payclient;
import ai.neargo.shop.spi.storedvalue.StoredValueIssuers.*;
public interface StoredValueIssuerAppService {
    View prepareStore(StoreAuthority scope);
    Lookup find(AuthorizedScope scope);
    View configure(AuthorizedScope scope, Config command, String key);
}
