package ai.neargo.shop.payclient.impl;

import ai.neargo.shop.common.CursorPage;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.client.PayInternalApi;
import ai.neargo.shop.pay.client.PayStoredValueMerchantRequests;
import ai.neargo.shop.pay.storedvalue.service.StoredValueMerchantQueryService;
import ai.neargo.shop.payclient.StoredValueMerchantQueryAppService;
import ai.neargo.shop.payclient.RemoteEntitlementCalls;
import ai.neargo.shop.spi.storedvalue.StoredValueMerchantViews.Account;
import ai.neargo.shop.spi.storedvalue.StoredValueMerchantViews.Record;
import java.time.Instant;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name="shop.pay.deployment",havingValue="embedded",matchIfMissing=true)
public class StoredValueMerchantQueryAppServiceImpl implements StoredValueMerchantQueryAppService {
    private final StoredValueMerchantQueryService reads;
    public StoredValueMerchantQueryAppServiceImpl(StoredValueMerchantQueryService reads){this.reads=reads;}
    @Override public CursorPage<Account> directory(CatalogScope s,String store,List<String> users,String hash,String cursor,int size){return reads.directory(s,store,users,hash,cursor,size);}
    @Override public Account account(CatalogScope s,String store,String ref){return reads.account(s,store,ref);}
    @Override public CursorPage<Record> records(CatalogScope s,String store,String type,Instant from,Instant to,String ref,String cursor,int size){return reads.records(s,store,type,from,to,ref,cursor,size);}
    @Override public Record record(CatalogScope s,String store,String no){return reads.record(s,store,no);}
}
