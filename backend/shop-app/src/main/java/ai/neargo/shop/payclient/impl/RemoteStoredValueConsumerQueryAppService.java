package ai.neargo.shop.payclient.impl;

import ai.neargo.shop.spi.storedvalue.StoredValueConsumerViews;

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
@ConditionalOnProperty(name="shop.pay.deployment",havingValue="standalone")
public class RemoteStoredValueConsumerQueryAppService implements StoredValueConsumerQueryAppService {
    private final PayInternalApi reads;
    public RemoteStoredValueConsumerQueryAppService(PayInternalApi reads) { this.reads=reads; }
    @Override public StoreItem store(EntitlementContext c,String store) {
        return RemoteEntitlementCalls.call(()->reads.svConsumerStore(new PayStoredValueConsumerRequests.Store(c,store)));
    }
    @Override public CursorPage<Record> records(EntitlementContext c,String store,String type,Instant from,Instant to,
            String focus,String cursor,int size) {
        return RemoteEntitlementCalls.call(()->reads.svConsumerRecords(new PayStoredValueConsumerRequests.Records(c,store,type,from,to,focus,cursor,size)));
    }
    @Override public NotificationFact notificationFact(EntitlementContext c,String usageNo) {
        return RemoteEntitlementCalls.call(()->reads.svNotificationFact(new PayStoredValueConsumerRequests.Fact(c,usageNo)));
    }
    @Override public CursorPage<StoredValueConsumerViews.Issuer> issuers(EntitlementContext c,String cursor,int size){return RemoteEntitlementCalls.call(()->reads.svConsumerIssuers(new PayStoredValueConsumerRequests.Issuers(c,cursor,size)));}
    @Override public StoredValueConsumerViews.Issuer issuer(EntitlementContext c,String ref){return RemoteEntitlementCalls.call(()->reads.svConsumerIssuer(new PayStoredValueConsumerRequests.Issuer(c,ref)));}
    @Override public StoredValueConsumerViews.Issuer locateIssuer(EntitlementContext c,String document){return RemoteEntitlementCalls.call(()->reads.svConsumerLocateIssuer(new PayStoredValueConsumerRequests.Locate(c,document)));}
    @Override public CursorPage<Record> issuerRecords(EntitlementContext c,String ref,String type,Instant from,Instant to,String focus,String cursor,int size){return RemoteEntitlementCalls.call(()->reads.svConsumerIssuerRecords(new PayStoredValueConsumerRequests.IssuerRecords(c,ref,type,from,to,focus,cursor,size)));}
}
