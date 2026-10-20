package ai.neargo.shop.spi.storedvalue;

import java.time.Instant;

/** Internal read views: the merchant BFF enriches masked profiles and removes raw user numbers. */
public final class StoredValueMerchantViews {
    private StoredValueMerchantViews() { }
    public record Account(String accountRef, String userNo, String currency, long balanceMinor,
            long heldMinor, long availableMinor, Instant lastOccurredAt,String customerNo,Instant lastRechargeAt) {
        public Account(String ref,String user,String currency,long balance,long held,long available,Instant last,String customer){this(ref,user,currency,balance,held,available,last,customer,null);}
        public Account(String ref,String user,String currency,long balance,long held,long available,Instant last){this(ref,user,currency,balance,held,available,last,user);}
        public String identityNo(){return customerNo==null?userNo:customerNo;}
    }
    public record Record(String recordNo, String userNo, String type, String title, String storeNo,
            String storeName, String currency, long signedAmountMinor, long balanceAfterMinor,
            Instant occurredAt, String businessNo, String planNo, String versionNo,
            Long paidAmountMinor, Long bonusAmountMinor,String customerNo) {
        public Record(String record,String user,String type,String title,String store,String storeName,String currency,long signed,long after,Instant at,String business,String plan,String version,Long paid,Long bonus){this(record,user,type,title,store,storeName,currency,signed,after,at,business,plan,version,paid,bonus,user);}
        public String identityNo(){return customerNo==null?userNo:customerNo;}
    }
}
