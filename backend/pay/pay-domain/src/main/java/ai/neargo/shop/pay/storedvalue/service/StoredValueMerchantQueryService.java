package ai.neargo.shop.pay.storedvalue.service;

import ai.neargo.shop.common.CursorPage;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.spi.storedvalue.StoredValueMerchantViews.Account;
import ai.neargo.shop.spi.storedvalue.StoredValueMerchantViews.Record;
import java.time.Instant;
import java.util.List;

public interface StoredValueMerchantQueryService {
    CursorPage<Account> directory(CatalogScope scope,String store,List<String> users,String keywordHash,String cursor,int size);
    Account account(CatalogScope scope,String store,String accountRef);
    CursorPage<Record> records(CatalogScope scope,String store,String type,Instant from,Instant to,String accountRef,String cursor,int size);
    /** Read an already-owned wallet selected by the PAY issuer boundary. */
    CursorPage<Record> walletRecords(CatalogScope scope,String store,String type,Instant from,Instant to,String account,String cursor,int size);
    Record record(CatalogScope scope,String store,String recordNo);
}
