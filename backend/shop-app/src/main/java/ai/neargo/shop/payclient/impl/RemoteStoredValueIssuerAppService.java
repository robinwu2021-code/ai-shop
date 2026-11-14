package ai.neargo.shop.payclient.impl;
import ai.neargo.shop.payclient.*;
import ai.neargo.shop.spi.storedvalue.StoredValueIssuers.*;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
@Service
@ConditionalOnProperty(name="shop.pay.deployment",havingValue="standalone",matchIfMissing=false)
public class RemoteStoredValueIssuerAppService implements StoredValueIssuerAppService {
    private final ai.neargo.shop.pay.client.PayInternalApi target;
    public RemoteStoredValueIssuerAppService(ai.neargo.shop.pay.client.PayInternalApi target) { this.target=target; }

    public ScopeRef locate(String tenant,String issuer){return RemoteEntitlementCalls.call(()->target.svIssuerLocate(new ai.neargo.shop.pay.client.PayStoredValueIssuerRequests.Locate(tenant,issuer)));}
    public ConfigurationImpact check(AuthorizedScope scope,String issuer,Config config){return RemoteEntitlementCalls.call(()->target.svIssuerCheck(new ai.neargo.shop.pay.client.PayStoredValueIssuerRequests.Check(scope,issuer,config)));}
    public StoreOptions storeOptions(StoreQuery query){return RemoteEntitlementCalls.call(()->target.svIssuerOptions(new ai.neargo.shop.pay.client.PayStoredValueIssuerRequests.Options(query)));}
    public AccessAck refresh(StoreAuthority scope){return RemoteEntitlementCalls.call(()->target.svIssuerRefresh(new ai.neargo.shop.pay.client.PayStoredValueIssuerRequests.PrepareStore(scope)));}
    public View prepareStore(StoreAuthority scope) { return RemoteEntitlementCalls.call(()->target.svIssuerPrepareStore(new ai.neargo.shop.pay.client.PayStoredValueIssuerRequests.PrepareStore(scope))); }
    public Lookup find(AuthorizedScope scope) { return RemoteEntitlementCalls.call(()->target.svIssuerFind(new ai.neargo.shop.pay.client.PayStoredValueIssuerRequests.Find(scope))); }
    public View configure(AuthorizedScope scope,Config command,String key) { return RemoteEntitlementCalls.call(()->target.svIssuerConfigure(new ai.neargo.shop.pay.client.PayStoredValueIssuerRequests.Configure(scope,command,key))); }
}
