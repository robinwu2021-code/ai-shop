package ai.neargo.shop.payclient.impl;
import ai.neargo.shop.payclient.StoredValueFinanceAppService;
import ai.neargo.shop.pay.client.PayInternalApi;
import ai.neargo.shop.pay.storedvalue.settlement.StoredValueFinanceService;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
@Service @ConditionalOnProperty(name="shop.pay.deployment",havingValue="embedded",matchIfMissing=true)
public class StoredValueFinanceAppServiceImpl implements StoredValueFinanceAppService {
 private final StoredValueFinanceService pay;public StoredValueFinanceAppServiceImpl(StoredValueFinanceService pay){this.pay=pay;}
 public ai.neargo.shop.spi.storedvalue.StoredValueFinance.Capabilities capabilities(){return pay.capabilities();}
 public java.util.List<ai.neargo.shop.spi.storedvalue.StoredValueFinance.Resource> directory(ai.neargo.shop.spi.storedvalue.StoredValueFinance.Directory c){return pay.directory(c);}
 public ai.neargo.shop.spi.storedvalue.StoredValueReports.Summary summary(ai.neargo.shop.spi.storedvalue.StoredValueFinance.SummaryRequest c){return pay.summary(c);}
 public ai.neargo.shop.common.CursorPage<ai.neargo.shop.spi.storedvalue.StoredValueReports.StoreAttribution> stores(ai.neargo.shop.spi.storedvalue.StoredValueReports.Query c){return pay.stores(c);}
 public ai.neargo.shop.common.CursorPage<ai.neargo.shop.spi.storedvalue.StoredValueReports.ActivityAttribution> activities(ai.neargo.shop.spi.storedvalue.StoredValueReports.Query c){return pay.activities(c);}
 public ai.neargo.shop.common.CursorPage<ai.neargo.shop.spi.storedvalue.StoredValueReports.Summary> localIssuers(ai.neargo.shop.spi.storedvalue.StoredValueFinance.BatchReports c){return pay.localIssuers(c);}
 public ai.neargo.shop.spi.storedvalue.StoredValueSettlements.Location locate(ai.neargo.shop.spi.storedvalue.StoredValueSettlements.Resource c){return pay.locate(c);}
 public ai.neargo.shop.common.CursorPage<ai.neargo.shop.spi.storedvalue.StoredValueSettlements.Bill> bills(ai.neargo.shop.spi.storedvalue.StoredValueSettlements.Query c){return pay.bills(c);}
 public ai.neargo.shop.spi.storedvalue.StoredValueSettlements.Bill detail(ai.neargo.shop.spi.storedvalue.StoredValueFinance.BillRead c){return pay.detail(c);}
 public ai.neargo.shop.common.CursorPage<ai.neargo.shop.spi.storedvalue.StoredValueSettlements.Item> items(ai.neargo.shop.spi.storedvalue.StoredValueFinance.BillRead c){return pay.items(c);}
 public ai.neargo.shop.common.CursorPage<ai.neargo.shop.spi.storedvalue.StoredValueSettlements.Event> events(ai.neargo.shop.spi.storedvalue.StoredValueFinance.BillRead c){return pay.events(c);}
 public ai.neargo.shop.common.CursorPage<ai.neargo.shop.spi.storedvalue.StoredValueSettlements.Payment> payments(ai.neargo.shop.spi.storedvalue.StoredValueFinance.BillRead c){return pay.payments(c);}
 public ai.neargo.shop.spi.storedvalue.StoredValueSettlements.Generated generate(ai.neargo.shop.spi.storedvalue.StoredValueSettlements.Generation c){return pay.generate(c);}
 public ai.neargo.shop.spi.storedvalue.StoredValueSettlements.Bill reconcile(ai.neargo.shop.spi.storedvalue.StoredValueSettlements.Decision c){return pay.reconcile(c);}
 public ai.neargo.shop.spi.storedvalue.StoredValueSettlements.Bill dispute(ai.neargo.shop.spi.storedvalue.StoredValueSettlements.Decision c){return pay.dispute(c);}
 public ai.neargo.shop.spi.storedvalue.StoredValueSettlements.Bill resolveDispute(ai.neargo.shop.spi.storedvalue.StoredValueSettlements.Resolution c){return pay.resolveDispute(c);}
 public ai.neargo.shop.spi.storedvalue.StoredValueSettlements.Bill adjust(ai.neargo.shop.spi.storedvalue.StoredValueSettlements.Adjustment c){return pay.adjust(c);}
 public ai.neargo.shop.spi.storedvalue.StoredValueSettlements.Payment register(ai.neargo.shop.spi.storedvalue.StoredValueSettlements.Registration c){return pay.register(c);}
 public ai.neargo.shop.spi.storedvalue.StoredValueSettlements.Payment confirm(ai.neargo.shop.spi.storedvalue.StoredValueSettlements.Receipt c){return pay.confirm(c);}
 public ai.neargo.shop.spi.storedvalue.StoredValueSettlements.Payment voidPayment(ai.neargo.shop.spi.storedvalue.StoredValueSettlements.VoidPayment c){return pay.voidPayment(c);}
 public ai.neargo.shop.spi.storedvalue.StoredValueFinance.Resolution resolvePayment(ai.neargo.shop.spi.storedvalue.StoredValueFinance.ResolvePayment c){return pay.resolvePayment(c);}
 public java.util.List<ai.neargo.shop.spi.storedvalue.StoredValueMembership.Target> membershipTargets(ai.neargo.shop.spi.storedvalue.StoredValueMembership.Describe c){return pay.membershipTargets(c);}
 public ai.neargo.shop.spi.storedvalue.StoredValueMembership.Ack prepareMembership(ai.neargo.shop.spi.storedvalue.StoredValueMembership.Prepare c){return pay.prepareMembership(c);}
 public ai.neargo.shop.spi.storedvalue.StoredValueMembership.Ack commitMembership(ai.neargo.shop.spi.storedvalue.StoredValueMembership.Commit c){return pay.commitMembership(c);}
}
