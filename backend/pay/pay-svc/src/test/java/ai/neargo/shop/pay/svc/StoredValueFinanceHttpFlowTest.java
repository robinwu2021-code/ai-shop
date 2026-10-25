package ai.neargo.shop.pay.svc;
import ai.neargo.shop.pay.client.PayInternalApi;
import ai.neargo.shop.pay.storedvalue.settlement.StoredValueFinanceService;
import ai.neargo.shop.spi.storedvalue.*;
import ai.neargo.shop.svc.*;
import ai.neargo.svc.client.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
@SpringBootTest(classes=PayApplication.class,webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties="shop.services.internal-token=p6-test-token")
@ActiveProfiles("svctest")
class StoredValueFinanceHttpFlowTest {
 @LocalServerPort int port;
 @MockitoBean StoredValueFinanceService finance;
 PayInternalApi api(String token){var locator=new ConfigServiceLocator();locator.getTargets().put(ServiceName.PAY,"http://127.0.0.1:"+port);return new InternalHttp(locator,token).client(ServiceName.PAY,PayInternalApi.class,Duration.ofSeconds(5));}
 StoredValueWallets.ExecutionScope scope(){return new StoredValueWallets.ExecutionScope("MAIN","OWNER","M1","E2","S1",3,"B1","BIND1",2,"CNY",4,2);}
 @Test void privateCollectionProofSurvivesRealTypedHttpAndAbsentRequirementIsNotNullResponse(){
  var s=scope();when(finance.collectionRequirement(s)).thenReturn(new StoredValueCollections.Lookup(null));assertThat(api("p6-test-token").svFinanceCollectionRequirement(s)).isEqualTo(new StoredValueCollections.Lookup(null));
  var requirement=new StoredValueCollections.Requirement(s,"I1","IV1","E1",true);var command=new StoredValueCollections.Options(s,new StoredValueCollections.Proof(requirement,"MCH:OWNER",4,Instant.now().plusSeconds(60)));
  var response=new StoredValueIssuers.StoreOptions("S1",false,List.of());when(finance.collectionOptions(command)).thenReturn(response);assertThat(api("p6-test-token").svFinanceCollectionOptions(command)).isEqualTo(response);verify(finance).collectionOptions(command);
 }
 @Test void financialMembershipOriginalAndAckRetainTheirExactWireValues(){
  var original=new StoredValueMembership.Prepare("MAIN","MCH:OWNER","M1","C1","S1","E2",List.of(new StoredValueMembership.Side("I1","IV1","B1","JOIN",null,"BIND1",-1,2)),2);var prepared=new StoredValueMembership.Ack("C1","a".repeat(64),"PREPARED",List.of("I1"));when(finance.prepareMembership(original)).thenReturn(prepared);assertThat(api("p6-test-token").svFinancePrepareMembership(original)).isEqualTo(prepared);
  var commit=new StoredValueMembership.Commit(original,prepared.snapshotHash(),"C1");var active=new StoredValueMembership.Ack("C1",prepared.snapshotHash(),"ACTIVE",List.of("I1"));when(finance.commitMembership(commit)).thenReturn(active);assertThat(api("p6-test-token").svFinanceCommitMembership(commit)).isEqualTo(active);
 }
 @Test void boundedProjectionCursorAndMetadataSurviveHttp(){
  var scope=new StoredValueReports.Scope("MAIN","SYSTEM_JOB","M1","I1","BRAND","B1","CNY",null,null);var c=new StoredValueReports.Projection(scope,LocalDate.parse("2026-10-24"),"R1","S1",50);var result=new StoredValueReports.ProjectionResult("I1",c.date(),"R2","S2",true,50,Instant.parse("2026-10-25T00:00:00Z"));when(finance.rebuild(c)).thenReturn(result);assertThat(api("p6-test-token").svFinanceRebuild(c)).isEqualTo(result);
 }
 @Test void wrongInternalTokenCannotReachFinancialOwner(){
  assertThatThrownBy(()->ServiceCalls.call(ServiceName.PAY,()->api("wrong").svFinanceCollectionRequirement(scope()))).isInstanceOfSatisfying(ServiceCallException.class,e->assertThat(e.statusCode()).isEqualTo(401));verifyNoInteractions(finance);
 }
}
