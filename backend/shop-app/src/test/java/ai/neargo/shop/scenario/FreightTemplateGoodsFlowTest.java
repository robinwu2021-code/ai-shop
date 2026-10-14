package ai.neargo.shop.scenario;

import ai.neargo.shop.support.TestLogin;
import ai.neargo.shop.support.TestStoreCategory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * 商品级运费模板（ADR-031 §2.4，TDD-下单按门店拆单与运费模板 AC5/AC7/AC8）。
 *
 * <p>下单时模板逐行解析：<b>商品指定的 ＞ 所属门店快递通道的 ＞ 平台默认</b>，实时读、不缓存。
 * 默认模板（种子 FT0001）首重 ¥8；这里另建一个首重 ¥3 的平台模板，三个数各不相同才分得清读的是哪一个。
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("商品级运费模板：商品 ＞ 门店 ＞ 默认；归档回落门店；B 端可设可清")
class FreightTemplateGoodsFlowTest {

    @Autowired private ai.neargo.shop.common.OtpStore otpStore;
    @Autowired private WebApplicationContext context;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @Test
    @DisplayName("★★★ 商品指定模板优先于门店；模板被归档后回落门店（默认 ¥8），不是 0 元")
    void goodsTemplateWinsAndArchivedFallsBack() throws Exception {
        String tpl = template("FT-GOODS-3", 300);
        String biz = merchant("12600350010", "商品级模板店");
        String goodsNo = onSaleGoods(biz, "指定了运费模板的面粉");
        save(biz, goodsNo, "\"freightTemplateNo\":\"" + tpl + "\"");

        assertThat(previewFreight("12600350011", goodsNo))
                .as("商品指定了首重 ¥3 的模板 —— 按门店（默认 ¥8）算就是没读到商品那一层")
                .isEqualTo(300L);

        jdbc.update("update ful_freight_template set archived_at=? where template_no=?",
                System.currentTimeMillis(), tpl);
        assertThat(previewFreight("12600350012", goodsNo))
                .as("指定的模板归档了：回落门店模板（默认 ¥8），不能变成 0 元")
                .isEqualTo(800L);
    }

    @Test
    @DisplayName("★★ B 端：能从平台模板里选、能清回「跟随门店」；不在用的模板号拒")
    void merchantSetsAndClears() throws Exception {
        String tpl = template("FT-GOODS-5", 500);
        String biz = merchant("12600350020", "设模板店");
        String goodsNo = onSaleGoods(biz, "设模板的货");

        JsonNode list = data(mvc().perform(get("/biz/freight-template/list")
                        .header("Authorization", "Bearer " + biz))
                .andReturn().getResponse().getContentAsString());
        assertThat(list.findValuesAsString("templateNo")).contains("FT0001", tpl);

        save(biz, goodsNo, "\"freightTemplateNo\":\"" + tpl + "\"");
        assertThat(detailTemplate(biz, goodsNo)).isEqualTo(tpl);

        // 空串 = 清掉、跟随门店。updateById 跳过 null —— 这一条守的就是「清空那句 set 真的生成了」
        save(biz, goodsNo, "\"freightTemplateNo\":\"\"");
        assertThat(detailTemplate(biz, goodsNo)).isNull();

        int code = json.readTree(saveRaw(biz, goodsNo, "\"freightTemplateNo\":\"FT-NOPE\"")).get("code").asInt();
        assertThat(code).as("不存在的模板号不能存进去").isNotZero();
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private String template(String no, long firstFee) {
        jdbc.update("insert into ful_freight_template(template_no,name,first_weight_gram,first_fee,add_weight_gram,"
                        + "add_fee,free_threshold,is_default,out_of_range,tenant_no,created_at,updated_at)"
                        + " values(?,?,1000,?,500,100,0,0,'[]','MAIN',now(),now())",
                no, no, firstFee);
        return no;
    }

    private JsonNode data(String body) {
        return json.readTree(body).get("data");
    }

    private String detailTemplate(String biz, String goodsNo) throws Exception {
        JsonNode v = data(mvc().perform(get("/biz/goods/" + goodsNo).header("Authorization", "Bearer " + biz))
                .andReturn().getResponse().getContentAsString()).get("freightTemplateNo");
        return v == null || v.isNull() ? null : v.asString();
    }

    /** 在售商品编辑：别的字段原样，只带运费模板 —— 它不进草稿，保存即落主行 */
    private void save(String biz, String goodsNo, String extra) throws Exception {
        assertThat(json.readTree(saveRaw(biz, goodsNo, extra)).get("code").asInt()).isZero();
    }

    private String saveRaw(String biz, String goodsNo, String extra) throws Exception {
        return mvc().perform(post("/biz/goods/save").header("Authorization", "Bearer " + biz)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"goodsNo\":\"" + goodsNo + "\",\"title\":\"面粉\",\"type\":\"NORMAL\","
                                + "\"categoryNo\":\"CAT210\",\"skus\":[{\"spec\":\"默认\",\"price\":500,\"stock\":99}],"
                                + extra + "}"))
                .andReturn().getResponse().getContentAsString();
    }

    /** 快递预览的运费（分） */
    private long previewFreight(String phone, String goodsNo) throws Exception {
        String token = TestLogin.consumer(mvc(), json, otpStore, phone);
        String skuNo = data(mvc().perform(get("/mp/goods/" + goodsNo)).andReturn().getResponse()
                .getContentAsString()).get("skus").get(0).get("skuNo").asString();
        mvc().perform(post("/mp/cart/add").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"goodsNo\":\"" + goodsNo + "\",\"skuNo\":\"" + skuNo + "\",\"qty\":1}"));
        String addressId = data(mvc().perform(post("/mp/user/address")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"买家\",\"phone\":\"13600180013\",\"province\":\"浙江省\","
                                + "\"city\":\"某市\",\"district\":\"某区\",\"detail\":\"某路 1 号\","
                                + "\"isDefault\":true,\"tag\":\"家\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString()).get(0).get("addressId").asString();
        JsonNode p = json.readTree(mvc().perform(post("/mp/order/preview").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fulfillment\":\"EXPRESS\",\"addressId\":\"" + addressId + "\"}"))
                .andReturn().getResponse().getContentAsString());
        assertThat(p.get("code").asInt()).as("预览失败：%s", p).isZero();
        return p.get("data").get("amount").get("freightMinor").asLong();
    }

    private String onSaleGoods(String merchantToken, String title) throws Exception {
        TestStoreCategory.open(mvc(), json, merchantToken, "CAT210");
        String body = mvc().perform(post("/biz/goods/save")
                        .header("Authorization", "Bearer " + merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\",\"type\":\"NORMAL\",\"categoryNo\":\"CAT210\","
                                + "\"skus\":[{\"spec\":\"默认\",\"price\":500,\"stock\":99}]}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        String goodsNo = data(body).get("goodsNo").asString();
        mvc().perform(post("/ops/goods/" + goodsNo + "/audit")
                .header("Authorization", "Bearer " + TestLogin.admin(mvc(), json))
                .contentType(MediaType.APPLICATION_JSON).content("{\"approved\":true}"));
        mvc().perform(post("/biz/goods/" + goodsNo + "/toggle")
                        .header("Authorization", "Bearer " + merchantToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"onSale\":true}"))
                .andExpect(jsonPath("$.code").value(0));
        return goodsNo;
    }

    private String merchant(String phone, String name) throws Exception {
        String user = TestLogin.consumer(mvc(), json, otpStore, phone);
        String body = mvc().perform(post("/mp/merchant/apply").header("Authorization", "Bearer " + user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"subject\":\"INDIVIDUAL_BIZ\","
                                + "\"contactName\":\"张三\",\"contactPhone\":\"13900000000\","
                                + "\"category\":\"食品\",\"serviceScope\":\"COMMUNITY\","
                                + "\"communityNos\":[\"CM001\"]}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        String applyNo = data(body).get("applyNo").asString();
        mvc().perform(post("/ops/merchant/apply/" + applyNo + "/audit")
                .header("Authorization", "Bearer " + TestLogin.admin(mvc(), json))
                .contentType(MediaType.APPLICATION_JSON).content("{\"approved\":true}"));
        return TestLogin.merchantOwner(mvc(), json, otpStore, phone);
    }
}
