package ai.neargo.shop.scenario;

import ai.neargo.shop.support.TestLogin;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 当前生效位置（多位置单生效）。
 *
 * <p>三条规则各防一件具体的事，都不是「接口能通」那种验证：
 * <ol>
 *   <li><b>切换不动 is_default</b> —— 两者回答不同问题。合成一个的后果是
 *       「给父母下单时切到父母家看货，默认收货人也跟着变了」</li>
 *   <li><b>没设过返回 null 而不是报错</b> —— 新用户就是这个状态，
 *       让首页因此打不开是把正常状态当成故障</li>
 *   <li><b>生效位置指向的地址被删了，当作没有</b> —— 删自己的地址是正常操作，
 *       不该让它把首页变砖</li>
 * </ol>
 */
@SpringBootTest
@ActiveProfiles("test")
class ActiveAddressFlowTest {

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

    private String wxUser(String openId) throws Exception {
        return TestLogin.consumerByWechat(mvc(), json, openId);
    }

    /** 存一条地址，返回它的 addressId */
    private String saveAddress(String token, String tag, boolean isDefault) throws Exception {
        String body = mvc().perform(post("/mp/user/address")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"张三\",\"phone\":\"17700188001\",\"region\":\"浙江省杭州市西湖区\","
                                + "\"province\":\"浙江省\",\"city\":\"杭州市\",\"district\":\"西湖区\","
                                + "\"detail\":\"文一西路 1 号\",\"isDefault\":" + isDefault
                                + ",\"tag\":\"" + tag + "\"}"))
                .andReturn().getResponse().getContentAsString();
        var list = json.readTree(body).get("data");
        for (var a : list) {
            if (tag.equals(a.path("tag").asText())) return a.path("addressId").asText();
        }
        throw new IllegalStateException("没找到刚存的地址：" + body);
    }

    private String activeAddressId(String token) throws Exception {
        String body = mvc().perform(get("/mp/user/active-address")
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        var data = json.readTree(body).get("data");
        return data == null || data.isNull() ? null : data.path("addressId").asText();
    }

    private String defaultAddressId(String token) throws Exception {
        String body = mvc().perform(get("/mp/user/address")
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        for (var a : json.readTree(body).get("data")) {
            if (a.path("isDefault").asBoolean()) return a.path("addressId").asText();
        }
        return null;
    }

    private int switchTo(String token, String addressId) throws Exception {
        String body = mvc().perform(post("/mp/user/active-address/" + addressId)
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).path("code").asInt();
    }

    @Test
    @DisplayName("★★★ 切换生效位置**不动** is_default —— 两者回答的是不同问题")
    void switchingActiveDoesNotTouchDefault() throws Exception {
        String token = wxUser("wx-active-1");
        String home = saveAddress(token, "家", true);
        String office = saveAddress(token, "公司", false);

        assertThat(switchTo(token, office)).isZero();

        assertThat(activeAddressId(token)).isEqualTo(office);
        assertThat(defaultAddressId(token))
                .as("切生效位置时，默认收货地址必须纹丝不动")
                .isEqualTo(home);
    }

    @Test
    @DisplayName("★★★ 没设过 → data 为 null，不是报错（新用户就是这个状态）")
    void noActiveAddressIsNotAnError() throws Exception {
        String token = wxUser("wx-active-2");
        assertThat(activeAddressId(token))
                .as("新用户一个位置都没有，首页要照常有东西看")
                .isNull();
    }

    @Test
    @DisplayName("★★★ 生效位置被删掉 → 当作没有，不让首页变砖")
    void deletedActiveAddressDegradesToNull() throws Exception {
        String token = wxUser("wx-active-3");
        String a = saveAddress(token, "家", true);
        assertThat(switchTo(token, a)).isZero();

        mvc().perform(post("/mp/user/address/" + a + "/archive")
                .header("Authorization", "Bearer " + token));

        assertThat(activeAddressId(token))
                .as("删自己的地址是正常操作，不该让它把首页变砖")
                .isNull();
    }

    @Test
    @DisplayName("★★ 切到别人的地址 → 拒绝（换个 id 就读到别人地址簿是最典型的越权）")
    void cannotSwitchToSomeoneElsesAddress() throws Exception {
        String other = wxUser("wx-active-4b");
        String his = saveAddress(other, "他家", true);
        String mine = wxUser("wx-active-4a");

        assertThat(switchTo(mine, his)).isNotZero();
        assertThat(activeAddressId(mine)).isNull();
    }
}
