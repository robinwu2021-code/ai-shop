package ai.neargo.shop.pay.storedvalue.settlement;

import ai.neargo.shop.common.CursorPage;
import ai.neargo.shop.spi.storedvalue.StoredValueFinance;
import ai.neargo.shop.spi.storedvalue.StoredValueMembership;
import ai.neargo.shop.spi.storedvalue.StoredValueReports;
import ai.neargo.shop.spi.storedvalue.StoredValueSettlements;
import java.util.List;

/** Business contract; implementation preserves owner checks, transactions and immutable financial facts. */
public interface StoredValueFinanceService {
    StoredValueFinance.Capabilities capabilities();

    List<StoredValueFinance.Resource> directory(StoredValueFinance.Directory c);

    StoredValueReports.Summary summary(StoredValueFinance.SummaryRequest c);

    CursorPage<StoredValueReports.StoreAttribution> stores(StoredValueReports.Query c);

    CursorPage<StoredValueReports.ActivityAttribution> activities(StoredValueReports.Query c);

    CursorPage<StoredValueReports.Summary> localIssuers(StoredValueFinance.BatchReports c);

    StoredValueSettlements.Location locate(StoredValueSettlements.Resource c);

    CursorPage<StoredValueSettlements.Bill> bills(StoredValueSettlements.Query c);

    StoredValueSettlements.Bill detail(StoredValueFinance.BillRead c);

    CursorPage<StoredValueSettlements.Item> items(StoredValueFinance.BillRead c);

    CursorPage<StoredValueSettlements.Event> events(StoredValueFinance.BillRead c);

    CursorPage<StoredValueSettlements.Payment> payments(StoredValueFinance.BillRead c);

    StoredValueSettlements.Generated generate(StoredValueSettlements.Generation c);

    StoredValueSettlements.Bill reconcile(StoredValueSettlements.Decision c);

    StoredValueSettlements.Bill dispute(StoredValueSettlements.Decision c);

    StoredValueSettlements.Bill resolveDispute(StoredValueSettlements.Resolution c);

    StoredValueSettlements.Bill adjust(StoredValueSettlements.Adjustment c);

    StoredValueSettlements.Payment register(StoredValueSettlements.Registration c);

    StoredValueSettlements.Payment confirm(StoredValueSettlements.Receipt c);

    StoredValueSettlements.Payment voidPayment(StoredValueSettlements.VoidPayment c);

    StoredValueFinance.Resolution resolvePayment(StoredValueFinance.ResolvePayment c);

    List<StoredValueMembership.Target> membershipTargets(StoredValueMembership.Describe c);

    StoredValueMembership.Ack prepareMembership(StoredValueMembership.Prepare c);

    StoredValueMembership.Ack commitMembership(StoredValueMembership.Commit c);
}
