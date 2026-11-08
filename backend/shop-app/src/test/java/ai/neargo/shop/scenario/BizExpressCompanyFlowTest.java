package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import ai.neargo.shop.common.OtpStore;
import ai.neargo.shop.support.ShippingMerchantFixture;
import ai.neargo.shop.support.TestLogin;
import ai.neargo.shop.trade.service.MerchantOrderService;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 商户 App 快递公司清单（TDD-商户App快递公司）：清单按门店所属主体的市场给，
 * 清单里的每一个码拿去发货、存发货设置都被接受，发货的市场取订单自己的门店。
 *
 * <p>两家店：大陆店的主体市场留空（列非空，存量是空串；回落部署默认 CN），台湾店的主体写 TW。
 */
@SpringBootTest
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BizExpressCompanyFlowTest {
    @Autowired private WebApplicationContext context;
    @Autowired private ObjectMapper json;
    @Autowired private OtpStore otp;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MerchantOrderService merchantOrders;
    private MockMvc mvc;
    private ShippingMerchantFixture.Scope cn;
    private ShippingMerchantFixture.Scope tw;
    private final String run = UUID.randomUUID().toString().substring(0, 8);

    @BeforeAll
    void merchants() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        cn = ShippingMerchantFixture.open(mvc, json, otp, "13400855001");
        tw = ShippingMerchantFixture.open(mvc, json, otp, "13400855002");
        jdbc.update("UPDATE mch_entity SET market='' WHERE entity_no=?", cn.merchant());
        jdbc.update("UPDATE mch_entity SET market='TW' WHERE entity_no=?", tw.merchant());
    }

    @Test
    @DisplayName("★★★ E1 成员读到本店清单，顺序即显示顺序")
    void member_reads_companies_in_display_order() throws Exception {
        JsonNode data = companies(cn);
        assertThat(codes(data)).containsExactly("SF", "ZTO", "YTO", "YD", "STO", "JTSD", "JD", "YZPY", "EMS",
                "DBL", "HTKY", "FWX", "UC", "ZJS");
        assertThat(data.path("companies").get(0).path("name").asString()).isEqualTo("顺丰速运");
    }

    @Test
    @DisplayName("★★★ E2/E10 主体没填市场：按部署默认回落，响应里的 market 是回落后的值")
    void entity_without_market_falls_back_to_deploy_default() throws Exception {
        assertThat(companies(cn).path("market").asString()).isEqualTo("CN");
    }

    @Test
    @DisplayName("★★★ E2/E5/E10 台湾店：只给台湾承运商、黑貓第一、轨迹不支持、不能叫快递")
    void tw_store_lists_tw_carriers_without_trace_or_pickup() throws Exception {
        JsonNode data = companies(tw);
        assertThat(data.path("market").asString()).isEqualTo("TW");
        assertThat(codes(data)).containsExactly("TCAT", "HCT", "TWPOST", "KERRYTJ", "PELICAN");
        assertThat(data.path("companies").get(0).path("name").asString()).isEqualTo("黑貓宅急便");
        for (JsonNode c : data.path("companies")) {
            assertThat(c.path("traceSupported").asBoolean(true)).as(c.toString()).isFalse();
        }
        assertThat(data.path("courierPickupAvailable").asBoolean(true)).isFalse();
    }

    @Test
    @DisplayName("★★★ E3 清单里的每一个码，发货与发货设置都收")
    void every_listed_code_is_accepted_by_ship_and_ship_setting() throws Exception {
        for (ShippingMerchantFixture.Scope s : List.of(cn, tw)) {
            for (String code : codes(companies(s))) {
                String sub = seed(s);
                assertThat(ship(s, sub, code)).as(s.store() + " ship " + code).isZero();
                assertThat(expressCompany(sub)).isEqualTo(code);
                JsonNode saved = saveCarrier(s, code);
                assertThat(saved.path("code").asInt()).as(s.store() + " ship-setting " + code + " " + saved)
                        .isZero();
                assertThat(saved.path("data").path("carrier").asString()).isEqualTo(code);
            }
        }
    }

    @Test
    @DisplayName("★★★ E3 台湾店送大陆码 → 10400；送「黑貓宅急便」→ 存 TCAT；送「顺丰速运」→ 10400")
    void tw_store_validates_by_its_own_market() throws Exception {
        assertThat(ship(tw, seed(tw), "SF")).isEqualTo(10400);
        String named = seed(tw);
        assertThat(ship(tw, named, "黑貓宅急便")).isZero();
        assertThat(expressCompany(named)).isEqualTo("TCAT");
        assertThat(ship(tw, seed(tw), "顺丰速运")).isEqualTo(10400);
        assertThat(saveCarrier(tw, "SF").path("code").asInt()).isEqualTo(10400);
        assertThat(saveCarrier(tw, "黑貓宅急便").path("data").path("carrier").asString()).isEqualTo("TCAT");
    }

    @Test
    @DisplayName("★★ E3 大陆店多接受本市场名称（只放宽不收紧）：送「顺丰速运」存 SF；台湾码仍 10400")
    void cn_store_normalizes_name_and_rejects_tw_code() throws Exception {
        String named = seed(cn);
        assertThat(ship(cn, named, "顺丰速运")).isZero();
        assertThat(expressCompany(named)).isEqualTo("SF");
        assertThat(ship(cn, seed(cn), "TCAT")).isEqualTo(10400);
    }

    @Test
    @DisplayName("★★★ E3 调用方门店号为空（叫快递取件回填）时，市场取订单自己的门店")
    void pickup_write_back_uses_order_store_market() {
        String sub = seed(tw);
        merchantOrders.ship(tw.merchant(), null, sub, "TW-PICK-" + run + "-" + sub.substring(2, 10), "TCAT");
        assertThat(expressCompany(sub)).isEqualTo("TCAT");
    }

    @Test
    @DisplayName("★★ E6 角色里既没有 biz:ship 也没有 biz:store 的店员读不到（70006）；有其一就读得到")
    void clerk_without_ship_or_store_is_forbidden() throws Exception {
        String cs = staff(cn, "13400855011", "CS");
        assertThat(code(get("/biz/express/companies"), cs, cn.store())).isEqualTo(70006);
        String courier = staff(cn, "13400855012", "COURIER");
        assertThat(code(get("/biz/express/companies"), courier, cn.store())).isZero();
    }

    // ---------------------------------------------------------------- helpers

    private JsonNode companies(ShippingMerchantFixture.Scope s) throws Exception {
        JsonNode env = json.readTree(mvc.perform(get("/biz/express/companies")
                        .header("Authorization", "Bearer " + s.token()).header("X-Store-No", s.store()))
                .andReturn().getResponse().getContentAsString());
        assertThat(env.path("code").asInt(-1)).as(env.toString()).isZero();
        return env.path("data");
    }

    private static List<String> codes(JsonNode data) {
        List<String> out = new ArrayList<>();
        data.path("companies").forEach(c -> out.add(c.path("code").asString()));
        return out;
    }

    private String seed(ShippingMerchantFixture.Scope s) {
        String sub = "SE" + UUID.randomUUID().toString().replace("-", "");
        jdbc.update("INSERT INTO ord_order (order_no,user_no,status,created_at,updated_at) "
                + "VALUES (?,?,'PAID',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", "O-" + sub, "EXP-BUYER");
        jdbc.update("INSERT INTO ord_sub_order (sub_order_no,order_no,user_no,entity_no,store_no,fulfillment,"
                + "status,created_at,updated_at) VALUES (?,?,?,?,?,'EXPRESS','WAIT_FULFILL',"
                + "CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", sub, "O-" + sub, "EXP-BUYER", s.merchant(), s.store());
        jdbc.update("INSERT INTO ord_item (sub_order_no,order_no,goods_no,sku_no,qty,is_gift,created_at,"
                + "updated_at) VALUES (?,?,'EXP-GOODS','EXP-SKU',1,FALSE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                sub, "O-" + sub);
        return sub;
    }

    private int ship(ShippingMerchantFixture.Scope s, String sub, String carrier) throws Exception {
        String body = mvc.perform(post("/biz/order/" + sub + "/ship")
                        .header("Authorization", "Bearer " + s.token()).header("X-Store-No", s.store())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(java.util.Map.of(
                                "expressNo", "EX" + run + sub.substring(2, 12), "expressCompany", carrier))))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).path("code").asInt(-1);
    }

    private JsonNode saveCarrier(ShippingMerchantFixture.Scope s, String carrier) throws Exception {
        return json.readTree(mvc.perform(put("/biz/store/" + s.store() + "/ship-setting")
                        .header("Authorization", "Bearer " + s.token()).header("X-Store-No", s.store())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(java.util.Map.of("carrier", carrier))))
                .andReturn().getResponse().getContentAsString());
    }

    private String expressCompany(String sub) {
        return jdbc.queryForObject("SELECT express_company FROM ord_sub_order WHERE sub_order_no=?",
                String.class, sub);
    }

    private String staff(ShippingMerchantFixture.Scope owner, String phone, String role) throws Exception {
        String staffNo = json.readTree(mvc.perform(post("/biz/staff")
                        .header("Authorization", "Bearer " + owner.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginPhone\":\"" + phone + "\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString())
                .get("data").get("mchAccountNo").asString();
        mvc.perform(post("/biz/staff/" + staffNo + "/store")
                        .header("Authorization", "Bearer " + owner.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"storeNo\":\"" + owner.store() + "\",\"role\":\"" + role + "\"}"))
                .andExpect(jsonPath("$.code").value(0));
        return TestLogin.merchantStaff(mvc, json, otp, phone);
    }

    private int code(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req,
                     String token, String store) throws Exception {
        String body = mvc.perform(req.header("Authorization", "Bearer " + token).header("X-Store-No", store))
                .andReturn().getResponse().getContentAsString();
        return body.isBlank() ? -1 : json.readTree(body).path("code").asInt(-1);
    }
}
