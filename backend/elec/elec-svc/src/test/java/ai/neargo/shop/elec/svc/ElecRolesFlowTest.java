package ai.neargo.shop.elec.svc;

import ai.neargo.elec.api.ElecInternal;
import ai.neargo.shop.elec.entity.ElcStock;
import ai.neargo.shop.elec.mapper.ElecMappers.StockMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 一个账号、两种身份：{@code /elec/me}、自己的求购不派给自己、暂停只关供应商面。
 * 对账表见 docs/technical/TDD-元器件-接口总览与双角色.md §六 —— 方法名前的 ACn 就是那张表的行。
 */
@SpringBootTest(classes = ElecApplication.class)
@ActiveProfiles("test")
@Import(FakeMainSystem.Config.class)
class ElecRolesFlowTest {

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private FakeMainSystem main;
    @Autowired
    private StockMapper stockMapper;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @Test
    @DisplayName("AC1 ★★★ /elec/me：还不是供应商 → supplier 为 null、角标 0；没绑手机也能调（它要告诉端上「你还没绑」）")
    void ac1_meForPlainBuyer() throws Exception {
        String buyer = main.consumer("12600960001");
        JsonNode me = data(get("/elec/me"), buyer, null);
        assertThat(me.get("userNo").asString()).isEqualTo(main.userNoOf(buyer));
        assertThat(me.get("phoneBound").asBoolean()).isTrue();
        assertThat(absent(me.get("supplier"))).isTrue();
        assertThat(me.get("badges").get("dispatchPending").asInt()).isZero();

        JsonNode noPhone = data(get("/elec/me"), main.consumerWithoutPhone(), null);
        assertThat(noPhone.get("phoneBound").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("AC1 AC2 ★★★ /elec/me：成为供应商后不用重新登录就是供应商；角标有数、但一面的内容都不带")
    void ac1ac2_meForSupplier() throws Exception {
        Sup s = supplier("12600960002", "身份测试电子", "型号,数量\nRLA100,10\nRLA101,20\n");
        stockMapper.update(null, Wrappers.<ElcStock>lambdaUpdate().eq(ElcStock::getSupplierNo, s.no)
                .eq(ElcStock::getMpnRaw, "RLA100").set(ElcStock::getValidUntil, LocalDate.now().plusDays(3)));
        String buyer = main.consumer("12600960003");
        data(post("/elec/c/rfq"), buyer, "{\"lines\":[{\"mpn\":\"RLA101\",\"qty\":5}]}");

        JsonNode me = data(get("/elec/me"), s.token, null);
        assertThat(me.get("supplier").get("supplierNo").asString()).as("同一个令牌，不用重新登录").isEqualTo(s.no);
        assertThat(me.get("supplier").get("status").asString()).isEqualTo("ACTIVE");
        assertThat(me.get("badges").get("dispatchPending").asInt()).isEqualTo(1);
        assertThat(me.get("badges").get("stockExpiring").asInt()).isEqualTo(1);

        List<String> fields = new ArrayList<>();
        me.propertyNames().forEach(fields::add);
        assertThat(fields).as("只有身份与角标四样 —— 它是「两面数据分开走」的唯一例外，所以不许带内容")
                .containsExactlyInAnyOrder("userNo", "phoneBound", "supplier", "badges");
        assertThat(me.toString()).doesNotContain("RLA101").doesNotContain("12600960003");
    }

    @Test
    @DisplayName("AC3 ★★★ 自己的求购不派给自己：既是买家又有货的人询价，只派给别家")
    void ac3_noSelfDispatch() throws Exception {
        Sup self = supplier("12600960004", "自己电子", "型号,数量\nRLB200,100\n");
        Sup other = supplier("12600960005", "别家电子", "型号,数量\nRLB200,100\n");

        data(post("/elec/c/rfq"), self.token, "{\"lines\":[{\"mpn\":\"RLB200\",\"qty\":10}]}");
        assertThat(data(get("/elec/b/rfq"), self.token, null)).as("自己那一面收不到自己的求购").isEmpty();
        assertThat(data(get("/elec/b/rfq"), other.token, null)).as("别家照常收到").hasSize(1);
    }

    @Test
    @DisplayName("AC4 ★★ 运营手工指派时点了买家本人的供应商：挡住（运营多半没意识到是同一个人）")
    void ac4_opsCannotDispatchToBuyerSelf() throws Exception {
        Sup self = supplier("12600960006", "指派自己电子", "型号,数量\nRLC300,1\n");
        String rfqNo = data(post("/elec/c/rfq"), self.token, "{\"lines\":[{\"mpn\":\"RLC300NONE\",\"qty\":1}]}")
                .get("rfqNo").asString();
        String ops = main.operator(ElecInternal.PERM_RFQ_READ, ElecInternal.PERM_RFQ_QUOTE);
        JsonNode r = call(post("/elec/ops/rfq/" + rfqNo + "/line/1/dispatch"), ops,
                "{\"supplierNos\":[\"" + self.no + "\"]}");
        assertThat(r.get("code").asInt()).isEqualTo(10400);
    }

    @Test
    @DisplayName("AC5 ★★★ 暂停只关供应商面：被暂停的人照样能询价、接受报价；供应商面回 90004")
    void ac5_suspendedSupplierCanStillBuy() throws Exception {
        Sup s = supplier("12600960007", "被停但要买电子", "型号,数量\nRLD400,1\n");
        String ops = main.operator(ElecInternal.OPS_PERMS.toArray(String[]::new));
        data(post("/elec/ops/supplier/" + s.no + "/suspend"), ops, "{\"reason\":\"测试暂停\"}");

        assertThat(call(get("/elec/b/stock"), s.token, null).get("code").asInt()).isEqualTo(90004);
        String rfqNo = data(post("/elec/c/rfq"), s.token, "{\"lines\":[{\"mpn\":\"RLD999\",\"qty\":3}]}")
                .get("rfqNo").asString();
        data(post("/elec/ops/rfq/" + rfqNo + "/quote"), ops,
                "{\"validDays\":3,\"lines\":[{\"lineNo\":1,\"priceE6\":1000000,\"qty\":3}]}");
        assertThat(data(post("/elec/c/rfq/" + rfqNo + "/accept"), s.token, null).get("status").asString())
                .isEqualTo("ACCEPTED");
        assertThat(data(get("/elec/me"), s.token, null).get("badges").get("stockExpiring").asInt())
                .as("暂停中：供应商角标归零，免得点进去碰壁").isZero();
    }

    @Test
    @DisplayName("AC6 ★★★ 「询价有新报价」：来了报价 +1、打开详情清零；供应商改价不算新；第二家报价、平台报价都算新")
    void ac6_rfqNewOffersBadge() throws Exception {
        Sup s1 = supplier("12600960008", "新报价一号电子", "型号,数量\nRLE500,100\n");
        Sup s2 = supplier("12600960009", "新报价二号电子", "型号,数量\nRLE500,100\n");
        String buyer = main.consumer("12600960010");
        String rfqNo = data(post("/elec/c/rfq"), buyer, "{\"lines\":[{\"mpn\":\"RLE500\",\"qty\":10}]}")
                .get("rfqNo").asString();
        assertThat(newOffers(buyer)).as("还没人报").isZero();

        String d1 = data(get("/elec/b/rfq"), s1.token, null).get(0).get("dispatchNo").asString();
        data(post("/elec/b/rfq/" + d1 + "/quote"), s1.token, "{\"priceE6\":1000000,\"qtyAvailable\":10}");
        assertThat(newOffers(buyer)).isEqualTo(1);

        data(get("/elec/c/rfq/" + rfqNo), buyer, null);
        assertThat(newOffers(buyer)).as("打开详情就算看过").isZero();

        data(post("/elec/b/rfq/" + d1 + "/quote"), s1.token, "{\"priceE6\":900000,\"qtyAvailable\":10}");
        assertThat(newOffers(buyer)).as("改价不算新 —— 与「改价不打扰买家」一致").isZero();

        String d2 = data(get("/elec/b/rfq"), s2.token, null).get(0).get("dispatchNo").asString();
        data(post("/elec/b/rfq/" + d2 + "/quote"), s2.token, "{\"priceE6\":950000,\"qtyAvailable\":10}");
        assertThat(newOffers(buyer)).as("第二家报价算新").isEqualTo(1);
        data(get("/elec/c/rfq/" + rfqNo), buyer, null);

        String ops = main.operator(ElecInternal.PERM_RFQ_READ, ElecInternal.PERM_RFQ_QUOTE);
        data(post("/elec/ops/rfq/" + rfqNo + "/quote"), ops,
                "{\"validDays\":3,\"lines\":[{\"lineNo\":1,\"priceE6\":880000,\"qty\":10}]}");
        assertThat(newOffers(buyer)).as("平台报价也算新").isEqualTo(1);
        data(get("/elec/c/rfq/" + rfqNo), buyer, null);
        assertThat(newOffers(buyer)).isZero();
    }

    private int newOffers(String token) throws Exception {
        return data(get("/elec/me"), token, null).get("badges").get("rfqNewOffers").asInt();
    }

    // ── 造数与调用 ──────────────────────────────────────────────────────────

    private record Sup(String token, String no) {
    }

    private Sup supplier(String phone, String company, String csv) throws Exception {
        String token = main.consumer(phone);
        String no = data(post("/elec/b/supplier"), token, "{\"companyName\":\"" + company + "\"}")
                .get("supplierNo").asString();
        String body = mvc().perform(multipart("/elec/b/stock/upload")
                        .file(new MockMultipartFile("file", "s.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)))
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        JsonNode up = json.readTree(body);
        assertThat(up.get("code").asInt()).as(body).isZero();
        data(post("/elec/b/stock/batch/" + up.get("data").get("batchNo").asString() + "/apply"), token, null);
        return new Sup(token, no);
    }

    private static boolean absent(JsonNode n) {
        return n == null || n.isNull() || n.isMissingNode();
    }

    private JsonNode data(MockHttpServletRequestBuilder req, String token, String body) throws Exception {
        JsonNode r = call(req, token, body);
        assertThat(r.get("code").asInt()).as(r.toString()).isZero();
        return r.path("data");
    }

    private JsonNode call(MockHttpServletRequestBuilder req, String token, String body) throws Exception {
        if (token != null) {
            req.header("Authorization", "Bearer " + token);
        }
        if (body != null) {
            req.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return json.readTree(mvc().perform(req).andReturn().getResponse().getContentAsString());
    }
}
