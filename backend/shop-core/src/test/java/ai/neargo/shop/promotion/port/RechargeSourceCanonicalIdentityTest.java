package ai.neargo.shop.promotion.port;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.promotion.dto.RechargeIntentPlan;
import ai.neargo.shop.promotion.service.RechargeIntentService;
import ai.neargo.shop.spi.entitlement.*;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition.RedemptionScope;
import ai.neargo.shop.spi.promotion.ActivityParticipationPort.*;
import ai.neargo.shop.spi.user.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class RechargeSourceCanonicalIdentityTest {
 @Test void existingPlatformFactsSupplyCanonicalCustomerWithoutCreatingOrBindingAnyone(){
  var intents=mock(RechargeIntentService.class);var people=mock(PersonPort.class);var identities=mock(MemberIdentityFactsPort.class);
  var ctx=new EntitlementContext("MAIN","OP","K1","0".repeat(64));var source=new SourceRef("CORE","RECHARGE","I1",null,null,null,0);var now=Instant.now();
  var participation=new ReserveActivity(source,"A1","AV1","T1","U1","E1","S1","CNY",1);
  var permit=new ParticipationPermit("P1",source,"A1","AV1","T1","U1","E1","S1","CNY",100L,10L,List.of(),new RedemptionScope("STORES",List.of("S1")),"a".repeat(64),now);
  when(intents.byRechargeNo("MAIN","R1")).thenReturn(new RechargeIntentPlan("I1",ctx,source,"R1",participation,permit,"Store",now,now.plusSeconds(60)));
  when(people.findByUser("U1")).thenReturn(Optional.of(new PersonPort.PersonView("PERSON1","8000","U1","138****8000")));
  when(identities.find("MAIN","PERSON1")).thenReturn(Optional.of(new MemberIdentityFactsPort.Identity("PERSON1","U1","138****8000",true)));
  var port=new RechargeSourcePortImpl(intents,people,identities);
  assertThat(port.byRechargeNo(ctx,"R1").customerNo()).isEqualTo("PERSON1");
  when(identities.find("MAIN","PERSON1")).thenReturn(Optional.of(new MemberIdentityFactsPort.Identity("OTHER","U1","138****8000",true)));
  assertThatThrownBy(()->port.byRechargeNo(ctx,"R1")).isInstanceOf(BizException.class);
  verify(people,never()).resolveOrCreateByPhone(anyString());
 }
}
