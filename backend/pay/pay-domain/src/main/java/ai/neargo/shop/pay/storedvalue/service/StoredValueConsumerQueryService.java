package ai.neargo.shop.pay.storedvalue.service;

import ai.neargo.shop.common.CursorPage;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.storedvalue.StoredValueConsumerViews.NotificationFact;
import ai.neargo.shop.spi.storedvalue.StoredValueConsumerViews.Record;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.StoreItem;
import java.time.Instant;

/** Trusted member ownership is checked in Pay for both embedded and remote deployments. */
public interface StoredValueConsumerQueryService {
    StoreItem store(EntitlementContext context, String storeNo);
    CursorPage<Record> records(EntitlementContext context, String storeNo, String type, Instant from,
                              Instant to, String focusDocumentNo, String cursor, int size);
    NotificationFact notificationFact(EntitlementContext context, String usageNo);
    CursorPage<ai.neargo.shop.spi.storedvalue.StoredValueConsumerViews.Issuer> issuers(EntitlementContext context,String cursor,int size);
    ai.neargo.shop.spi.storedvalue.StoredValueConsumerViews.Issuer issuer(EntitlementContext context,String issuerRef);
    ai.neargo.shop.spi.storedvalue.StoredValueConsumerViews.Issuer locateIssuer(EntitlementContext context,String documentNo);
    CursorPage<Record> issuerRecords(EntitlementContext context,String issuerRef,String type,Instant from,Instant to,String focus,String cursor,int size);
}
