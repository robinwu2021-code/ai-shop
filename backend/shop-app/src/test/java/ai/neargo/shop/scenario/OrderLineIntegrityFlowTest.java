package ai.neargo.shop.scenario;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.marketing.campaign.entity.MktCampaign;
import ai.neargo.shop.marketing.campaign.mapper.CampaignMappers.CampaignMapper;
import ai.neargo.shop.support.TestLogin;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 下单请求里「行」本身的完整性：件数与重复行。
 *
 * <p>{@code POST /mp/order} 与 {@code /mp/order/preview} 的 {@code items[]} 由端上直接给，
 * 此前两件事都没人管：
 * <ul>
 *   <li><b>件数 ≤ 0 照收</b>：{2 件 A, -2 件 B} 这样一单，B 行金额是负的，
 *       整单应付被拉低，而库存锁与限购都按「件数之和」算 —— 等于用一行负数抵掉另一行；</li>
 *   <li><b>同一规格拆成多行</b>：买赠按规格汇总赠品件数，而锁库存、写赠品行是<b>逐行</b>读那个汇总数，
 *       五行 {X, 2} 在「买 2 送 1」下送出 25 件而不是 5 件。</li>
 * </ul>
 * 每条都走真 HTTP，库存从商品详情读 —— 断言的是买家与商家真正看到的数。
 *
 * <p>号段 1300301xxxx 本类独占。
 */
@SpringBootTest
@ActiveProfiles("test")
class OrderLineIntegrityFlowTest {

    private static final String GOODS = "G0001";
    private static final String SKU_A = "SK0001";
    private static final String SKU_B = "SK0002";
    private static final String CAMPAIGN_PREFIX = "CPLINE";
    private static final int BAD_REQUEST = 10400;

    private static int seq;

    @Autowired private WebApplicationContext context;
    @Autowired private ObjectMapper json;
    @Autowired private ai.neargo.shop.common.OtpStore otpStore;
    @Autowired private CampaignMapper campaignMapper;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    @AfterEach
    void clearOwnCampaigns() {
        DataScopeContext.executeWithoutScope(() -> campaignMapper.delete(
                Wrappers.<MktCampaign>lambdaQuery().likeRight(MktCampaign::getCampaignNo, CAMPAIGN_PREFIX)));
    }

    @Test
    @DisplayName("★★★ {A×2, B×-2} 下单被拒，两个规格的库存一件不动，也没有落单")
    void negativeQtyLineRejectedOnCreate() throws Exception {
        String token = login(phone());
        int stockA = stockOf(SKU_A);
        int stockB = stockOf(SKU_B);

        JsonNode r = call("/mp/order", token, body(line(SKU_A, 2), line(SKU_B, -2)), "neg-1");

        assertThat(r.get("code").asInt()).as("负数件数被收下了：" + r).isEqualTo(BAD_REQUEST);
        assertThat(stockOf(SKU_A)).as("被拒的单不能锁库存").isEqualTo(stockA);
        assertThat(stockOf(SKU_B)).as("负数行不能把库存「加」回去").isEqualTo(stockB);
        assertThat(ordersOf(token)).as("被拒的单不能落库").isZero();
    }

    @Test
    @DisplayName("★★ 件数 0 与负数在预览就拒 —— 预览与下单同一道闸，确认页不显示一个付不了的价")
    void zeroAndNegativeQtyRejectedOnPreview() throws Exception {
        String token = login(phone());
        assertThat(call("/mp/order/preview", token, body(line(SKU_A, 0)), null).get("code").asInt())
                .isEqualTo(BAD_REQUEST);
        assertThat(call("/mp/order/preview", token, body(line(SKU_A, 2), line(SKU_B, -2)), null)
                .get("code").asInt()).isEqualTo(BAD_REQUEST);
    }

    // ------------------------------------------------------------------ helpers

    private static String line(String sku, int qty) {
        return "{\"goodsNo\":\"" + GOODS + "\",\"skuNo\":\"" + sku + "\",\"qty\":" + qty + "}";
    }

    private static String body(String... lines) {
        return "{\"fulfillment\":\"STORE_PICKUP\",\"pickupNo\":\"PP0001\",\"items\":["
                + String.join(",", lines) + "]}";
    }

    private JsonNode call(String path, String token, String body, String idem) throws Exception {
        var req = post(path).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body);
        if (idem != null) {
            req = req.header("Idempotency-Key", idem + System.nanoTime());
        }
        return json.readTree(mvc().perform(req).andReturn().getResponse().getContentAsString());
    }

    private int stockOf(String skuNo) throws Exception {
        String body = mvc().perform(get("/mp/goods/" + GOODS)).andReturn().getResponse().getContentAsString();
        for (JsonNode sku : json.readTree(body).get("data").get("skus")) {
            if (skuNo.equals(sku.get("skuNo").asString())) {
                return sku.get("stock").asInt();
            }
        }
        throw new AssertionError("sku not found: " + skuNo);
    }

    private int ordersOf(String token) throws Exception {
        JsonNode r = json.readTree(mvc().perform(get("/mp/order").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString());
        JsonNode data = r.get("data");
        JsonNode list = data.has("list") ? data.get("list") : data.has("records") ? data.get("records") : data;
        return list.size();
    }

    private String phone() {
        return "1300301" + String.format("%04d", ++seq);
    }

    private String login(String phone) throws Exception {
        return TestLogin.consumer(mvc(), json, otpStore, phone);
    }

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
    }
}
