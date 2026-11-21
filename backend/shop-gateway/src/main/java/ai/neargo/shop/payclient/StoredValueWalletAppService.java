package ai.neargo.shop.payclient;

import ai.neargo.shop.common.CursorPage;
import ai.neargo.shop.pay.client.PayStoredValueWalletRequests;
import ai.neargo.shop.spi.storedvalue.StoredValueMerchantViews;
import ai.neargo.shop.spi.storedvalue.StoredValueWallets.*;
import java.util.List;
public interface StoredValueWalletAppService {
    List<AvailableAccount> accounts(Query query);
    CursorPage<StoredValueMerchantViews.Record> records(PayStoredValueWalletRequests.Records request);
    Selected select(Query query,String issuerRef,String accountRef);
}
