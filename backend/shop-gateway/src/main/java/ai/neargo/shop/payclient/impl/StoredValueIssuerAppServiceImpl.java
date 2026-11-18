package ai.neargo.shop.payclient.impl;
import ai.neargo.shop.payclient.*;
import ai.neargo.shop.spi.storedvalue.StoredValueIssuers.*;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
@Service
@ConditionalOnProperty(name="shop.pay.deployment",havingValue="embedded",matchIfMissing=true)
public class StoredValueIssuerAppServiceImpl implements StoredValueIssuerAppService {
    private final ai.neargo.shop.pay.storedvalue.issuer.StoredValueIssuerService target;
    private final ai.neargo.shop.pay.storedvalue.issuer.StoredValueStoreIssuerService stores;
    public ScopeRef locate(String tenant,String issuer){return target.locate(tenant,issuer);}
    public ConfigurationImpact check(AuthorizedScope scope,String issuer,Config config){return target.check(scope,issuer,config);}
    public StoreOptions storeOptions(StoreQuery query){return target.storeOptions(query);}
    public AccessAck refresh(StoreAuthority scope){return stores.refreshAck(scope);}
    public View prepareStore(StoreAuthority scope) { return stores.prepare(scope); }
    public StoredValueIssuerAppServiceImpl(ai.neargo.shop.pay.storedvalue.issuer.StoredValueIssuerService target,ai.neargo.shop.pay.storedvalue.issuer.StoredValueStoreIssuerService stores) { this.target=target;this.stores=stores; }
    public Lookup find(AuthorizedScope scope) { return new Lookup(target.find(scope).orElse(null)); }
    public View configure(AuthorizedScope scope,Config command,String key) { return target.configure(scope,command,key); }
}
