package ai.neargo.shop.payclient;
import ai.neargo.shop.common.CursorPage;
import ai.neargo.shop.spi.storedvalue.*;
import java.util.List;
/** Exact same contract for embedded and remote Pay deployment. */
public interface StoredValueFinanceAppService {
 StoredValueFinance.Capabilities capabilities();
 StoredValueReports.ProjectionResult rebuild(StoredValueReports.Projection c);
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
 java.util.List<ai.neargo.shop.spi.storedvalue.StoredValueMembership.Target> membershipTargets(ai.neargo.shop.spi.storedvalue.StoredValueMembership.Describe c);
 ai.neargo.shop.spi.storedvalue.StoredValueMembership.Ack prepareMembership(ai.neargo.shop.spi.storedvalue.StoredValueMembership.Prepare c);
 ai.neargo.shop.spi.storedvalue.StoredValueMembership.Ack commitMembership(ai.neargo.shop.spi.storedvalue.StoredValueMembership.Commit c);
 ai.neargo.shop.spi.storedvalue.StoredValueCollections.Lookup collectionRequirement(ai.neargo.shop.spi.storedvalue.StoredValueWallets.ExecutionScope c);
 ai.neargo.shop.spi.storedvalue.StoredValueIssuers.StoreOptions collectionOptions(ai.neargo.shop.spi.storedvalue.StoredValueCollections.Options c);
}
