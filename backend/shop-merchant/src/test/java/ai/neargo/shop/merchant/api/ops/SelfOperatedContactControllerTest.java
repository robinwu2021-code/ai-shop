package ai.neargo.shop.merchant.api.ops;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.merchant.service.SelfOperatedService;
import ai.neargo.shop.spi.platform.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class SelfOperatedContactControllerTest {
 record Policy(String market,boolean recordOnly,boolean marketEnabled) implements MarketContactPolicyPort.Policy {}
 @Test void taiwanEmailRequestsDoNotRequireAMainlandPhone() throws Exception {
  var service=mock(SelfOperatedService.class);var contacts=mock(MarketContactPolicyPort.class);var audit=mock(AuditLogPort.class);
  when(service.create(any(),eq("OPS"))).thenReturn(new SelfOperatedService.ResultVO("M","S","U","AGGREGATED","SELF_OPERATED","CITY",true,1,true));
  var mvc=MockMvcBuilders.standaloneSetup(new OpsSelfOperatedController(service,audit,contacts)).build();
  try(var security=mockStatic(SecurityUtils.class)){
   security.when(SecurityUtils::currentUserNo).thenReturn("OPS");
   assertEquals(200,mvc.perform(post("/ops/merchants/self-operated").contentType("application/json").content("{\"name\":\"Taiwan Store\",\"ownerEmail\":\"owner@example.test\",\"serviceScope\":\"CITY\"}")).andReturn().getResponse().getStatus());
  }
  var command=ArgumentCaptor.forClass(SelfOperatedService.CreateCommand.class);verify(service).create(command.capture(),eq("OPS"));assertEquals("owner@example.test",command.getValue().ownerEmail());assertNull(command.getValue().phone());
 }
 @Test void emailFormatIsValidatedBeforeWriting() throws Exception {
  var service=mock(SelfOperatedService.class);var mvc=MockMvcBuilders.standaloneSetup(new OpsSelfOperatedController(service,mock(AuditLogPort.class),mock(MarketContactPolicyPort.class))).build();
  assertEquals(400,mvc.perform(post("/ops/merchants/self-operated").contentType("application/json").content("{\"name\":\"Store\",\"ownerEmail\":\"bad\"}")).andReturn().getResponse().getStatus());verifyNoInteractions(service);
 }
 @Test void selfOperationContextUsesTheSharedSystemPolicy(){
  var port=mock(MarketContactPolicyPort.class);var policy=new Policy("TW",true,true);when(port.instancePolicy()).thenReturn(policy);
  assertSame(policy,new OpsSelfOperatedController(mock(SelfOperatedService.class),mock(AuditLogPort.class),port).context());
 }
}
