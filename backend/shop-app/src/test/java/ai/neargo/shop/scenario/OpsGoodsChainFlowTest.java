package ai.neargo.shop.scenario;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.product.entity.PrdGoods;
import ai.neargo.shop.product.entity.PrdSku;
import ai.neargo.shop.product.mapper.ProductMappers.GoodsMapper;
import ai.neargo.shop.product.mapper.ProductMappers.SkuMapper;
import ai.neargo.shop.support.TestLogin;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 单商品全链路状态（M5）。
 *
 * <p>两条断言各守一件事：
 *
 * <ol>
 *   <li><b>「建了一半的账」必须看得出来。</b>{@code bookedSkus < skuCount}
 *       在商家端的表现是「有些规格盘得着、有些盘不着」，极难自查 ——
 *       而它在这一页上要是被算成「建好了」，运营也查不出来。</li>
 *   <li><b>查不到就是 404，不是一个全零的壳。</b>字段全零与「这件货卡在第一步」
 *       在界面上长得一模一样。</li>
 * </ol>
 *
 * <p>另外：这一页<b>只读</b>。它不能顺着 SKU 去调 {@code itemIdOf} —— 那个方法
 * 查不到会创建，于是「运营点开一个商品」就往进销存库里凭空写入一批空物料。
 */
@SpringBootTest
@ActiveProfiles("test")
class OpsGoodsChainFlowTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private GoodsMapper goodsMapper;

    @Autowired
    private SkuMapper skuMapper;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
                        .springSecurity())
                .build();
    }

    @Test
    @DisplayName("★★★ 建了一半的账要看得出来 —— 商家端的表现是「有些规格盘不着」，极难自查")
    void partiallyBookedIsNotBooked() throws Exception {
        String goodsNo = "GCHAIN1-" + System.nanoTime();
        Long gid = seedGoods(goodsNo, "APPROVED", true);
        Long s1 = seedSku(goodsNo, "SKU1-" + System.nanoTime());
        Long s2 = seedSku(goodsNo, "SKU2-" + System.nanoTime());
        try {
            String body = mvc().perform(get("/ops/product/" + goodsNo + "/chain")
                            .header("Authorization", "Bearer " + TestLogin.admin(mvc(), json)))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            JsonNode d = json.readTree(body).get("data");

            assertThat(d.get("skuCount").asInt()).isEqualTo(2);
            /*
             * 这两个 SKU 一个都没搬进进销存（探针商家没有 inv_owner），
             * 所以 bookedSkus = 0 < 2 —— 卡点必须落在 NO_ACCOUNT。
             * 判成「通了」的话，运营在这一页上永远看不见投影没搬全。
             */
            assertThat(d.get("bookedSkus").asInt()).isLessThan(d.get("skuCount").asInt());
            assertThat(d.get("stuckAt").asString())
                    .as("两个规格一个都没建账，却没说卡在建账这一层")
                    .isEqualTo("NO_ACCOUNT");
            assertThat(d.get("onHand").asInt()).isZero();
        } finally {
            DataScopeContext.executeWithoutScope(() -> {
                skuMapper.deleteById(s1);
                skuMapper.deleteById(s2);
                return goodsMapper.deleteById(gid);
            });
        }
    }

    @Test
    @DisplayName("★★ 查不到是 404，不是一个全零的壳 —— 全零与「卡在第一步」长得一样")
    void missingGoodsIsNotAnEmptyShell() throws Exception {
        mvc().perform(get("/ops/product/GNOSUCH-0000/chain")
                        .header("Authorization", "Bearer " + TestLogin.admin(mvc(), json)))
                .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.not(0)));
    }

    private Long seedGoods(String goodsNo, String auditStatus, boolean onSale) {
        PrdGoods g = new PrdGoods();
        g.setGoodsNo(goodsNo);
        g.setEntityNo("E-GCHAIN-PROBE");
        g.setTitle("全链路探针");
        g.setType("GOODS");
        g.setAuditStatus(auditStatus);
        g.setOnSale(onSale);
        g.setDeleted(0);
        DataScopeContext.executeWithoutScope(() -> goodsMapper.insert(g));
        return g.getId();
    }

    private Long seedSku(String goodsNo, String skuNo) {
        PrdSku s = new PrdSku();
        s.setSkuNo(skuNo);
        s.setGoodsNo(goodsNo);
        s.setEntityNo("E-GCHAIN-PROBE");
        s.setMarket("CN");
        s.setPrice(100L);
        s.setStock(0);
        s.setDeleted(0);
        DataScopeContext.executeWithoutScope(() -> skuMapper.insert(s));
        return s.getId();
    }
}
