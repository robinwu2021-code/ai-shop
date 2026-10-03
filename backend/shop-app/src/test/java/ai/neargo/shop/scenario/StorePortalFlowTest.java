package ai.neargo.shop.scenario;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.merchant.entity.MchStore;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchStoreMapper;
import ai.neargo.shop.product.entity.PrdStoreGoods;
import ai.neargo.shop.product.mapper.ProductMappers.StoreGoodsMapper;
import ai.neargo.shop.product.review.entity.RvwReview;
import ai.neargo.shop.product.review.mapper.ReviewMappers.ReviewMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 门店门户（TDD-C端门店化与门店门户 AC1、AC7、AC9、AC10、AC12、§2.7 在售口径）。
 *
 * <p>用种子主体 M0001（默认店 ST-M0001，在售 G0001 / G0002）再挂一家分店。
 * <b>只加行不改行</b>，加的行在 {@link #cleanup} 里删掉 —— 种子是全量共用的，
 * 改了不还原的症状是「单独跑绿、全量红」，报错永远不指向这里。
 */
@SpringBootTest
@ActiveProfiles("test")
class StorePortalFlowTest {

    private static final String ENTITY = "M0001";
    private static final String DEFAULT_STORE = "ST-M0001";

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private MchStoreMapper storeMapper;
    @Autowired
    private StoreGoodsMapper storeGoodsMapper;
    @Autowired
    private ReviewMapper reviewMapper;

    private final List<Runnable> undo = new ArrayList<>();

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @AfterEach
    void cleanup() {
        for (int i = undo.size() - 1; i >= 0; i--) {
            undo.get(i).run();
        }
        undo.clear();
    }

    @Test
    @DisplayName("★ AC1/AC9 门户以门店为根：门头是分店名，主体信息仍在 merchant 里")
    void portalIsStoreRooted() throws Exception {
        MchStore b = addStore("分店甲 · 门户测试", "ACTIVE", "09:00-18:00");

        JsonNode home = data(get("/mp/store/" + b.getStoreNo()));
        assertThat(home.get("portal").get("storeNo").asString()).isEqualTo(b.getStoreNo());
        assertThat(home.get("portal").get("storeName").asString()).isEqualTo("分店甲 · 门户测试");
        assertThat(home.get("portal").get("status").asString()).isEqualTo("ACTIVE");
        assertThat(home.get("merchant").get("merchantNo").asString()).isEqualTo(ENTITY);
        // 门面文案取这家店自己的，不是默认店的
        assertThat(home.get("store").get("openHours").asString()).isEqualTo("09:00-18:00");
        assertThat(home.get("closed").asBoolean()).isFalse();
        assertThat(home.get("sibling").isNull()).isTrue();
    }

    @Test
    @DisplayName("★ AC10 主体号落到默认门店；未知门店号 404")
    void entityNoLandsOnDefaultStore() throws Exception {
        JsonNode home = data(get("/mp/store/" + ENTITY));
        assertThat(home.get("portal").get("storeNo").asString()).isEqualTo(DEFAULT_STORE);

        JsonNode r = json.readTree(mvc().perform(get("/mp/store/ST-NOPE-404"))
                .andReturn().getResponse().getContentAsString());
        assertThat(r.get("code").asInt()).isEqualTo(10404);
    }

    @Test
    @DisplayName("★ §2.7 门户只列本店在售：有了店级行的商品只在那家店出现；没有店级行的照旧处处可见")
    void goodsFollowStoreLevelOnSale() throws Exception {
        MchStore b = addStore("分店乙 · 门户测试", "ACTIVE", "");
        // G0001 转为店级管理，且只在默认店上架
        storeGoods(DEFAULT_STORE, "G0001", true);

        List<String> atB = goodsNos(data(get("/mp/store/" + b.getStoreNo() + "/goods")).get("records"));
        List<String> atA = goodsNos(data(get("/mp/store/" + DEFAULT_STORE + "/goods")).get("records"));
        assertThat(atB).doesNotContain("G0001").contains("G0002");
        assertThat(atA).contains("G0001", "G0002");

        // 门户首屏的 goods 与分页接口同一口径
        List<String> homeB = goodsNos(data(get("/mp/store/" + b.getStoreNo())).get("goods"));
        assertThat(homeB).doesNotContain("G0001").contains("G0002");
    }

    @Test
    @DisplayName("★ AC7 暂停营业的店：门户照样打开、closed=true，并给同主体的营业店")
    void pausedStoreShowsSibling() throws Exception {
        MchStore paused = addStore("分店丙 · 暂停", "READONLY", "");

        JsonNode home = data(get("/mp/store/" + paused.getStoreNo()));
        assertThat(home.get("closed").asBoolean()).isTrue();
        assertThat(home.get("portal").get("status").asString()).isEqualTo("READONLY");
        assertThat(home.get("sibling").get("storeNo").asString()).isEqualTo(DEFAULT_STORE);
    }

    @Test
    @DisplayName("AC12 评价按门店：只回这家店的；老评价没有门店号，不混进来")
    void reviewsByStore() throws Exception {
        MchStore b = addStore("分店丁 · 评价", "ACTIVE", "");
        review(b.getStoreNo(), "分店丁的评价");
        review(DEFAULT_STORE, "默认店的评价");
        review(null, "没有门店号的老评价");

        JsonNode list = data(get("/mp/review").param("storeNo", b.getStoreNo()));
        List<String> contents = new ArrayList<>();
        list.forEach(r -> contents.add(r.get("content").asString()));
        assertThat(contents).containsExactly("分店丁的评价");
    }

    @Test
    @DisplayName("店码按门店：回的是这家店的号与名（码图在测试里通道未开，为 null）")
    void acodeIsPerStore() throws Exception {
        MchStore b = addStore("分店戊 · 码", "ACTIVE", "");
        JsonNode a = data(get("/mp/store/" + b.getStoreNo() + "/acode"));
        assertThat(a.get("storeNo").asString()).isEqualTo(b.getStoreNo());
        assertThat(a.get("storeName").asString()).isEqualTo("分店戊 · 码");
    }

    // ---------------------------------------------------------------- helpers

    private MchStore addStore(String name, String status, String openHours) {
        MchStore s = new MchStore();
        s.setEntityNo(ENTITY);
        s.setStoreNo("STPTL" + System.nanoTime());
        s.setName(name);
        s.setIsDefault(false);
        s.setStatus(status);
        s.setOpenHours(openHours);
        s.setFeatured("[]");
        s.setTenantNo("MAIN");
        DataScopeContext.executeWithoutScope(() -> storeMapper.insert(s));
        undo.add(() -> DataScopeContext.executeWithoutScope(() -> storeMapper.deleteById(s.getId())));
        return s;
    }

    private void storeGoods(String storeNo, String goodsNo, boolean onSale) {
        PrdStoreGoods g = new PrdStoreGoods();
        g.setStoreNo(storeNo);
        g.setGoodsNo(goodsNo);
        g.setEntityNo(ENTITY);
        g.setOnSale(onSale);
        g.setPlatformSuspended(false);
        g.setTenantNo("MAIN");
        DataScopeContext.executeWithoutScope(() -> storeGoodsMapper.insert(g));
        undo.add(() -> DataScopeContext.executeWithoutScope(() -> storeGoodsMapper.deleteById(g.getId())));
    }

    private void review(String storeNo, String content) {
        RvwReview r = new RvwReview();
        String no = "RV-PTL-" + System.nanoTime();
        r.setReviewNo(no);
        r.setSubOrderNo("S" + no);
        r.setOrderNo("O" + no);
        r.setGoodsNo("G0001");
        r.setEntityNo(ENTITY);
        r.setStoreNo(storeNo);
        r.setUserNo("U-PTL");
        r.setRating(5);
        r.setContent(content);
        r.setStatus("PASSED");
        r.setTenantNo("MAIN");
        DataScopeContext.executeWithoutScope(() -> reviewMapper.insert(r));
        undo.add(() -> DataScopeContext.executeWithoutScope(() -> reviewMapper.deleteById(r.getId())));
    }

    private static List<String> goodsNos(JsonNode arr) {
        List<String> out = new ArrayList<>();
        arr.forEach(n -> out.add(n.get("goodsNo").asString()));
        return out;
    }

    private JsonNode data(MockHttpServletRequestBuilder b) throws Exception {
        String body = mvc().perform(b).andReturn().getResponse().getContentAsString();
        JsonNode root = json.readTree(body);
        assertThat(root.get("code").asInt()).as(body).isZero();
        return root.get("data");
    }
}
