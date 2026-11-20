package ai.neargo.shop.payclient.impl;
import ai.neargo.shop.common.CursorPage;
import ai.neargo.shop.spi.storedvalue.StoredValueCollections;
import ai.neargo.shop.spi.storedvalue.StoredValueFinance;
import ai.neargo.shop.spi.storedvalue.StoredValueIssuers;
import ai.neargo.shop.spi.storedvalue.StoredValueMembership;
import ai.neargo.shop.spi.storedvalue.StoredValueReports;
import ai.neargo.shop.spi.storedvalue.StoredValueSettlements;
import ai.neargo.shop.spi.storedvalue.StoredValueWallets;
import ai.neargo.shop.payclient.StoredValueFinanceAppService;
import ai.neargo.shop.pay.client.PayInternalApi;
import ai.neargo.shop.pay.storedvalue.settlement.StoredValueFinanceService;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
@Service @ConditionalOnProperty(name="shop.pay.deployment",havingValue="embedded",matchIfMissing=true)
public class StoredValueFinanceAppServiceImpl implements StoredValueFinanceAppService {
 private final StoredValueFinanceService pay;public StoredValueFinanceAppServiceImpl(StoredValueFinanceService pay){this.pay=pay;}
 public StoredValueReports.ProjectionResult rebuild(StoredValueReports.Projection c){return pay.rebuild(c);}
 public StoredValueFinance.Capabilities capabilities(){return pay.capabilities();}
 public java.util.List<StoredValueFinance.Resource> directory(StoredValueFinance.Directory c){return pay.directory(c);}
 public StoredValueReports.Summary summary(StoredValueFinance.SummaryRequest c){return pay.summary(c);}
 public CursorPage<StoredValueReports.StoreAttribution> stores(StoredValueReports.Query c){return pay.stores(c);}
 public CursorPage<StoredValueReports.ActivityAttribution> activities(StoredValueReports.Query c){return pay.activities(c);}
 public CursorPage<StoredValueReports.Summary> localIssuers(StoredValueFinance.BatchReports c){return pay.localIssuers(c);}
 public StoredValueSettlements.Location locate(StoredValueSettlements.Resource c){return pay.locate(c);}
 public CursorPage<StoredValueSettlements.Bill> bills(StoredValueSettlements.Query c){return pay.bills(c);}
 public StoredValueSettlements.Bill detail(StoredValueFinance.BillRead c){return pay.detail(c);}
 public CursorPage<StoredValueSettlements.Item> items(StoredValueFinance.BillRead c){return pay.items(c);}
 public CursorPage<StoredValueSettlements.Event> events(StoredValueFinance.BillRead c){return pay.events(c);}
 public CursorPage<StoredValueSettlements.Payment> payments(StoredValueFinance.BillRead c){return pay.payments(c);}
 public StoredValueSettlements.Generated generate(StoredValueSettlements.Generation c){return pay.generate(c);}
 public StoredValueSettlements.Bill reconcile(StoredValueSettlements.Decision c){return pay.reconcile(c);}
 public StoredValueSettlements.Bill dispute(StoredValueSettlements.Decision c){return pay.dispute(c);}
 public StoredValueSettlements.Bill resolveDispute(StoredValueSettlements.Resolution c){return pay.resolveDispute(c);}
 public StoredValueSettlements.Bill adjust(StoredValueSettlements.Adjustment c){return pay.adjust(c);}
 public StoredValueSettlements.Payment register(StoredValueSettlements.Registration c){return pay.register(c);}
 public StoredValueSettlements.Payment confirm(StoredValueSettlements.Receipt c){return pay.confirm(c);}
 public StoredValueSettlements.Payment voidPayment(StoredValueSettlements.VoidPayment c){return pay.voidPayment(c);}
 public StoredValueFinance.Resolution resolvePayment(StoredValueFinance.ResolvePayment c){return pay.resolvePayment(c);}
 public java.util.List<StoredValueMembership.Target> membershipTargets(StoredValueMembership.Describe c){return pay.membershipTargets(c);}
 public StoredValueMembership.Ack prepareMembership(StoredValueMembership.Prepare c){return pay.prepareMembership(c);}
 public StoredValueMembership.Ack commitMembership(StoredValueMembership.Commit c){return pay.commitMembership(c);}
 public StoredValueCollections.Lookup collectionRequirement(StoredValueWallets.ExecutionScope c){return pay.collectionRequirement(c);}
 public StoredValueIssuers.StoreOptions collectionOptions(StoredValueCollections.Options c){return pay.collectionOptions(c);}
}
