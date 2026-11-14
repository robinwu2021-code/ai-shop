package ai.neargo.shop.pay.svc;
import ai.neargo.shop.pay.client.PayStoredValuePolicyRequests;
import ai.neargo.shop.pay.storedvalue.policy.*;
import ai.neargo.shop.spi.storedvalue.StoredValuePolicies.*;
import ai.neargo.shop.spi.storedvalue.StoredValueReleases.*;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class StoredValueGovernanceEndpointTest {
 @Test void internalTokenRejectsPolicyAndReleaseCallsBeforeAnyBusinessInvocation(){
  var query=mock(StoredValueIssuePolicyService.class);var configs=mock(StoredValuePolicyConfigurationService.class);var releases=mock(StoredValueReleaseService.class);
  var policies=new InternalStoredValuePolicyEndpoint(query,configs,"secret");var endpoint=new InternalStoredValueReleaseEndpoint(releases,"secret");
  var authority=new PolicyAuthority("MAIN","OWNER","M1","SPACE","M1",null,"CNY",1);var edit=new PolicyEdit(new Rules(true,true,2000,10000,2000,30,100000),0);
  assertThat(policies.configure(new PayStoredValuePolicyRequests.Configure(authority,edit,"KEY"),null).getStatusCode().value()).isEqualTo(401);
  var prepare=new Prepare(new StoreScope("MAIN","M1","S1",null,"CNY"),"E1","OWNER","OWNER","Offer","A1","0","a".repeat(64),Instant.now(),Instant.now().plusSeconds(86400),"Asia/Shanghai",List.of(new Tier("T1",10000,2000)),"PREPARE");
  assertThat(endpoint.prepare(prepare,"wrong").getStatusCode().value()).isEqualTo(401);verifyNoInteractions(query,configs,releases);
  policies.configure(new PayStoredValuePolicyRequests.Configure(authority,edit,"KEY"),"secret");verify(configs).configure(authority,edit,"KEY");endpoint.prepare(prepare,"secret");verify(releases).prepare(prepare);
 }
}
