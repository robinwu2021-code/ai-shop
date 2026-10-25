package ai.neargo.shop.payclient.impl;
import ai.neargo.shop.payclient.StoredValueFinanceAppService;
import ai.neargo.shop.payclient.RemoteEntitlementCalls;
import ai.neargo.shop.pay.client.PayInternalApi;
import ai.neargo.shop.pay.storedvalue.settlement.StoredValueFinanceService;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import ai.neargo.shop.spi.storedvalue.StoredValueCollections;
import ai.neargo.shop.spi.storedvalue.StoredValueFinance;
import ai.neargo.shop.spi.storedvalue.StoredValueIssuers;
import ai.neargo.shop.spi.storedvalue.StoredValueMembership;
import ai.neargo.shop.spi.storedvalue.StoredValueReports;
import ai.neargo.shop.spi.storedvalue.StoredValueSettlements;
import ai.neargo.shop.spi.storedvalue.StoredValueWallets;
@Service @ConditionalOnProperty(name="shop.pay.deployment",havingValue="standalone",matchIfMissing=false)
public class RemoteStoredValueFinanceAppService implements StoredValueFinanceAppService {
 private final PayInternalApi pay;public RemoteStoredValueFinanceAppService(PayInternalApi pay){this.pay=pay;}
 public StoredValueReports.ProjectionResult rebuild(StoredValueReports.Projection c){return RemoteEntitlementCalls.call(()->pay.svFinanceRebuild(c));}
 public StoredValueFinance.Capabilities capabilities(){return RemoteEntitlementCalls.call(()->pay.svFinanceCapabilities());}
 public java.util.List<StoredValueFinance.Resource> directory(StoredValueFinance.Directory c){return RemoteEntitlementCalls.call(()->pay.svFinanceDirectory(c));}
 public StoredValueReports.Summary summary(StoredValueFinance.SummaryRequest c){return RemoteEntitlementCalls.call(()->pay.svFinanceSummary(c));}
 public ai.neargo.shop.common.CursorPage<StoredValueReports.StoreAttribution> stores(StoredValueReports.Query c){return RemoteEntitlementCalls.call(()->pay.svFinanceStores(c));}
 public ai.neargo.shop.common.CursorPage<StoredValueReports.ActivityAttribution> activities(StoredValueReports.Query c){return RemoteEntitlementCalls.call(()->pay.svFinanceActivities(c));}
 public ai.neargo.shop.common.CursorPage<StoredValueReports.Summary> localIssuers(StoredValueFinance.BatchReports c){return RemoteEntitlementCalls.call(()->pay.svFinanceLocalIssuers(c));}
 public StoredValueSettlements.Location locate(StoredValueSettlements.Resource c){return RemoteEntitlementCalls.call(()->pay.svFinanceLocate(c));}
 public ai.neargo.shop.common.CursorPage<StoredValueSettlements.Bill> bills(StoredValueSettlements.Query c){return RemoteEntitlementCalls.call(()->pay.svFinanceBills(c));}
 public StoredValueSettlements.Bill detail(StoredValueFinance.BillRead c){return RemoteEntitlementCalls.call(()->pay.svFinanceDetail(c));}
 public ai.neargo.shop.common.CursorPage<StoredValueSettlements.Item> items(StoredValueFinance.BillRead c){return RemoteEntitlementCalls.call(()->pay.svFinanceItems(c));}
 public ai.neargo.shop.common.CursorPage<StoredValueSettlements.Event> events(StoredValueFinance.BillRead c){return RemoteEntitlementCalls.call(()->pay.svFinanceEvents(c));}
 public ai.neargo.shop.common.CursorPage<StoredValueSettlements.Payment> payments(StoredValueFinance.BillRead c){return RemoteEntitlementCalls.call(()->pay.svFinancePayments(c));}
 public StoredValueSettlements.Generated generate(StoredValueSettlements.Generation c){return RemoteEntitlementCalls.call(()->pay.svFinanceGenerate(c));}
 public StoredValueSettlements.Bill reconcile(StoredValueSettlements.Decision c){return RemoteEntitlementCalls.call(()->pay.svFinanceReconcile(c));}
 public StoredValueSettlements.Bill dispute(StoredValueSettlements.Decision c){return RemoteEntitlementCalls.call(()->pay.svFinanceDispute(c));}
 public StoredValueSettlements.Bill resolveDispute(StoredValueSettlements.Resolution c){return RemoteEntitlementCalls.call(()->pay.svFinanceResolveDispute(c));}
 public StoredValueSettlements.Bill adjust(StoredValueSettlements.Adjustment c){return RemoteEntitlementCalls.call(()->pay.svFinanceAdjust(c));}
 public StoredValueSettlements.Payment register(StoredValueSettlements.Registration c){return RemoteEntitlementCalls.call(()->pay.svFinanceRegister(c));}
 public StoredValueSettlements.Payment confirm(StoredValueSettlements.Receipt c){return RemoteEntitlementCalls.call(()->pay.svFinanceConfirm(c));}
 public StoredValueSettlements.Payment voidPayment(StoredValueSettlements.VoidPayment c){return RemoteEntitlementCalls.call(()->pay.svFinanceVoidPayment(c));}
 public StoredValueFinance.Resolution resolvePayment(StoredValueFinance.ResolvePayment c){return RemoteEntitlementCalls.call(()->pay.svFinanceResolvePayment(c));}
 public java.util.List<StoredValueMembership.Target> membershipTargets(StoredValueMembership.Describe c){return RemoteEntitlementCalls.call(()->pay.svFinanceMembershipTargets(c));}
 public StoredValueMembership.Ack prepareMembership(StoredValueMembership.Prepare c){return RemoteEntitlementCalls.call(()->pay.svFinancePrepareMembership(c));}
 public StoredValueMembership.Ack commitMembership(StoredValueMembership.Commit c){return RemoteEntitlementCalls.call(()->pay.svFinanceCommitMembership(c));}
 public StoredValueCollections.Lookup collectionRequirement(StoredValueWallets.ExecutionScope c){return RemoteEntitlementCalls.call(()->pay.svFinanceCollectionRequirement(c));}
 public StoredValueIssuers.StoreOptions collectionOptions(StoredValueCollections.Options c){return RemoteEntitlementCalls.call(()->pay.svFinanceCollectionOptions(c));}
}
