package ai.neargo.shop.payclient;
import ai.neargo.shop.spi.storedvalue.StoredValueWallets.*;
import java.util.List;
public interface StoredValueWalletAppService {
    List<AvailableAccount> accounts(Query query);
    ai.neargo.shop.common.CursorPage<ai.neargo.shop.spi.storedvalue.StoredValueMerchantViews.Record> records(ai.neargo.shop.pay.client.PayStoredValueWalletRequests.Records request);
    Selected select(Query query,String issuerRef,String accountRef);
}
