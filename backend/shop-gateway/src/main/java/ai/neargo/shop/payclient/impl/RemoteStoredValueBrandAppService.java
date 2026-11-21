package ai.neargo.shop.payclient.impl;
import ai.neargo.shop.pay.client.PayInternalApi;
import ai.neargo.shop.payclient.*;
import ai.neargo.shop.spi.storedvalue.StoredValueBrandActivation.*;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
@Service @ConditionalOnProperty(name="shop.pay.deployment",havingValue="standalone",matchIfMissing=false)
public class RemoteStoredValueBrandAppService implements StoredValueBrandAppService {
    private final PayInternalApi pay;public RemoteStoredValueBrandAppService(PayInternalApi pay){this.pay=pay;}
    public Capability capability(){return RemoteEntitlementCalls.call(pay::svBrandCapability);}
    public Ack activate(Prepare command){return RemoteEntitlementCalls.call(()->pay.svBrandActivate(command));}
}
