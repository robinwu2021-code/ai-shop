package ai.neargo.shop.scenario;

import ai.neargo.shop.spi.user.UserQueryPort;
import ai.neargo.shop.support.TestLogin;
import ai.neargo.shop.user.service.AddressService;
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
 * 门牌号（V319）与地址簿上限。
 *
 * <p>门牌那两条防的是**同一类错误的两半**：加了一列，只改写入、忘了读出。
 * 那种缺陷不报错、不崩，地址簿页面上一切正常 —— 只有骑手拿到的地址少了最后 50 米。
 */
@SpringBootTest
@ActiveProfiles("test")
class AddressHouseNoLimitTest {

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private UserQueryPort userPort;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
                        .springSecurity())
                .build();
    }

    private String save(String token, String tag, String houseNo) throws Exception {
        return mvc().perform(post("/mp/user/address")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"张三\",\"phone\":\"17700188002\",\"region\":\"浙江省杭州市西湖区\","
                                + "\"province\":\"浙江省\",\"city\":\"杭州市\",\"district\":\"西湖区\","
                                + "\"detail\":\"阳光里小区\""
                                + (houseNo == null ? "" : ",\"houseNo\":\"" + houseNo + "\"")
                                + ",\"tag\":\"" + tag + "\"}"))
                .andReturn().getResponse().getContentAsString();
    }

    private String idOf(String listBody, String tag) {
        for (var a : json.readTree(listBody).get("data")) {
            if (tag.equals(a.path("tag").asText())) return a.path("addressId").asText();
        }
        throw new IllegalStateException("没找到 tag=" + tag + " 的地址：" + listBody);
    }

    private String userNoOf(String token) throws Exception {
        String body = mvc().perform(get("/mp/user/profile").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("cUserNo").asString();
    }

    @Test
    @DisplayName("★★★ 门牌号存得进也读得回 —— 只写不读那一半最容易漏")
    void houseNoRoundTrips() throws Exception {
        String token = TestLogin.consumerByWechat(mvc(), json, "wx-house-1");
        String body = save(token, "家", "3 幢 2 单元 601");
        for (var a : json.readTree(body).get("data")) {
            if ("家".equals(a.path("tag").asText())) {
                assertThat(a.path("houseNo").asText())
                        .as("写进去了但出参里没有 = 端上永远读出空，而页面看不出区别")
                        .isEqualTo("3 幢 2 单元 601");
                return;
            }
        }
        throw new AssertionError("没找到刚存的地址：" + body);
    }

    @Test
    @DisplayName("★★★ 下单快照里必须带着门牌 —— 少了它骑手就少了最后 50 米")
    void snapshotCarriesHouseNo() throws Exception {
        String token = TestLogin.consumerByWechat(mvc(), json, "wx-house-2");
        String listBody = save(token, "家", "3 幢 2 单元 601");
        String addressId = idOf(listBody, "家");

        var receiver = userPort.receiverOf(userNoOf(token), addressId).orElseThrow();
        assertThat(receiver.address())
                .as("快照是省市区 + 地址主体 + 门牌拼出来的，漏掉门牌不会报错，只会送错门")
                .contains("阳光里小区")
                .endsWith("3 幢 2 单元 601");
    }

    @Test
    @DisplayName("★★ 没填门牌的老地址照旧能存，快照末尾不留孤零零的尾巴")
    void legacyAddressWithoutHouseNoStillWorks() throws Exception {
        String token = TestLogin.consumerByWechat(mvc(), json, "wx-house-3");
        String listBody = save(token, "家", null);
        String addressId = idOf(listBody, "家");

        var receiver = userPort.receiverOf(userNoOf(token), addressId).orElseThrow();
        assertThat(receiver.address())
                .as("后端刻意不把门牌设成必填：老版本 App 压根不发这个字段")
                .endsWith("阳光里小区");
    }

    @Test
    @DisplayName("★★★ 地址簿满了要拒新增，但**永远放行编辑**")
    void limitBlocksCreateButNotEdit() throws Exception {
        String token = TestLogin.consumerByWechat(mvc(), json, "wx-limit-1");
        String last = null;
        for (int i = 0; i < AddressService.MAX_ADDRESSES; i++) {
            last = save(token, "T" + i, "601");
        }
        String overflow = save(token, "满了", "601");
        assertThat(json.readTree(overflow).path("code").asInt())
                .as("第 " + (AddressService.MAX_ADDRESSES + 1) + " 条要被拒")
                .isNotZero();

        /*
         * 编辑必须放行。上限是后加的，存量用户可能本来就超了 ——
         * 拦住编辑等于让他连自己的手机号都改不了，而那跟「地址太多」毫无关系。
         */
        String editId = idOf(last, "T0");
        String edited = mvc().perform(post("/mp/user/address")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"addressId\":\"" + editId + "\",\"name\":\"李四\","
                                + "\"phone\":\"17700188003\",\"region\":\"浙江省杭州市西湖区\","
                                + "\"detail\":\"阳光里小区\",\"houseNo\":\"602\",\"tag\":\"T0\"}"))
                .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(edited).path("code").asInt())
                .as("到了上限之后仍然要能改已有的那些")
                .isZero();
    }
}
