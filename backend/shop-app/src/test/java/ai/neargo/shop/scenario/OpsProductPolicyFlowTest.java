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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 建品规则（商品①）。
 *
 * <p><b>最要紧的一条是默认值。</b>这三条规则一旦生效，命中的存量商品下次提审全会被拦 ——
 * 而平台上有 200 个 SPU、194 个正卡在审核里。默认打开等于在没人预告的情况下
 * 让一批商家的提交突然失败，而他们只会看到一个自己没做错什么的报错。
 *
 * <p>第二条守的是「下限比上限还大」：那时候<b>任何标题都提交不了</b>，
 * 而报错会说「标题太短」—— 一个永远改不对的提示。
 */
@SpringBootTest
@ActiveProfiles("test")
class OpsProductPolicyFlowTest {

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

    private JsonNode read(String token) throws Exception {
        String body = mvc().perform(get("/ops/product/policy")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data");
    }

    @Test
    @DisplayName("★★★ 默认全关 —— 默认打开等于让一批商家的提交在没人预告时突然失败")
    void defaultsPreserveTodaysBehaviour() throws Exception {
        JsonNode p = read(TestLogin.admin(mvc(), json));
        assertThat(p.get("requireCover").asBoolean())
                .as("必填主图默认开着 —— 194 件在审的存量商品下次提审会被拦，而没人预告过")
                .isFalse();
        assertThat(p.get("titleMinLength").asInt())
                .as("标题下限默认非 0 —— 同上，且报错会说「标题太短」")
                .isZero();
        assertThat(p.get("titleMaxLength").asInt()).isZero();
    }

    @Test
    @DisplayName("★★ 下限大于上限要拒 —— 那时候任何标题都提交不了，而提示说「太短」")
    void rejectsInvertedRange() throws Exception {
        String token = TestLogin.admin(mvc(), json);
        mvc().perform(post("/ops/product/policy")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requireCover\":false,\"titleMinLength\":50,\"titleMaxLength\":10}"))
                .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.not(0)));

        // 越界那次不该改动已存的值
        assertThat(read(token).get("titleMinLength").asInt()).isZero();
    }

    @Test
    @DisplayName("★★ 写完读得回来")
    void savesAndReadsBack() throws Exception {
        String token = TestLogin.admin(mvc(), json);
        try {
            mvc().perform(post("/ops/product/policy")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"requireCover\":true,\"titleMinLength\":4,\"titleMaxLength\":60}"))
                    .andExpect(jsonPath("$.code").value(0));
            JsonNode p = read(token);
            assertThat(p.get("requireCover").asBoolean()).isTrue();
            assertThat(p.get("titleMinLength").asInt()).isEqualTo(4);
            assertThat(p.get("titleMaxLength").asInt()).isEqualTo(60);
        } finally {
            // 还原：这是共享设置，留着会让别的用例里的提审突然要求主图
            mvc().perform(post("/ops/product/policy")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"requireCover\":false,\"titleMinLength\":0,\"titleMaxLength\":0}"));
        }
    }
}
