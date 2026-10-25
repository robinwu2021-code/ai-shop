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

 @Test void publicFinancialDirectoryRequiresReadAndBindsGenerationToTheSameIssuer() throws Exception {
  var access=mock(ai.neargo.shop.merchantbridge.ManagementAccess.class);var authority=mock(ai.neargo.shop.merchant.brand.StoredValueFinancialAuthorityService.class);var finance=mock(StoredValueFinanceAppService.class);
  var actor=new ai.neargo.shop.merchant.brand.ManagementModels.Actor("MAIN","MCH:A");when(access.actor()).thenReturn(actor);
  var refs=List.of(new StoredValueIssuers.ScopeRef("BRAND","B1"));when(authority.financeResources(actor,"M1",null)).thenReturn(refs);
  var one=new StoredValueFinance.Resource("I1","BRAND","B1","One","CNY","Asia/Shanghai");var two=new StoredValueFinance.Resource("I2","BRAND","B2","Two","CNY","Asia/Shanghai");
  when(finance.directory(any())).thenAnswer(inv->{var q=inv.getArgument(0,StoredValueFinance.Directory.class);return q.entityNos().equals(Set.of("GEN"))?List.of(two):List.of(one);});
  var context=new StoredValueFinanceContext(access,authority,mock(ai.neargo.shop.payclient.StoredValueIssuerAppService.class),finance,mock(ai.neargo.shop.promotion.service.StoredValueActivityReportOwnership.class));var controller=new BizStoredValueSettlementController(context,finance);
  when(authority.financialEntities(actor,"M1")).thenReturn(Map.of("WRITE",Set.of("sv.settlement.payment.register")));
  assertThat(controller.resources("M1",null)).isEmpty();verifyNoInteractions(finance);
  assertThat(context.directory("M1",null)).containsExactly(one);
  when(authority.financialEntities(actor,"M1")).thenReturn(Map.of("READ",Set.of("sv.settlement.read"),"GEN",Set.of("sv.settlement.generate")));
  var rows=controller.resources("M1",null);assertThat(rows).hasSize(1);assertThat(rows.getFirst().issuerNo()).isEqualTo("I1");
  assertThat(rows.getFirst().getClass().getMethod("canGenerate").invoke(rows.getFirst())).isEqualTo(false);
  verify(finance).directory(new StoredValueFinance.Directory("MAIN","MCH:A","M1","FINANCE",refs,Set.of("READ")));
  verify(finance).directory(new StoredValueFinance.Directory("MAIN","MCH:A","M1","FINANCE",refs,Set.of("GEN")));
 }
 @Test void generationIsOfferedOnlyForAnIssuerInBothAuthorizedDirectories() throws Exception {
  var access=mock(ai.neargo.shop.merchantbridge.ManagementAccess.class);var authority=mock(ai.neargo.shop.merchant.brand.StoredValueFinancialAuthorityService.class);var finance=mock(StoredValueFinanceAppService.class);
  var actor=new ai.neargo.shop.merchant.brand.ManagementModels.Actor("MAIN","MCH:A");when(access.actor()).thenReturn(actor);var refs=List.of(new StoredValueIssuers.ScopeRef("BRAND","B1"));when(authority.financeResources(actor,"M1",null)).thenReturn(refs);when(authority.financialEntities(actor,"M1")).thenReturn(Map.of("E1",Set.of("sv.settlement.read","sv.settlement.generate")));
  when(finance.directory(any())).thenReturn(List.of(new StoredValueFinance.Resource("I1","BRAND","B1","One","CNY","Asia/Shanghai")));
  var context=new StoredValueFinanceContext(access,authority,mock(ai.neargo.shop.payclient.StoredValueIssuerAppService.class),finance,mock(ai.neargo.shop.promotion.service.StoredValueActivityReportOwnership.class));var rows=new BizStoredValueSettlementController(context,finance).resources("M1",null);
  assertThat(rows.getFirst().getClass().getMethod("canGenerate").invoke(rows.getFirst())).isEqualTo(true);
 }
}
