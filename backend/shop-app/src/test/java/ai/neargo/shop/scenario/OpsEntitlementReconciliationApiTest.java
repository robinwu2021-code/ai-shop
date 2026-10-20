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
import ai.neargo.shop.pay.entitlement.dto.ReconciliationViews.Check;
import ai.neargo.shop.pay.entitlement.dto.ReconciliationViews.RunView;
import ai.neargo.shop.pay.entitlement.dto.ReconciliationViews.DiffView;
import ai.neargo.shop.payclient.EntitlementReconciliationAppService;
import ai.neargo.shop.support.TestLogin;
import java.time.LocalDate;
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

/** Public DTO safety and permission decisions are exercised through the real MVC and security chain. */
@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:ops-ent-reconciliation-api;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class OpsEntitlementReconciliationApiTest {
    @Autowired WebApplicationContext web;
    @Autowired ObjectMapper json;
    @MockitoBean EntitlementReconciliationAppService app;
    private final Check check=new Check("MONEY","CNY",LocalDate.of(2026,10,19),"UTC","",20);
    @Test void fourRoutesKeepScopeRunLabelsAndSafeDifferenceFields() throws Exception {
        var mvc=MockMvcBuilders.webAppContextSetup(web).apply(SecurityMockMvcConfigurers.springSecurity()).build();
        var token=TestLogin.admin(mvc,json);
        var view=new RunView("RR1","M1","MONEY","COMPLETED",1,1,null,check.bizDate(),"UTC","CNY");
        when(app.check(any(),eq(check),eq("K1"))).thenReturn(view);
        when(app.get(any(),eq("RR1"))).thenReturn(view);
        when(app.list(any(),eq(null),eq(1L),eq(20L))).thenReturn(new PageData<>(List.of(view),1,1,20));
        when(app.differences(any(),eq("RR1"),eq(1L),eq(20L))).thenReturn(new PageData<>(List.of(
                new DiffView("RD1","RR1","M1","A1","MONEY_MISMATCH","OPEN")),1,1,20));
        String base="/ops/pay/entitlement-reconciliations";
        mvc.perform(post(base).param("entityNo","M1").header("Authorization","Bearer "+token)
                .header("Idempotency-Key","K1").contentType("application/json").content(json.writeValueAsString(check)))
                .andExpect(jsonPath("$.data.currency").value("CNY"));
        mvc.perform(get(base).param("entityNo","M1").header("Authorization","Bearer "+token))
                .andExpect(jsonPath("$.data.records[0].reconNo").value("RR1"));
        mvc.perform(get(base+"/RR1").param("entityNo","M1").header("Authorization","Bearer "+token))
                .andExpect(jsonPath("$.data.bizDate").value("2026-10-19"));
        mvc.perform(get(base+"/RR1/differences").param("entityNo","M1").header("Authorization","Bearer "+token))
                .andExpect(jsonPath("$.data.records[0].diffKind").value("MONEY_MISMATCH"))
                .andExpect(jsonPath("$.data.records[0].expectedSnapshot").doesNotExist());
        verify(app).check(any(),eq(check),eq("K1"));
    }
    @Test void anonymousSupportMissingScopeAndInvalidCurrencyCannotStartRun() throws Exception {
        var mvc=MockMvcBuilders.webAppContextSetup(web).apply(SecurityMockMvcConfigurers.springSecurity()).build();
        String base="/ops/pay/entitlement-reconciliations";
        mvc.perform(get(base).param("entityNo","M1")).andExpect(status().is4xxClientError());
        var support=TestLogin.operator(mvc,json,"support","support123");
        mvc.perform(get(base).param("entityNo","M1").header("Authorization","Bearer "+support))
                .andExpect(jsonPath("$.code").value(10403));
        mvc.perform(post(base).param("entityNo","M1").header("Authorization","Bearer "+support)
                .header("Idempotency-Key","K1").contentType("application/json").content(json.writeValueAsString(check)))
                .andExpect(jsonPath("$.code").value(10403));
        var admin=TestLogin.admin(mvc,json);
        mvc.perform(get(base).header("Authorization","Bearer "+admin)).andExpect(jsonPath("$.code").value(10400));
        mvc.perform(post(base).param("entityNo","M1").header("Authorization","Bearer "+admin)
                .header("Idempotency-Key","K1").contentType("application/json")
                .content(json.writeValueAsString(check).replace("CNY","bad")))
                .andExpect(jsonPath("$.code").value(10400));
        verifyNoInteractions(app);
    }
}
