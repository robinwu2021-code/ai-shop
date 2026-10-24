package ai.neargo.shop.pay.storedvalue.issuer;

import ai.neargo.shop.common.CursorPage;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.storedvalue.StoredValueConsumerViews.Issuer;
import ai.neargo.shop.spi.storedvalue.StoredValueConsumerViews.Record;
import java.time.Instant;

/** Stable PAY business contract; original owner checks and transaction boundaries remain in its implementation. */
public interface StoredValueConsumerIssuerService {
    CursorPage<Issuer> directory(EntitlementContext c,String cursor,int size);

    Issuer issuer(EntitlementContext c,String issuerRef);

    CursorPage<Record> records(EntitlementContext c,String issuerRef,String type,Instant from,Instant to,String focus,String cursor,int size);

    Issuer locate(EntitlementContext c,String documentNo);
}
