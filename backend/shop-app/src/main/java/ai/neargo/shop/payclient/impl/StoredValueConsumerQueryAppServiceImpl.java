package ai.neargo.shop.payclient.impl;

import ai.neargo.shop.common.CursorPage;
import ai.neargo.shop.pay.client.PayInternalApi;
import ai.neargo.shop.pay.client.PayStoredValueConsumerRequests;
import ai.neargo.shop.pay.storedvalue.service.StoredValueConsumerQueryService;
import ai.neargo.shop.payclient.StoredValueConsumerQueryAppService;
import ai.neargo.shop.payclient.RemoteEntitlementCalls;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.storedvalue.StoredValueConsumerViews.NotificationFact;
import ai.neargo.shop.spi.storedvalue.StoredValueConsumerViews.Record;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.StoreItem;
import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** Uses the deployment-selected Pay boundary without widening consumer ownership. */
@Service
@ConditionalOnProperty(name="shop.pay.deployment",havingValue="embedded",matchIfMissing=true)
public class StoredValueConsumerQueryAppServiceImpl implements StoredValueConsumerQueryAppService {
    private final StoredValueConsumerQueryService reads;
    public StoredValueConsumerQueryAppServiceImpl(StoredValueConsumerQueryService reads) { this.reads=reads; }
    @Override public StoreItem store(EntitlementContext c,String store) {
        return reads.store(c,store);
    }
    @Override public CursorPage<Record> records(EntitlementContext c,String store,String type,Instant from,Instant to,
            String focus,String cursor,int size) {
        return reads.records(c,store,type,from,to,focus,cursor,size);
    }
    @Override public NotificationFact notificationFact(EntitlementContext c,String usageNo) {
        return reads.notificationFact(c,usageNo);
    }
    @Override public CursorPage<ai.neargo.shop.spi.storedvalue.StoredValueConsumerViews.Issuer> issuers(EntitlementContext c,String cursor,int size){return reads.issuers(c,cursor,size);}
    @Override public ai.neargo.shop.spi.storedvalue.StoredValueConsumerViews.Issuer issuer(EntitlementContext c,String ref){return reads.issuer(c,ref);}
    @Override public ai.neargo.shop.spi.storedvalue.StoredValueConsumerViews.Issuer locateIssuer(EntitlementContext c,String document){return reads.locateIssuer(c,document);}
    @Override public CursorPage<Record> issuerRecords(EntitlementContext c,String ref,String type,Instant from,Instant to,String focus,String cursor,int size){return reads.issuerRecords(c,ref,type,from,to,focus,cursor,size);}
}
