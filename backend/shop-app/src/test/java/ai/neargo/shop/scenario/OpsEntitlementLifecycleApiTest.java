package ai.neargo.shop.scenario;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import ai.neargo.shop.pay.entitlement.dto.LifecycleViews.FreezeCommand;
import ai.neargo.shop.pay.entitlement.dto.LifecycleViews.FreezeView;
import ai.neargo.shop.payclient.EntitlementLifecycleAppService;
import ai.neargo.shop.support.TestLogin;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

/** Session-derived data scope and the separate freeze permission protect all lifecycle writes. */
@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:ops-ent-lifecycle-api;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class OpsEntitlementLifecycleApiTest {
    @Autowired WebApplicationContext web;
    @Autowired ObjectMapper json;
    @MockitoBean EntitlementLifecycleAppService app;
    @Test void operatorReadsVersionThenFreezesUsingOriginalCommand() throws Exception {
        var mvc=MockMvcBuilders.webAppContextSetup(web).apply(SecurityMockMvcConfigurers.springSecurity()).build();
        var token=TestLogin.admin(mvc,json);
        when(app.state(any(),eq("I1"))).thenReturn(new FreezeView("I1","ACTIVE",4,3,1));
        var command=new FreezeCommand(4,"FREEZE","核实异常");
        when(app.freeze(any(),eq("I1"),eq(command),eq("FK1"))).thenReturn(new FreezeView("I1","FROZEN",5,3,1));
        mvc.perform(get("/ops/pay/entitlements/I1/lifecycle").param("entityNo","M1")
                .header("Authorization","Bearer "+token)).andExpect(jsonPath("$.data.revision").value(4));
        mvc.perform(post("/ops/pay/entitlements/I1/freeze").param("entityNo","M1")
                .header("Authorization","Bearer "+token).header("Idempotency-Key","FK1")
                .contentType("application/json").content(json.writeValueAsString(command)))
                .andExpect(jsonPath("$.data.status").value("FROZEN"))
                .andExpect(jsonPath("$.data.reservedUnits").value(1));
    }
    @Test void ordinarySupportAndInvalidReasonCannotReachPay() throws Exception {
        var mvc=MockMvcBuilders.webAppContextSetup(web).apply(SecurityMockMvcConfigurers.springSecurity()).build();
        var support=TestLogin.operator(mvc,json,"support","support123");
        String body=json.writeValueAsString(new FreezeCommand(0,"FREEZE","核实异常"));
        mvc.perform(post("/ops/pay/entitlements/I1/freeze").param("entityNo","M1")
                .header("Authorization","Bearer "+support).header("Idempotency-Key","FK1")
                .contentType("application/json").content(body)).andExpect(jsonPath("$.code").value(10403));
        var admin=TestLogin.admin(mvc,json);
        mvc.perform(post("/ops/pay/entitlements/I1/freeze").param("entityNo","M1")
                .header("Authorization","Bearer "+admin).header("Idempotency-Key","FK1")
                .contentType("application/json").content(body.replace("核实异常","")))
                .andExpect(jsonPath("$.code").value(10400));
        verifyNoInteractions(app);
    }
}
