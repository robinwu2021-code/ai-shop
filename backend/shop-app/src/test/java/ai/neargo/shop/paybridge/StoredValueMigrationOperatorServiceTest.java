package ai.neargo.shop.paybridge;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.payclient.StoredValueMigrationAppService;
import ai.neargo.shop.spi.storedvalue.StoredValueMigrationPlans.*;
import ai.neargo.shop.spi.storedvalue.StoredValueMigrationReadiness.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class StoredValueMigrationOperatorServiceTest {
 @Test void closedOrUnreadyOperatorDoesNotReachPay(){
  var readiness=mock(StoredValueMigrationReadinessService.class);var pay=mock(StoredValueMigrationAppService.class);
  assertThatThrownBy(()->new StoredValueMigrationOperatorService(readiness,pay,mock(ai.neargo.shop.merchant.brand.IssuerAuthorityService.class),false).execute("resume","OP","A1")).isInstanceOf(BizException.class);
  verifyNoInteractions(readiness,pay);
  when(readiness.proof()).thenReturn(new Proof(new Inventory("R1","b".repeat(64),false,List.of()),new Report(false,"h",Instant.now(),Instant.now().plusSeconds(30),List.of(new Issue(null,"UNSEALED")),List.of())));
  assertThatThrownBy(()->new StoredValueMigrationOperatorService(readiness,pay,mock(ai.neargo.shop.merchant.brand.IssuerAuthorityService.class),true).execute("resume","OP","A1")).isInstanceOf(BizException.class);
  verifyNoInteractions(pay);
 }
 @Test void operatorAlwaysUsesServerCollectedProofAndTypedOriginalCommand(){
  var readiness=mock(StoredValueMigrationReadinessService.class);var pay=mock(StoredValueMigrationAppService.class);
  var proof=new Proof(new Inventory("R1","b".repeat(64),true,List.of()),new Report(true,"h",Instant.now(),Instant.now().plusSeconds(30),List.of(),List.of()));
  when(readiness.proof()).thenReturn(proof);
  var result=new ExecutionCommand("C1","A1","RESUME","ISSUER_WRITERS_RESUMED",1,1);
  when(pay.operatorSources("OP","A1",proof)).thenReturn(new OperatorEvidence("a".repeat(64),List.of(new ai.neargo.shop.spi.storedvalue.StoredValueIssuers.StoreAuthority("MAIN","OWNER","E1","S1","Store",1))));
  when(pay.execute("resume","OP","A1",proof,"a".repeat(64))).thenReturn(result);
  when(pay.command("OP","A1","C1",proof)).thenReturn(result);
  var service=new StoredValueMigrationOperatorService(readiness,pay,mock(ai.neargo.shop.merchant.brand.IssuerAuthorityService.class),true);
  assertThat(service.execute("resume","OP","A1")).isEqualTo(result);
  assertThat(service.command("OP","A1","C1")).isEqualTo(result);
  verify(readiness,times(2)).proof();
 }

 @Test void changedStoreAuthorityPreventsAnyCutoverCommand(){
  var readiness=mock(StoredValueMigrationReadinessService.class);var pay=mock(StoredValueMigrationAppService.class);var authority=mock(ai.neargo.shop.merchant.brand.IssuerAuthorityService.class);
  var proof=new Proof(new Inventory("R1","b".repeat(64),true,List.of()),new Report(true,"h",Instant.now(),Instant.now().plusSeconds(30),List.of(),List.of()));when(readiness.proof()).thenReturn(proof);
  var sources=List.of(new ai.neargo.shop.spi.storedvalue.StoredValueIssuers.StoreAuthority("MAIN","OWNER","E1","S1","Store",1));when(pay.operatorSources("OP","A1",proof)).thenReturn(new OperatorEvidence("a".repeat(64),sources));
  doThrow(BizException.of(ai.neargo.shop.common.ErrorCode.CONFLICT)).when(authority).verifyMigrationSources(sources);
  assertThatThrownBy(()->new StoredValueMigrationOperatorService(readiness,pay,authority,true).execute("resume","OP","A1")).isInstanceOf(BizException.class);
  verify(pay,never()).execute(anyString(),anyString(),anyString(),any(),any());
 }
}
