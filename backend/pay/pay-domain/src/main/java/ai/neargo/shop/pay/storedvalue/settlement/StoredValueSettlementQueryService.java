package ai.neargo.shop.pay.storedvalue.settlement;

import ai.neargo.shop.common.CursorPage;
import ai.neargo.shop.spi.storedvalue.StoredValueSettlements.Authority;
import ai.neargo.shop.spi.storedvalue.StoredValueSettlements.Bill;
import ai.neargo.shop.spi.storedvalue.StoredValueSettlements.Event;
import ai.neargo.shop.spi.storedvalue.StoredValueSettlements.Item;
import ai.neargo.shop.spi.storedvalue.StoredValueSettlements.Location;
import ai.neargo.shop.spi.storedvalue.StoredValueSettlements.Payment;
import ai.neargo.shop.spi.storedvalue.StoredValueSettlements.Query;
import ai.neargo.shop.spi.storedvalue.StoredValueSettlements.Resource;

/** Business contract; implementation preserves owner checks, transactions and immutable financial facts. */
public interface StoredValueSettlementQueryService {
    Location locate(Resource c);

    Bill detail(Authority a,String no);

    CursorPage<Bill> bills(Query c);

    CursorPage<Item> items(Authority a,String no,String cursor,int size);

    CursorPage<Event> events(Authority a,String no,String cursor,int size);

    CursorPage<Payment> payments(Authority a,String no,String cursor,int size);
}
