package ai.neargo.shop.payclient.impl;
import ai.neargo.shop.pay.client.*;
import ai.neargo.shop.payclient.*;
import ai.neargo.shop.spi.storedvalue.StoredValueWallets.*;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
@Service @ConditionalOnProperty(name="shop.pay.deployment",havingValue="standalone",matchIfMissing=false)
public class RemoteStoredValueWalletAppService implements StoredValueWalletAppService {
    private final PayInternalApi pay;
    public RemoteStoredValueWalletAppService(PayInternalApi pay){this.pay=pay;}
    public List<AvailableAccount> accounts(Query query){return RemoteEntitlementCalls.call(()->pay.svWalletAccounts(query));}
    public ai.neargo.shop.common.CursorPage<ai.neargo.shop.spi.storedvalue.StoredValueMerchantViews.Record> records(PayStoredValueWalletRequests.Records request){return RemoteEntitlementCalls.call(()->pay.svWalletRecords(request));}
    public Selected select(Query query,String issuerRef,String accountRef){return RemoteEntitlementCalls.call(()->pay.svWalletSelect(new PayStoredValueWalletRequests.Select(query,issuerRef,accountRef)));}
}
