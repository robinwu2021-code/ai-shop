package ai.neargo.shop.pay.storedvalue.report;

import ai.neargo.shop.common.CursorPage;
import ai.neargo.shop.spi.storedvalue.StoredValueReports.ActivityAttribution;
import ai.neargo.shop.spi.storedvalue.StoredValueReports.Period;
import ai.neargo.shop.spi.storedvalue.StoredValueReports.Query;
import ai.neargo.shop.spi.storedvalue.StoredValueReports.Scope;
import ai.neargo.shop.spi.storedvalue.StoredValueReports.StoreAttribution;
import ai.neargo.shop.spi.storedvalue.StoredValueReports.Summary;

/** Business contract; implementation preserves owner checks, transactions and immutable financial facts. */
public interface StoredValueSummaryService {
    Summary summarize(Scope scope,Period period);

    CursorPage<StoreAttribution> stores(Query c);

    CursorPage<ActivityAttribution> activities(Query c);
}
