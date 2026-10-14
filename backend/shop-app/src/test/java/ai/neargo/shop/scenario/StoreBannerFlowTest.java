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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * 门店背景图（TDD-门店背景图）：店主在 B 端设，C 端门户顶部读 ——
 * <b>设了是照片，没设是主色浅底</b>（2026-09-29 用户定）。
 *
 * <p>每条用例用快速开店建一家<b>全新</b>的店：改共享种子 M0001 的门面会让别的用例单独绿、全量红。
 */
@SpringBootTest
@ActiveProfiles("test")
class StoreBannerFlowTest {

    private static final String BANNER = "https://cdn.example.com/store/banner-1.jpg";

    @Autowired
    private ai.neargo.shop.common.OtpStore otpStore;
    @Autowired
    private WebApplicationContext context;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private ai.neargo.shop.merchant.mapper.MerchantMappers.MchStoreMapper storeMapper;
    @Autowired
    private ai.neargo.shop.merchant.service.MerchantStoreService storeService;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
                        .springSecurity())
                .build();
    }

    @Test
    @DisplayName("★★★ AC1/AC2 店主设了背景图：B 端回读是它，C 端门户 store.bannerUrl 也是它")
    void bannerSetShowsOnPortal() throws Exception {
        String token = TestLogin.merchantOwner(mvc(), json, otpStore, "12600168001");
        String merchantNo = quickStart(token, "设背景图的店");

        JsonNode profile = saveStore(token, "{\"bannerUrl\":\"" + BANNER + "\"}");
        assertThat(profile.get("bannerUrl").asString()).isEqualTo(BANNER);

        assertThat(portalFront(merchantNo).get("bannerUrl").asString())
                .as("买家那边要读到同一张 —— 只写不读的话，店主设了、门户照旧是浅底")
                .isEqualTo(BANNER);
    }

    @Test
    @DisplayName("★★★ AC3 清掉（空串）= 没设；不传 = 不改（老版本 B 端保存别的资料不能把图冲掉）")
    void clearAndKeep() throws Exception {
        String token = TestLogin.merchantOwner(mvc(), json, otpStore, "12600168002");
        String merchantNo = quickStart(token, "清背景图的店");
        saveStore(token, "{\"bannerUrl\":\"" + BANNER + "\"}");

        // 老版本 B 端不认识这个字段：保存营业时间时不带它
        saveStore(token, "{\"openHours\":\"08:00-20:00\"}");
        assertThat(portalFront(merchantNo).get("bannerUrl").asString())
                .as("不传 = 不改").isEqualTo(BANNER);

        saveStore(token, "{\"bannerUrl\":\"\"}");
        assertThat(portalFront(merchantNo).get("bannerUrl").asString())
                .as("空串 = 清掉。写成 null 的话 updateById 会跳过它，等于没清").isEmpty();
    }

    /**
     * 按<b>门店号</b>进门户（分享 / 列表进店）走的是另一个端口实现（StoreDirectoryPortImpl.front）。
     * 只测主体号那条的话，这条漏了字段也是绿的 —— 第一版测试就是这么漏的（消融时没变红）。
     * 快速开店的店还没营业、按门店号进不去，所以这里给 M0001 临时加一家营业的店，用完删掉。
     */
    @Test
    @DisplayName("★★★ AC2 按门店号进门户也读得到背景图（另一个端口实现）")
    void bannerOnStoreNoPath() throws Exception {
        ai.neargo.shop.merchant.entity.MchStore st = new ai.neargo.shop.merchant.entity.MchStore();
        st.setEntityNo("M0001");
        st.setStoreNo("STBNR" + System.nanoTime());
        st.setName("背景图测试分店");
        st.setIsDefault(false);
        st.setStatus("ACTIVE");
        st.setOpenHours("08:00-20:00");
        st.setFeatured("[]");
        st.setTenantNo("MAIN");
        ai.neargo.common.data.scope.DataScopeContext.executeWithoutScope(() -> storeMapper.insert(st));
        try {
            storeService.saveBanner("M0001", st.getStoreNo(), BANNER);
            assertThat(home(st.getStoreNo()).get("store").get("bannerUrl").asString()).isEqualTo(BANNER);
        } finally {
            ai.neargo.common.data.scope.DataScopeContext.executeWithoutScope(() -> storeMapper.deleteById(st.getId()));
        }
    }

    @Test
    @DisplayName("★★ AC4 不是 http(s) 的值拒收 —— 本地临时路径写进去，买家那边是一张裂图")
    void rejectsNonHttp() throws Exception {
        String token = TestLogin.merchantOwner(mvc(), json, otpStore, "12600168003");
        String merchantNo = quickStart(token, "乱传背景图的店");

        mvc().perform(post("/biz/store").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bannerUrl\":\"wxfile://tmp_abc.jpg\"}"))
                .andExpect(jsonPath("$.code").value(10400));
        assertThat(portalFront(merchantNo).get("bannerUrl").asString()).isEmpty();
    }

    @Test
    @DisplayName("★★ 从没设过的店：C 端读到空串，不是 null（端上把 null 当值会画出一张裂图）")
    void neverSetIsEmptyString() throws Exception {
        String token = TestLogin.merchantOwner(mvc(), json, otpStore, "12600168004");
        String merchantNo = quickStart(token, "没设背景图的店");
        JsonNode front = portalFront(merchantNo);
        assertThat(front.has("bannerUrl")).isTrue();
        assertThat(front.get("bannerUrl").isNull()).isFalse();
        assertThat(front.get("bannerUrl").asString()).isEmpty();
    }

    private JsonNode saveStore(String token, String body) throws Exception {
        String res = mvc().perform(post("/biz/store").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(res).get("data");
    }

    /** 按主体号进门户（老链接 / 快速开店的店）读到的 store 段 —— 走 MerchantPortImpl.storeFront */
    private JsonNode portalFront(String merchantNo) throws Exception {
        return home(merchantNo).get("store");
    }

    private JsonNode home(String no) throws Exception {
        String res = mvc().perform(get("/mp/store/" + no))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(res).get("data");
    }

    private String quickStart(String token, String name) throws Exception {
        String body = mvc().perform(post("/biz/merchant/quick-start")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"storeName\":\"" + name + "\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("merchantNo").asString();
    }
}
