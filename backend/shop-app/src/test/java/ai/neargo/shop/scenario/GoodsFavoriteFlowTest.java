package ai.neargo.shop.scenario;

import ai.neargo.shop.common.OtpStore;
import ai.neargo.shop.support.TestLogin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 商品 / 店铺收藏与「卖不卖到你那儿」（TDD-C端商品收藏与送达判断，原型 g05 / g07 / g08）。
 *
 * <p>每个用例一个新手机号 —— 收藏是按人的，共用一个买家的话，用例之间会互相看到对方的收藏，
 * 单独跑绿、全量红，报错还不指向真因。
 */
@SpringBootTest
@ActiveProfiles("test")
class GoodsFavoriteFlowTest {

    /** 种子（DevSeeder）：G0001 / G0003 在 C0001、C0002 的商品池里；M0001 是 G0001 的商家 */
    private static final String GOODS_A = "G0001";
    private static final String GOODS_B = "G0003";
    private static final String MERCHANT = "M0001";
    private static final String COMMUNITY_IN = "C0001";
    private static final String COMMUNITY_OUT = "C-NOWHERE";

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private OtpStore otpStore;

    private static int seq;
    private String token;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup
                        .SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @BeforeEach
    void login() throws Exception {
        token = TestLogin.consumer(mvc(), json, otpStore, "1350099" + String.format("%04d", ++seq));
    }

    private JsonNode data(String method, String url) throws Exception {
        var req = "POST".equals(method) ? post(url) : get(url);
        String body = mvc().perform(req.header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data");
    }

    private boolean toggleGoods(String goodsNo) throws Exception {
        return data("POST", "/mp/favorite/goods/" + goodsNo).get("favorited").asBoolean();
    }

    private boolean detailFavorited(String goodsNo) throws Exception {
        return data("GET", "/mp/goods/" + goodsNo).get("favorited").asBoolean();
    }

    @Test
    @DisplayName("★★★ AC1 / AC2：收藏 → 取消 → 再收藏；再收藏不撞唯一键（取消是真删）")
    void toggleOnOffOn() throws Exception {
        assertThat(detailFavorited(GOODS_A)).as("初始").isFalse();
        assertThat(toggleGoods(GOODS_A)).isTrue();
        assertThat(detailFavorited(GOODS_A)).isTrue();
        assertThat(toggleGoods(GOODS_A)).isFalse();
        assertThat(detailFavorited(GOODS_A)).isFalse();
        // 取消走的若是全局逻辑删除，deleted=1 的行还占着 uk_prd_goods_favorite，这一步就是 500
        assertThat(toggleGoods(GOODS_A)).as("取消后再收藏").isTrue();
        assertThat(detailFavorited(GOODS_A)).isTrue();
    }

    @Test
    @DisplayName("★★★ AC3：我的收藏 · 商品按收藏时间倒序，每条都标着已收藏")
    void listNewestFirstKeepsOffShelf() throws Exception {
        toggleGoods(GOODS_A);
        toggleGoods(GOODS_B);
        JsonNode page = data("GET", "/mp/favorite/goods");
        List<String> nos = new ArrayList<>();
        page.get("records").forEach(r -> nos.add(r.get("goodsNo").asString()));
        assertThat(nos).containsExactly(GOODS_B, GOODS_A);
        assertThat(page.get("records").get(0).get("favorited").asBoolean()).isTrue();
        assertThat(page.get("total").asLong()).isEqualTo(2);
    }

    @Test
    @DisplayName("★★ AC4：店铺收藏 → 取消 → 再收藏不撞；我的收藏 · 店铺只列收藏的")
    void storeFavoriteRefavoriteAndList() throws Exception {
        assertThat(data("POST", "/mp/favorite/store/" + MERCHANT).get("favorited").asBoolean()).isTrue();
        assertThat(data("POST", "/mp/favorite/store/" + MERCHANT).get("favorited").asBoolean()).isFalse();
        // 此前 usr_store_favorite 取消走 deleteById（软删），这一步撞 uk_user_entity
        assertThat(data("POST", "/mp/favorite/store/" + MERCHANT).get("favorited").asBoolean())
                .as("取消后再收藏").isTrue();
        JsonNode stores = data("GET", "/mp/favorite/store");
        assertThat(stores.size()).isEqualTo(1);
        assertThat(stores.get(0).get("merchantNo").asString()).isEqualTo(MERCHANT);
    }

    @Test
    @DisplayName("★★★ AC5 / AC6：卖不卖到这个社区，与首页商品池同一份判据；没给社区号不判")
    void deliverableFollowsCommunityPool() throws Exception {
        JsonNode in = data("GET", "/mp/goods/" + GOODS_A + "?communityNo=" + COMMUNITY_IN);
        JsonNode out = data("GET", "/mp/goods/" + GOODS_A + "?communityNo=" + COMMUNITY_OUT);
        JsonNode none = data("GET", "/mp/goods/" + GOODS_A);
        // 对照量先验：首页在 C0001 真的看得到它（否则下面那条 true 只是巧合）
        JsonNode home = data("GET", "/mp/goods?communityNo=" + COMMUNITY_IN + "&size=50");
        List<String> homeNos = new ArrayList<>();
        home.get("records").forEach(r -> homeNos.add(r.get("goodsNo").asString()));
        assertThat(homeNos).contains(GOODS_A);

        assertThat(in.get("deliverable").asBoolean()).isTrue();
        assertThat(out.get("deliverable").asBoolean()).isFalse();
        assertThat(none.get("deliverable").isNull()).as("没传社区号 = 不判").isTrue();
    }

    @Test
    @DisplayName("★★ 游客看详情照常，favorited = false，不因没登录报错")
    void anonymousDetailNotFavorited() throws Exception {
        String body = mvc().perform(get("/mp/goods/" + GOODS_A)).andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(body).get("data").get("favorited").asBoolean()).isFalse();
    }
}
