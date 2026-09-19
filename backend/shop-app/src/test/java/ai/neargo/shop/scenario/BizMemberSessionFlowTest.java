package ai.neargo.shop.scenario;

import ai.neargo.shop.support.TestLogin;
import ai.neargo.shop.common.OtpStore;
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

/**
 * 会员一整套 B 端接口，**带着真实的商家会话**走一遍（2026-09-19 真机发现）。
 *
 * <p>商家会话的数据域是 SELF（商家账号号），而 {@code mbr_*} 只按 {@code entity_no} 登记了 MERCHANT ——
 * 数据域 fail-closed：锚点对不上时拼的是 {@code 1=0}。于是<b>写得进去、读不出来</b>：
 * 新建的标签列表里看不见，商家再建一次同名的就撞唯一键（500）；添加会员写入之后回读报「不存在」。
 *
 * <p>其余会员用例都是直接调 Service、不带会话（{@code DataScopeContext} 为空 → 放行），
 * 所以一路全绿。这一类只有走过滤器、带着 btk_ 令牌才测得到。
 */
@SpringBootTest
@ActiveProfiles("test")
class BizMemberSessionFlowTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private OtpStore otpStore;

    private static int seq = 7700;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    /** 入驻 → 运营通过 → 换 B 端令牌 */
    private String merchant() throws Exception {
        String phone = "1260077" + String.format("%04d", ++seq);
        String user = TestLogin.consumer(mvc(), json, otpStore, phone);
        String body = mvc().perform(post("/mp/merchant/apply").header("Authorization", "Bearer " + user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"会员会话店" + seq + "\",\"subject\":\"INDIVIDUAL_BIZ\","
                                + "\"contactName\":\"张三\",\"contactPhone\":\"13900000000\","
                                + "\"category\":\"食品\",\"serviceScope\":\"COMMUNITY\","
                                + "\"communityNos\":[\"CM001\"]}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        String applyNo = json.readTree(body).get("data").get("applyNo").asString();
        String bd = TestLogin.operator(mvc(), json, "bd", "bd123");
        mvc().perform(post("/ops/merchant/apply/" + applyNo + "/audit").header("Authorization", "Bearer " + bd)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"approved\":true}"))
                .andExpect(jsonPath("$.code").value(0));
        return TestLogin.merchantOwner(mvc(), json, otpStore, phone);
    }

    private JsonNode call(String method, String path, String token, String body) throws Exception {
        var req = "GET".equals(method) ? get(path) : post(path);
        req.header("Authorization", "Bearer " + token);
        if (body != null) {
            req.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return json.readTree(mvc().perform(req).andReturn().getResponse().getContentAsString());
    }

    @Test
    @DisplayName("★★★ 新建的标签在列表里看得见；同名再建返回同一个，不是 500")
    void tagCreatedIsListedAndDuplicateReturnsSame() throws Exception {
        String token = merchant();
        JsonNode created = call("POST", "/biz/member-tags", token, "{\"name\":\"爱囤货\"}");
        assertThat(created.get("code").asInt()).isZero();
        String tagNo = created.get("data").get("tagNo").asString();

        JsonNode list = call("GET", "/biz/member-tags", token, null);
        assertThat(list.get("code").asInt()).isZero();
        assertThat(list.get("data").findValuesAsString("tagNo")).as("新建的标签要在列表里").contains(tagNo);

        JsonNode again = call("POST", "/biz/member-tags", token, "{\"name\":\"爱囤货\"}");
        assertThat(again.get("code").asInt()).as("同名再建：返回已有的那个，不撞唯一键").isZero();
        assertThat(again.get("data").get("tagNo").asString()).isEqualTo(tagNo);
    }

    @Test
    @DisplayName("★★★ 添加会员（手工录入）成功，并出现在名单与统计里")
    void enrollIsReadBackInSession() throws Exception {
        String token = merchant();
        JsonNode r = call("POST", "/biz/members", token, "{\"phone\":\"13711" + String.format("%06d", seq) + "\"}");
        assertThat(r.get("code").asInt()).as("写入后回读不能是「不存在」").isZero();
        String memberNo = r.get("data").get("memberNo").asString();

        JsonNode list = call("GET", "/biz/members", token, null);
        assertThat(list.get("code").asInt()).isZero();
        assertThat(list.get("data").findValuesAsString("memberNo")).contains(memberNo);

        JsonNode detail = call("GET", "/biz/members/" + memberNo, token, null);
        assertThat(detail.get("code").asInt()).isZero();
    }

    @Test
    @DisplayName("★★★ 端上真实请求体：人群试算与保存只带 tagNos（不带 page/size）也要收得下")
    void segmentPreviewAcceptsClientBody() throws Exception {
        String token = merchant();
        String tagNo = call("POST", "/biz/member-tags", token, "{\"name\":\"熟客\"}").get("data").get("tagNo").asString();
        // 与 b-app customers 页 saveAsSegment 发出的形状一致：rule 里只有 level / tagNos
        String rule = "{\"tagNos\":[\"" + tagNo + "\"]}";
        JsonNode pv = call("POST", "/biz/member-segments/preview", token, "{\"rule\":" + rule + "}");
        assertThat(pv.get("code").asInt()).as("试算：%s", pv).isZero();
        JsonNode saved = call("POST", "/biz/member-segments", token, "{\"name\":\"熟客人群\",\"rule\":" + rule + "}");
        assertThat(saved.get("code").asInt()).as("保存：%s", saved).isZero();
    }

    @Test
    @DisplayName("★★ 人群存了看得见；「发出去的」列表接口在会话里也能正常返回")
    void segmentsAndReachTasksReadInSession() throws Exception {
        String token = merchant();
        JsonNode seg = call("POST", "/biz/member-segments", token,
                "{\"name\":\"全部\",\"rule\":{\"page\":1,\"size\":0}}");
        assertThat(seg.get("code").asInt()).isZero();
        String segmentNo = seg.get("data").get("segmentNo").asString();

        JsonNode list = call("GET", "/biz/member-segments", token, null);
        assertThat(list.get("data").findValuesAsString("segmentNo")).as("存下的人群要在列表里").contains(segmentNo);

        JsonNode detail = call("GET", "/biz/member-segments/" + segmentNo, token, null);
        assertThat(detail.get("code").asInt()).as("人群详情").isZero();

        assertThat(call("GET", "/biz/member-reach/task", token, null).get("code").asInt()).isZero();
        assertThat(call("GET", "/biz/members/stats", token, null).get("code").asInt()).isZero();
    }
}
