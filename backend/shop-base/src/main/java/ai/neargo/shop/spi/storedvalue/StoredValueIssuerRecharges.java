package ai.neargo.shop.spi.storedvalue;

import ai.neargo.shop.spi.entitlement.ContractChecks;
import ai.neargo.shop.spi.storedvalue.StoredValueIssuers.Route;
import ai.neargo.shop.spi.storedvalue.StoredValueWallets.Query;
import java.util.List;

/** Brand funding freezes actual collection and liability, separate from the execution store. */
public final class StoredValueIssuerRecharges {
    private StoredValueIssuerRecharges() { }
    public record FundedCustomer(String customerNo,java.time.Instant lastRechargeAt){}
    public record FundedPage(StoredValueWallets.ExecutionScope scope,String cursor,int size){}
    public record Option(String optionRef,String activityName,String currency,long paidAmountMinor,long bonusAmountMinor,long totalAmountMinor){ }
    public record Replay(String storeNo,String optionHash,String customerReferenceHash){public Replay{ContractChecks.id(storeNo);ContractChecks.hash(optionHash);ContractChecks.hash(customerReferenceHash);}}
    public record Create(Query query,String optionRef,String customerReferenceHash,String storeName){public Create{ContractChecks.present(query);ContractChecks.require(optionRef!=null&&!optionRef.isBlank()&&optionRef.length()<=2048&&storeName!=null&&!storeName.isBlank()&&storeName.length()<=100);ContractChecks.hash(customerReferenceHash);}}
    public record Funding(String rechargeNo,String userNo,String customerNo,String operatingEntityNo,String storeNo,String currency,
            String issuerNo,String issuerVersionNo,String releaseNo,String tierNo,String activityNo,String activityVersionNo,
            String activityName,long paidAmountMinor,long bonusAmountMinor,String collectionEntityNo,String settlementEntityNo,Route collectionRoute,String sourceBindingNo,long sourceRevision){
        public Funding{for(String id:List.of(rechargeNo,customerNo,operatingEntityNo,storeNo,issuerNo,issuerVersionNo,releaseNo,tierNo,activityNo,activityVersionNo,collectionEntityNo,settlementEntityNo,sourceBindingNo))ContractChecks.id(id);ContractChecks.optionalId(userNo);ContractChecks.currency(currency);ContractChecks.require(sourceRevision>=0);ContractChecks.require(paidAmountMinor>0&&bonusAmountMinor>=0&&activityName!=null&&!activityName.isBlank());ContractChecks.addAmounts(paidAmountMinor,bonusAmountMinor);ContractChecks.present(collectionRoute);}
    }
}
