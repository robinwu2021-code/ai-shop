package ai.neargo.shop.pay.client;

import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import java.time.Instant;
import java.util.List;

public final class PayStoredValueMerchantRequests {
    private PayStoredValueMerchantRequests() { }
    public record Directory(CatalogScope scope,String storeNo,List<String> users,String keywordHash,String cursor,int size) { }
    public record Account(CatalogScope scope,String storeNo,String accountRef) { }
    public record Records(CatalogScope scope,String storeNo,String type,Instant from,Instant to,String accountRef,String cursor,int size) { }
    public record Record(CatalogScope scope,String storeNo,String recordNo) { }
}
