package ai.neargo.shop.portal.biz;
import ai.neargo.shop.merchantbridge.StoredValueFinanceContext;
import ai.neargo.shop.payclient.StoredValueFinanceAppService;
import ai.neargo.shop.spi.storedvalue.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class StoredValueFinanceAccessTest {
 @Test void publicActionsComeFromLiveEntityRightsRatherThanBrandOwnership(){
  var context=mock(StoredValueFinanceContext.class);var finance=mock(StoredValueFinanceAppService.class);
  var authority=new StoredValueSettlements.Authority("MAIN","MCH:A","USER:A","M1",List.of(new StoredValueSettlements.IssuerGrant("I1","BRAND","B1")),Map.of("E2",Set.of("sv.settlement.read","sv.settlement.receipt.confirm")),Set.of());
  var at=Instant.parse("2026-10-01T00:00:00Z");var bill=new StoredValueSettlements.Bill("BILL1","I1","E1","E2","CNY",at,at.plusSeconds(86400),"CROSS_ENTITY","WAIT_RECEIPT",100,100,0,100,0,1,1,null,null,null,3,at,at);
  when(context.bill("M1","BILL1","BILL")).thenReturn(authority);when(finance.detail(new StoredValueFinance.BillRead(authority,"BILL1",null,20))).thenReturn(bill);
  var actions=new BizStoredValueSettlementController(context,finance).actions("BILL1","M1");assertThat(actions.payerActions()).isEmpty();assertThat(actions.payeeActions()).containsExactlyInAnyOrder("sv.settlement.read","sv.settlement.receipt.confirm");
 }
 @Test void publicFinancialBodiesNeverAcceptInternalAuthorityOrCollectionProof(){
  for(var type:List.of(BizStoredValueSettlementController.GenerationRequest.class,BizStoredValueSettlementController.PaymentRequest.class,BizBrandFinancialChangeController.Start.class,BizStoredValueSettlementController.PermissionRequest.class))assertThat(Arrays.stream(type.getRecordComponents()).map(java.lang.reflect.RecordComponent::getName)).doesNotContain("authority","actorNo","principalNo","collectionProof","targets");
 }
}
