package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import ai.neargo.shop.payclient.PaySourceLinkApi;
import ai.neargo.shop.support.TestLogin;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:ops-nearpay-board;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class OpsPayServiceOnboardingFlowTest {
    @Autowired WebApplicationContext web;
    @Autowired ObjectMapper json;
    @MockitoBean PaySourceLinkApi pay;
    private MockMvc mvc;

    @BeforeEach void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(web).apply(SecurityMockMvcConfigurers.springSecurity()).build();
        when(pay.onboardings(anyString(), anyString())).thenReturn(new PaySourceLinkApi.OnboardingProgress(
                "NONE", null, null, null, null, List.of()));
    }

    @Test void financeOperatorOnlyQueriesItsVisibleEntityAndKeywordCannotEscapeScope() throws Exception {
        String admin = TestLogin.admin(mvc, json);
        String username = "np-board-" + System.nanoTime() + "@neargo.ai";
        var created = json.readTree(mvc.perform(post("/ops/staffs").header("Authorization", "Bearer " + admin)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(java.util.Map.of(
                        "username", username, "realName", "进件看板域测试", "roles", List.of("FINANCE")))))
                .andExpect(jsonPath("$.code").value(0)).andReturn().getResponse().getContentAsString()).get("data");
        String staff = created.get("staff").get("staffNo").asString();
        mvc.perform(post("/ops/staffs/" + staff + "/scope").header("Authorization", "Bearer " + admin)
                .contentType(MediaType.APPLICATION_JSON).content("{\"merchantNo\":\"M0001\"}"))
                .andExpect(jsonPath("$.code").value(0));
        String token = TestLogin.operator(mvc, json, username, created.get("initialPassword").asString());
        clearInvocations(pay);
        mvc.perform(get("/ops/payment-onboardings").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].entityNo").value("M0001"));
        verify(pay).onboardings("MAIN", "M0001");
        verifyNoMoreInteractions(pay);
        clearInvocations(pay);
        mvc.perform(get("/ops/payment-onboardings").param("keyword", "M0002")
                .header("Authorization", "Bearer " + token)).andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(0));
        verifyNoInteractions(pay);
    }

    @Test void operatorPermissionIsEnforcedAndInvalidPagesNeverCallPay() throws Exception {
        String support = TestLogin.operator(mvc, json, "support", "support123");
        clearInvocations(pay);
        mvc.perform(get("/ops/payment-onboardings").header("Authorization", "Bearer " + support))
                .andExpect(jsonPath("$.code").value(10403));
        verifyNoInteractions(pay);
        String admin = TestLogin.admin(mvc, json);
        for (String query : List.of("?page=0", "?size=0", "?size=21")) {
            var response = mvc.perform(get("/ops/payment-onboardings" + query)
                    .header("Authorization", "Bearer " + admin)).andReturn().getResponse();
            assertThat(json.readTree(response.getContentAsString()).get("code").asInt()).isNotZero();
        }
        verifyNoInteractions(pay);
    }
}
