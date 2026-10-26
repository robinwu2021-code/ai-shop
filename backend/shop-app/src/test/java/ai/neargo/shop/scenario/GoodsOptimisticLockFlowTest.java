package ai.neargo.shop.scenario;

import ai.neargo.shop.support.TestLogin;
import ai.neargo.shop.support.TestStoreCategory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 商品行的乐观锁（{@code @Version}）不能静默 0 行。
 *
 * <p>MyBatis-Plus 的 {@code updateById} 在版本号对不上时<b>不抛异常，只返回 0</b>。
 * 不看返回值的话：读到商品 → 别人先改了一笔 → 我的 UPDATE 0 行 → 接口照样回 200，
 * 而我以为写进去的那些（上架、改标题）一个字节都没落库。
 *
 * <p>怎么造出「中途被人改了」：在服务读完商品、还没写回之间，借一个必经的端口回调
 * （上架时的准入判定 / 保存时的门店经营类目）从<b>另一个线程、另一条连接</b>把版本号推进一格 ——
 * 与真实的并发写同形。单独成类，是因为替身会让 Spring 起一个独立的上下文。
 */
@SpringBootTest
@ActiveProfiles("test")
class GoodsOptimisticLockFlowTest {

    @Autowired
    private ai.neargo.shop.common.OtpStore otpStore;
    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired
    private WebApplicationContext context;
    @Autowired
    private ObjectMapper json;

    @MockitoSpyBean
    private ai.neargo.shop.spi.user.AdmissionPort admissionPort;
    @MockitoSpyBean
    private ai.neargo.shop.spi.user.StoreCategoryPort storeCategoryPort;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
                        .springSecurity())
                .build();
    }

    @AfterEach
    void reset() {
        Mockito.reset(admissionPort, storeCategoryPort);
    }

    /** 另起一条连接把这件商品的版本号推进一格 —— 「别人先改了一笔」 */
    private void bumpConcurrently(String goodsNo) {
        CompletableFuture.runAsync(() ->
                jdbc.update("update prd_goods set version = version + 1 where goods_no=?", goodsNo)).join();
    }

    @Test
    @DisplayName("★★★ 上架时商品行被并发改过 → CONFLICT 且整体回滚，不是 200 + 没上架")
    void toggleOnStaleVersionConflicts() throws Exception {
        String biz = merchant("13700777801", "乐观锁·上架店");
        String goodsNo = createGoods(biz, "乐观锁上架品");
        approve(goodsNo);
        Mockito.doAnswer(inv -> {
            bumpConcurrently(goodsNo);
            return inv.callRealMethod();
        }).when(admissionPort).requireListingAllowed(Mockito.anyString(), Mockito.any(), Mockito.anyBoolean());

        JsonNode r = postJson(biz, "/biz/goods/" + goodsNo + "/toggle", "{\"onSale\":true}");
        assertThat(r.get("code").asInt()).as("0 行更新被当成成功了")
                .isEqualTo(ai.neargo.shop.common.ErrorCode.CONFLICT.code());
        assertThat(jdbc.queryForObject("select on_sale from prd_goods where goods_no=?", Boolean.class, goodsNo))
                .isFalse();
    }

    @Test
    @DisplayName("★★★ 保存时商品行被并发改过 → CONFLICT，标题与 SKU 都不落库")
    void saveOnStaleVersionConflicts() throws Exception {
        String biz = merchant("13700777802", "乐观锁·保存店");
        String goodsNo = createGoods(biz, "乐观锁保存品");
        Mockito.doAnswer(inv -> {
            bumpConcurrently(goodsNo);
            return inv.callRealMethod();
        }).when(storeCategoryPort).categoryNosOf(Mockito.anyString());

        JsonNode r = postJson(biz, "/biz/goods/save", "{\"goodsNo\":\"" + goodsNo + "\",\"categoryNo\":\"CAT210\","
                + "\"title\":\"乐观锁保存品·改\",\"subtitle\":\"t\",\"cover\":\"c\",\"images\":[],\"specGroups\":[],"
                + "\"skus\":[{\"optionValues\":[],\"price\":1500,\"stock\":10}]}");
        Mockito.reset(storeCategoryPort);
        assertThat(r.get("code").asInt()).isEqualTo(ai.neargo.shop.common.ErrorCode.CONFLICT.code());
        assertThat(jdbc.queryForObject("select title from prd_goods where goods_no=?", String.class, goodsNo))
                .isEqualTo("乐观锁保存品");
    }

    private String createGoods(String token, String title) throws Exception {
        JsonNode r = postJson(token, "/biz/goods/save", "{\"categoryNo\":\"CAT210\",\"title\":\"" + title
                + "\",\"subtitle\":\"t\",\"cover\":\"c\",\"images\":[],\"specGroups\":[],"
                + "\"skus\":[{\"optionValues\":[],\"price\":1000,\"stock\":10}]}");
        assertThat(r.get("code").asInt()).as(r.toString()).isZero();
        return r.get("data").get("goodsNo").asString();
    }

    private void approve(String goodsNo) throws Exception {
        String ops = TestLogin.operator(mvc(), json, "goods", "goods123");
        assertThat(postJson(ops, "/ops/goods/" + goodsNo + "/audit", "{\"approved\":true}")
                .get("code").asInt()).isZero();
    }

    private JsonNode postJson(String token, String path, String body) throws Exception {
        return json.readTree(mvc().perform(post(path).header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getContentAsString());
    }

    private String merchant(String phone, String name) throws Exception {
        String user = TestLogin.consumer(mvc(), json, otpStore, phone);
        String body = mvc().perform(post("/mp/merchant/apply").header("Authorization", "Bearer " + user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"subject\":\"INDIVIDUAL_BIZ\","
                                + "\"contactName\":\"张三\",\"contactPhone\":\"13900000000\","
                                + "\"category\":\"食品\",\"serviceScope\":\"COMMUNITY\","
                                + "\"communityNos\":[\"CM001\"]}"))
                .andReturn().getResponse().getContentAsString();
        String applyNo = json.readTree(body).get("data").get("applyNo").asString();
        String bd = TestLogin.operator(mvc(), json, "bd", "bd123");
        mvc().perform(post("/ops/merchant/apply/" + applyNo + "/audit")
                .header("Authorization", "Bearer " + bd)
                .contentType(MediaType.APPLICATION_JSON).content("{\"approved\":true}"));
        String token = TestLogin.merchantOwner(mvc(), json, otpStore, phone);
        TestStoreCategory.open(mvc(), json, token, "CAT210");
        return token;
    }
}
