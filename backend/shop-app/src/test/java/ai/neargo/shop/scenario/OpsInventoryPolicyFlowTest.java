package ai.neargo.shop.scenario;

import ai.neargo.shop.support.TestLogin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 进销存平台规则（M7）。
 *
 * <p>这一个数决定「什么时候可以把真相源切到进销存」。此前它<b>根本不存在</b>：
 * 判据写的是「对差连续为零」，而连续多少是空的 ——
 * 于是「够了没有」这个问题谁都答不了。
 *
 * <p>三条断言：<b>没配过时给默认值</b>（不是 0，0 意味着「随时可以切」）、
 * <b>越界拒</b>、<b>写完读得回来</b>。
 */
@SpringBootTest
@ActiveProfiles("test")
class OpsInventoryPolicyFlowTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper json;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
                        .springSecurity())
                .build();
    }

    private int read(String token) throws Exception {
        String body = mvc().perform(get("/ops/inventory/policy")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("reconCleanStreakRequired").asInt();
    }

    @Test
    @DisplayName("★★★ 没配过时不是 0 —— 0 意味着「随时可以切真相源」，而那正是这个数要防的")
    void unsetMeansAPositiveDefaultNotZero() throws Exception {
        assertThat(read(TestLogin.admin(mvc(), json)))
                .as("没配过就给 0 的话，「连续 0 轮为零」恒成立，这道闸等于不存在")
                .isGreaterThan(0);
    }

    @Test
    @DisplayName("★★ 写完读得回来，且越界拒 —— 90 轮以上不是判据是拖延")
    void savesAndRejectsOutOfRange() throws Exception {
        String token = TestLogin.admin(mvc(), json);
        int before = read(token);
        try {
            mvc().perform(post("/ops/inventory/policy")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reconCleanStreakRequired\":14}"))
                    .andExpect(jsonPath("$.code").value(0));
            assertThat(read(token)).isEqualTo(14);

            for (String bad : new String[]{"0", "-1", "9999"}) {
                mvc().perform(post("/ops/inventory/policy")
                                .header("Authorization", "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"reconCleanStreakRequired\":" + bad + "}"))
                        .andExpect(jsonPath("$.code")
                                .value(org.hamcrest.Matchers.not(0)));
            }
            assertThat(read(token)).as("越界那几次不该改动已存的值").isEqualTo(14);
        } finally {
            // 还原：这是共享设置，留着会让别的用例读到 14
            mvc().perform(post("/ops/inventory/policy")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"reconCleanStreakRequired\":" + before + "}"));
        }
    }
}
