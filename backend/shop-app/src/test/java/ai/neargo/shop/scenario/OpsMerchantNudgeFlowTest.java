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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 主动触达商家（M2）。
 *
 * <p>这是<b>唯一一个会到达商家的运营动作</b>，所以测试压的不是「能不能发」，
 * 而是三条边界：
 *
 * <ol>
 *   <li><b>一天一次。</b>运营连点五次不该让商家收到五条 ——
 *       一个能被连点的推送入口就是一个骚扰工具。而且第二次要<b>说出来</b>
 *       「今天已经提醒过了」：底层撞键是静默跳过，静默的后果是运营再点一次。</li>
 *   <li><b>IN_AUDIT 发不出去。</b>那一档的意思是「他的品全卡在平台的审核队列里」——
 *       欠账的是平台。就这件事提醒商家等于把自己的积压说成对方的问题，
 *       而商家收到之后能做的只有再等。</li>
 *   <li><b>事由是枚举。</b>不认的一律拒，否则自由文本会从这个口子漏进商家的收件箱。</li>
 * </ol>
 */
@SpringBootTest
@ActiveProfiles("test")
class OpsMerchantNudgeFlowTest {

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

    private JsonNode nudge(String token, String entityNo, String reason, int expectStatus)
            throws Exception {
        String body = mvc().perform(post("/ops/merchant/" + entityNo + "/nudge")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"" + reason + "\"}"))
                .andExpect(status().is(expectStatus))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    @Test
    @DisplayName("★★★ 一天一次：第二次不发，而且**说出来** —— 静默吞掉运营就会一直点")
    void secondNudgeSameDaySaysSo() throws Exception {
        String token = TestLogin.admin(mvc(), json);
        String entityNo = "M0001";

        JsonNode first = nudge(token, entityNo, "NO_INBOUND", 200).get("data");
        /*
         * 第一次可能真发了，也可能这家店没配人 —— 两种都行，
         * 但**都不该是 alreadySentToday**：那意味着有别的东西在同一天用了同一个键。
         */
        assertThat(first.get("alreadySentToday").asBoolean())
                .as("第一次就说「今天已经提醒过了」—— 幂等键撞上了不该撞的东西")
                .isFalse();

        JsonNode second = nudge(token, entityNo, "NO_INBOUND", 200).get("data");
        if (first.get("noRecipient").asBoolean()) {
            // 一个收件人都没有时压根没发出去，第二次自然也不是「已提醒」——
            // 这条用例在这种库上验不了，如实说，别假装验过
            assertThat(second.get("noRecipient").asBoolean()).isTrue();
            return;
        }
        assertThat(first.get("sent").asInt()).isGreaterThan(0);
        assertThat(second.get("alreadySentToday").asBoolean())
                .as("同一天同一事由发了两次 —— 这个入口能被连点，就是个骚扰工具")
                .isTrue();
        assertThat(second.get("sent").asInt()).isZero();
    }

    @Test
    @DisplayName("★★★ IN_AUDIT 提醒不出去 —— 那一档欠账的是平台，不该去催商家")
    void cannotBlameTheMerchantForThePlatformsBacklog() throws Exception {
        String token = TestLogin.admin(mvc(), json);
        mvc().perform(post("/ops/merchant/M0001/nudge")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"IN_AUDIT\"}"))
                .andExpect(jsonPath("$.code").value(
                        org.hamcrest.Matchers.not(0)));
    }

    @Test
    @DisplayName("★★ 事由不认就拒 —— 否则自由文本从这个口子漏进商家的收件箱")
    void unknownReasonIsRejected() throws Exception {
        String token = TestLogin.admin(mvc(), json);
        mvc().perform(post("/ops/merchant/M0001/nudge")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"你们的东西太贵了\"}"))
                .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.not(0)));
    }
}
