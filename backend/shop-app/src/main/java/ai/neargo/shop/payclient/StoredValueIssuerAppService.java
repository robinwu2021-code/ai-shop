package ai.neargo.shop.payclient;
import ai.neargo.shop.spi.storedvalue.StoredValueIssuers.*;
public interface StoredValueIssuerAppService {
    ScopeRef locate(String tenant,String issuer);
    ConfigurationImpact check(AuthorizedScope scope,String issuer,Config config);
    StoreOptions storeOptions(StoreQuery query);
    AccessAck refresh(StoreAuthority scope);
    View prepareStore(StoreAuthority scope);
    Lookup find(AuthorizedScope scope);
    View configure(AuthorizedScope scope, Config command, String key);
}
