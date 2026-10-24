package ai.neargo.shop.payclient.impl;
import ai.neargo.shop.payclient.StoredValueFinanceAppService;
import ai.neargo.shop.payclient.RemoteEntitlementCalls;
import ai.neargo.shop.pay.client.PayInternalApi;
import ai.neargo.shop.pay.storedvalue.settlement.StoredValueFinanceService;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
@Service @ConditionalOnProperty(name="shop.pay.deployment",havingValue="standalone",matchIfMissing=false)
public class RemoteStoredValueFinanceAppService implements StoredValueFinanceAppService {
 private final PayInternalApi pay;public RemoteStoredValueFinanceAppService(PayInternalApi pay){this.pay=pay;}
 public ai.neargo.shop.spi.storedvalue.StoredValueFinance.Capabilities capabilities(){return RemoteEntitlementCalls.call(()->pay.svFinanceCapabilities());}
 public java.util.List<ai.neargo.shop.spi.storedvalue.StoredValueFinance.Resource> directory(ai.neargo.shop.spi.storedvalue.StoredValueFinance.Directory c){return RemoteEntitlementCalls.call(()->pay.svFinanceDirectory(c));}
 public ai.neargo.shop.spi.storedvalue.StoredValueReports.Summary summary(ai.neargo.shop.spi.storedvalue.StoredValueFinance.SummaryRequest c){return RemoteEntitlementCalls.call(()->pay.svFinanceSummary(c));}
 public ai.neargo.shop.common.CursorPage<ai.neargo.shop.spi.storedvalue.StoredValueReports.StoreAttribution> stores(ai.neargo.shop.spi.storedvalue.StoredValueReports.Query c){return RemoteEntitlementCalls.call(()->pay.svFinanceStores(c));}
 public ai.neargo.shop.common.CursorPage<ai.neargo.shop.spi.storedvalue.StoredValueReports.ActivityAttribution> activities(ai.neargo.shop.spi.storedvalue.StoredValueReports.Query c){return RemoteEntitlementCalls.call(()->pay.svFinanceActivities(c));}
 public ai.neargo.shop.common.CursorPage<ai.neargo.shop.spi.storedvalue.StoredValueReports.Summary> localIssuers(ai.neargo.shop.spi.storedvalue.StoredValueFinance.BatchReports c){return RemoteEntitlementCalls.call(()->pay.svFinanceLocalIssuers(c));}
 public ai.neargo.shop.spi.storedvalue.StoredValueSettlements.Location locate(ai.neargo.shop.spi.storedvalue.StoredValueSettlements.Resource c){return RemoteEntitlementCalls.call(()->pay.svFinanceLocate(c));}
 public ai.neargo.shop.common.CursorPage<ai.neargo.shop.spi.storedvalue.StoredValueSettlements.Bill> bills(ai.neargo.shop.spi.storedvalue.StoredValueSettlements.Query c){return RemoteEntitlementCalls.call(()->pay.svFinanceBills(c));}
 public ai.neargo.shop.spi.storedvalue.StoredValueSettlements.Bill detail(ai.neargo.shop.spi.storedvalue.StoredValueFinance.BillRead c){return RemoteEntitlementCalls.call(()->pay.svFinanceDetail(c));}
 public ai.neargo.shop.common.CursorPage<ai.neargo.shop.spi.storedvalue.StoredValueSettlements.Item> items(ai.neargo.shop.spi.storedvalue.StoredValueFinance.BillRead c){return RemoteEntitlementCalls.call(()->pay.svFinanceItems(c));}
 public ai.neargo.shop.common.CursorPage<ai.neargo.shop.spi.storedvalue.StoredValueSettlements.Event> events(ai.neargo.shop.spi.storedvalue.StoredValueFinance.BillRead c){return RemoteEntitlementCalls.call(()->pay.svFinanceEvents(c));}
 public ai.neargo.shop.common.CursorPage<ai.neargo.shop.spi.storedvalue.StoredValueSettlements.Payment> payments(ai.neargo.shop.spi.storedvalue.StoredValueFinance.BillRead c){return RemoteEntitlementCalls.call(()->pay.svFinancePayments(c));}
 public ai.neargo.shop.spi.storedvalue.StoredValueSettlements.Generated generate(ai.neargo.shop.spi.storedvalue.StoredValueSettlements.Generation c){return RemoteEntitlementCalls.call(()->pay.svFinanceGenerate(c));}
 public ai.neargo.shop.spi.storedvalue.StoredValueSettlements.Bill reconcile(ai.neargo.shop.spi.storedvalue.StoredValueSettlements.Decision c){return RemoteEntitlementCalls.call(()->pay.svFinanceReconcile(c));}
 public ai.neargo.shop.spi.storedvalue.StoredValueSettlements.Bill dispute(ai.neargo.shop.spi.storedvalue.StoredValueSettlements.Decision c){return RemoteEntitlementCalls.call(()->pay.svFinanceDispute(c));}
 public ai.neargo.shop.spi.storedvalue.StoredValueSettlements.Bill resolveDispute(ai.neargo.shop.spi.storedvalue.StoredValueSettlements.Resolution c){return RemoteEntitlementCalls.call(()->pay.svFinanceResolveDispute(c));}
 public ai.neargo.shop.spi.storedvalue.StoredValueSettlements.Bill adjust(ai.neargo.shop.spi.storedvalue.StoredValueSettlements.Adjustment c){return RemoteEntitlementCalls.call(()->pay.svFinanceAdjust(c));}
 public ai.neargo.shop.spi.storedvalue.StoredValueSettlements.Payment register(ai.neargo.shop.spi.storedvalue.StoredValueSettlements.Registration c){return RemoteEntitlementCalls.call(()->pay.svFinanceRegister(c));}
 public ai.neargo.shop.spi.storedvalue.StoredValueSettlements.Payment confirm(ai.neargo.shop.spi.storedvalue.StoredValueSettlements.Receipt c){return RemoteEntitlementCalls.call(()->pay.svFinanceConfirm(c));}
 public ai.neargo.shop.spi.storedvalue.StoredValueSettlements.Payment voidPayment(ai.neargo.shop.spi.storedvalue.StoredValueSettlements.VoidPayment c){return RemoteEntitlementCalls.call(()->pay.svFinanceVoidPayment(c));}
 public ai.neargo.shop.spi.storedvalue.StoredValueFinance.Resolution resolvePayment(ai.neargo.shop.spi.storedvalue.StoredValueFinance.ResolvePayment c){return RemoteEntitlementCalls.call(()->pay.svFinanceResolvePayment(c));}
 public java.util.List<ai.neargo.shop.spi.storedvalue.StoredValueMembership.Target> membershipTargets(ai.neargo.shop.spi.storedvalue.StoredValueMembership.Describe c){return RemoteEntitlementCalls.call(()->pay.svFinanceMembershipTargets(c));}
 public ai.neargo.shop.spi.storedvalue.StoredValueMembership.Ack prepareMembership(ai.neargo.shop.spi.storedvalue.StoredValueMembership.Prepare c){return RemoteEntitlementCalls.call(()->pay.svFinancePrepareMembership(c));}
 public ai.neargo.shop.spi.storedvalue.StoredValueMembership.Ack commitMembership(ai.neargo.shop.spi.storedvalue.StoredValueMembership.Commit c){return RemoteEntitlementCalls.call(()->pay.svFinanceCommitMembership(c));}
}
