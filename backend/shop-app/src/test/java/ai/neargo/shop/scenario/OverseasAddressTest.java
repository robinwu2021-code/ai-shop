package ai.neargo.shop.scenario;

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
 * 海外收货地址（V333）：**存得下、读得回、旧端改不坏**。
 *
 * <p><b>为什么要专门写这一条</b>：加列最常见的翻车法是「只改写入」——
 * 那三列会永远读回 null，而界面上看不出区别（海外地址被当成 CN 渲染），
 * 所有闸门也全绿。所以这里判的是**读回来的值**，不是「保存成功了」。
 *
 * <p>第二条判的是**旧端不会把它改坏**：老版本 App 不发这三个字段。
 * 服务层把 null 当成「这次不改」（与坐标同一口径）；要是当成默认值，
 * 一条海外地址被旧端改一次手机号，国家就悄悄变回 CN，
 * 而它的 province/city 还是用户填的 State/City —— 两边都错，且界面上没有痕迹。
 */
@SpringBootTest
@ActiveProfiles("test")
class OverseasAddressTest {

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private ObjectMapper json;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup
                        .SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    private JsonNode save(String token, String body) throws Exception {
        String res = mvc().perform(post("/mp/user/address")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(res).get("data");
    }

    /** 刚存的那条：列表里 addressId 最大的那个不可靠，按收件人名字找 */
    private static JsonNode byName(JsonNode list, String name) {
        for (JsonNode a : list) {
            if (name.equals(a.path("name").asString())) {
                return a;
            }
        }
        return null;
    }

    @Test
    @DisplayName("★★★ 存一条海外地址，三个新字段要**原样读回来**")
    void overseasFieldsRoundTrip() throws Exception {
        String token = ai.neargo.shop.support.TestLogin.consumerByWechat(mvc(), json, "overseas-addr-test");
        JsonNode list = save(token, """
                {"name":"Alex Wu","phone":"4155550123","region":"California",
                 "province":"California","city":"San Francisco","district":"",
                 "detail":"1 Market St","houseNo":"Apt 502","isDefault":false,"tag":"home",
                 "countryCode":"US","postalCode":"94105","phoneCc":"1"}
                """);
        JsonNode saved = byName(list, "Alex Wu");
        assertThat(saved).as("存进去了吗").isNotNull();

        /*
         * **判读回来的值，不判「保存成功了」。** 只改写入的话这三列永远读回 null，
         * 而保存接口照样返回 code=0 —— 那是这类缺陷唯一的样子。
         */
        assertThat(saved.path("countryCode").asString()).isEqualTo("US");
        assertThat(saved.path("postalCode").asString()).isEqualTo("94105");
        assertThat(saved.path("phoneCc").asString()).isEqualTo("1");

        // 对照量：再从列表接口读一次，走的是另一条组装路径
        String res = mvc().perform(get("/mp/user/address")
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        JsonNode again = byName(json.readTree(res).get("data"), "Alex Wu");
        assertThat(again).isNotNull();
        assertThat(again.path("countryCode").asString())
                .as("保存的出参对了、列表的出参没对 —— 两条组装路径要各自验")
                .isEqualTo("US");
    }

    @Test
    @DisplayName("★★★ 旧版本端不发这三个字段时**不许把它改回 CN**")
    void oldClientMustNotResetCountry() throws Exception {
        String token = ai.neargo.shop.support.TestLogin.consumerByWechat(mvc(), json, "overseas-addr-test");
        JsonNode list = save(token, """
                {"name":"Kenji Sato","phone":"9012345678","region":"Tokyo",
                 "province":"Tokyo","city":"Shibuya","district":"",
                 "detail":"2-1 Dogenzaka","houseNo":"7F","isDefault":false,"tag":"home",
                 "countryCode":"JP","postalCode":"150-0043","phoneCc":"81"}
                """);
        String id = byName(list, "Kenji Sato").path("addressId").asString();

        // 老版本 App 的请求体：没有 countryCode / postalCode / phoneCc
        JsonNode after = save(token, """
                {"addressId":"%s","name":"Kenji Sato","phone":"9012345679","region":"Tokyo",
                 "province":"Tokyo","city":"Shibuya","district":"",
                 "detail":"2-1 Dogenzaka","houseNo":"7F","isDefault":false,"tag":"home"}
                """.formatted(id));
        JsonNode row = byName(after, "Kenji Sato");
        assertThat(row.path("phone").asString()).as("这次改的是手机号").isEqualTo("9012345679");
        assertThat(row.path("countryCode").asString())
                .as("旧端改一次手机号就把国家抹成 CN —— 而 province/city 还是 Tokyo/Shibuya，"
                        + "两边都错且界面上没有痕迹")
                .isEqualTo("JP");
        assertThat(row.path("postalCode").asString()).isEqualTo("150-0043");
        assertThat(row.path("phoneCc").asString()).isEqualTo("81");
    }

    @Test
    @DisplayName("★★ 存量地址（不带这三个字段建的）读出来是 CN / 86")
    void legacyRowsDefaultToChina() throws Exception {
        String token = ai.neargo.shop.support.TestLogin.consumerByWechat(mvc(), json, "overseas-addr-test");
        JsonNode list = save(token, """
                {"name":"张三","phone":"13800138000","region":"广东省深圳市龙华区",
                 "province":"广东省","city":"深圳市","district":"龙华区",
                 "detail":"桂澜新村","houseNo":"3 栋 501","isDefault":false,"tag":"家"}
                """);
        JsonNode row = byName(list, "张三");
        assertThat(row.path("countryCode").asString())
                .as("建表默认值没生效 = 存量地址会读出 null，端上按「海外」渲染")
                .isEqualTo("CN");
        assertThat(row.path("phoneCc").asString()).isEqualTo("86");
    }
}
