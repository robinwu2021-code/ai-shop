package ai.neargo.shop.scenario;
import ai.neargo.shop.print.api.*;
import ai.neargo.shop.portal.biz.BizPrintJobQueryController;
import ai.neargo.shop.portal.device.DevicePrintController;
import ai.neargo.shop.printbridge.PrintFactReadyConsumer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
@SpringBootTest @ActiveProfiles("test")
@TestPropertySource(properties={"shop.print.enabled=true","spring.datasource.url=jdbc:h2:mem:shop_print_enabled;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
class PrintEnabledAssemblyTest {
 @Autowired WebApplicationContext context;MockMvc mvc;
 @BeforeEach void client(){mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();}
 @Test void fullApplicationAssemblesWithPrintFeatureEnabled(){assertNotNull(context.getBean(PrintFacade.class));assertNotNull(context.getBean(BizPrintJobQueryController.class));assertNotNull(context.getBean(DevicePrintController.class));assertNotNull(context.getBean(PrintFactReadyConsumer.class));}
 @Test void merchantTokenCannotBeUsedAsAgentCredential()throws Exception{mvc.perform(post("/device/print/v1/heartbeats").servletPath("/device/print/v1/heartbeats").secure(true).header("Authorization","Bearer merchant-session-token").contentType("application/json").content("{}" )).andExpect(status().isUnauthorized());}
 @Test void enrollmentStillRequiresHttps()throws Exception{mvc.perform(post("/device/print/v1/enrollments").servletPath("/device/print/v1/enrollments").secure(false).contentType("application/json").content("{}")).andExpect(status().isUnauthorized());}
}
