package ai.neargo.shop.scenario;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.entitlement.dto.ExceptionViews.Evidence;
import ai.neargo.shop.pay.entitlement.dto.ExceptionViews.ExceptionView;
import ai.neargo.shop.pay.entitlement.dto.ExceptionViews.RecoveryCommand;
import ai.neargo.shop.pay.entitlement.dto.ExceptionViews.ActionView;
import ai.neargo.shop.payclient.EntitlementExceptionAppService;
import ai.neargo.shop.support.TestLogin;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;
@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:ops-ent-api;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class OpsEntitlementApiTest {
    @Autowired WebApplicationContext web;
    @Autowired ObjectMapper json;
    @MockitoBean EntitlementExceptionAppService app;
    @Test void fourRoutesUseAuthenticatedScopeAndOriginalRecoveryKey() throws Exception {
        var mvc=MockMvcBuilders.webAppContextSetup(web).apply(SecurityMockMvcConfigurers.springSecurity()).build();
        var token=TestLogin.admin(mvc,json);
        var view=new ExceptionView("EX1","M1","GRANT_DELIVERY","G1","OPEN",new Evidence("G1","PREPARED","CENTER","DELIVERY_FAILED"),null,0);
        when(app.listAcross(any(),eq(null),eq(null),eq(1L),eq(20L))).thenReturn(new PageData<>(List.of(view),1,1,20));
        when(app.get(any(),eq("EX1"))).thenReturn(view);
        when(app.actions(any(),eq("EX1"),eq(1L),eq(20L))).thenReturn(new PageData<>(List.of(
                new ActionView("EA1","EX1","QUERY_SOURCE","DONE","OP1","核实原单",
                        java.time.LocalDateTime.of(2026,10,19,1,0),java.time.LocalDateTime.of(2026,10,19,1,0))),1,1,20));
        when(app.retryGrant(any(),eq("EX1"),any(),eq("RETRY1"))).thenReturn(view);
        when(app.retryEvent(any(),eq("EX1"),any(),eq("EVENT1"))).thenReturn(view);
        when(app.querySource(any(),eq("EX1"),any(),eq("QUERY1"))).thenReturn(view);
        mvc.perform(get("/ops/pay/entitlement-exceptions").param("entityNo","M1")
                .header("Authorization","Bearer "+token)).andExpect(jsonPath("$.code").value(0));
        mvc.perform(get("/ops/pay/entitlement-exceptions/EX1").param("entityNo","M1")
                .header("Authorization","Bearer "+token)).andExpect(jsonPath("$.data.bizNo").value("G1"));
        mvc.perform(get("/ops/pay/entitlement-exceptions/EX1/actions").param("entityNo","M1")
                .header("Authorization","Bearer "+token)).andExpect(jsonPath("$.data.records[0].actionNo").value("EA1"))
                .andExpect(jsonPath("$.data.records[0].responseSnapshot").doesNotExist());
        String body=json.writeValueAsString(new RecoveryCommand(0,"核实原单"));
        for(String action:List.of("retry-grant","query-source","retry-event")) {
            mvc.perform(post("/ops/pay/entitlement-exceptions/EX1/"+action).param("entityNo","M1")
                    .header("Authorization","Bearer "+token).header("Idempotency-Key",action.equals("retry-grant")?"RETRY1":action.equals("retry-event")?"EVENT1":"QUERY1")
                    .contentType("application/json").content(body)).andExpect(jsonPath("$.code").value(0));
        }
        verify(app).retryEvent(any(),eq("EX1"),eq(new RecoveryCommand(0,"核实原单")),eq("EVENT1"));
        var scope=org.mockito.ArgumentCaptor.forClass(CatalogScope.class);
        verify(app).retryGrant(scope.capture(),eq("EX1"),eq(new RecoveryCommand(0,"核实原单")),eq("RETRY1"));
        org.assertj.core.api.Assertions.assertThat(scope.getValue().platformActor()).isTrue();
        org.assertj.core.api.Assertions.assertThat(scope.getValue().entityNo()).isEqualTo("M1");
    }
    @Test void anonymousAndDetailWithoutEntityNeverReachPay() throws Exception {
        var mvc=MockMvcBuilders.webAppContextSetup(web).apply(SecurityMockMvcConfigurers.springSecurity()).build();
        mvc.perform(get("/ops/pay/entitlement-exceptions").param("entityNo","M1")).andExpect(status().is4xxClientError());
        var token=TestLogin.admin(mvc,json);
        mvc.perform(get("/ops/pay/entitlement-exceptions/EX1").header("Authorization","Bearer "+token))
                .andExpect(jsonPath("$.code").value(10400));
        verifyNoInteractions(app);
    }
    @Test void ordinarySupportCannotReadOrRecoverExceptions() throws Exception {
        var mvc=MockMvcBuilders.webAppContextSetup(web).apply(SecurityMockMvcConfigurers.springSecurity()).build();
        var token=TestLogin.operator(mvc,json,"support","support123");
        var read=mvc.perform(get("/ops/pay/entitlement-exceptions").param("entityNo","M1")
                .header("Authorization","Bearer "+token)).andReturn().getResponse();
        org.assertj.core.api.Assertions.assertThat(json.readTree(read.getContentAsString()).path("code").asInt()).isEqualTo(10403);
        var retry=mvc.perform(post("/ops/pay/entitlement-exceptions/EX1/retry-grant").param("entityNo","M1")
                .header("Authorization","Bearer "+token).header("Idempotency-Key","K1")
                .contentType("application/json").content(json.writeValueAsString(new RecoveryCommand(0,"核实原单"))))
                .andReturn().getResponse();
        org.assertj.core.api.Assertions.assertThat(json.readTree(retry.getContentAsString()).path("code").asInt()).isEqualTo(10403);
        var dead=mvc.perform(get("/ops/pay/entitlement-dead-letters").header("Authorization","Bearer "+token))
                .andReturn().getResponse();
        org.assertj.core.api.Assertions.assertThat(json.readTree(dead.getContentAsString()).path("code").asInt()).isEqualTo(10403);
        verifyNoInteractions(app);
    }
    @Test void administratorCanOpenTheAggregateExceptionQueueWithoutChoosingAMerchantFirst() throws Exception {
        var mvc = MockMvcBuilders.webAppContextSetup(web).apply(SecurityMockMvcConfigurers.springSecurity()).build();
        var token = TestLogin.admin(mvc, json);
        when(app.listAcross(any(), eq(null), eq(null), eq(1L), eq(20L)))
                .thenReturn(new PageData<>(List.of(), 0, 1, 20));
        mvc.perform(get("/ops/pay/entitlement-exceptions").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.total").value(0));
        when(app.deadLetters(any(),eq(null),eq(20))).thenReturn(
                new ai.neargo.shop.pay.entitlement.dto.ExceptionViews.DeadLetterPage(List.of(),null));
        mvc.perform(get("/ops/pay/entitlement-dead-letters").header("Authorization","Bearer "+token))
                .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.nextCursor").doesNotExist());
        verify(app).deadLetters(org.mockito.ArgumentMatchers.argThat(scope -> scope.entityNos()==null),eq(null),eq(20));
        verify(app).listAcross(org.mockito.ArgumentMatchers.argThat(scope -> scope.entityNos() == null),
                eq(null), eq(null), eq(1L), eq(20L));
    }
}
