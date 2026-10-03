package ai.neargo.shop.scenario;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.product.entity.PrdSku;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 商品域平台统计（M4）。
 *
 * <p>覆盖率这类指标最容易坏在<b>「没填」的定义</b>上：表单交空值进来的是空串，
 * 只判 NULL 的话覆盖率虚高，而这个数正是扫码功能的天花板 ——
 * 报出「条码覆盖 40%」而实际只有 0.5%，会让人以为扫码入库可以推了。
 *
 * <p>断言全部写成差值，不是绝对值：测试库里本来就有 SKU，
 * 而绝对值会随别的用例种下的数据漂。
 */
@SpringBootTest
@ActiveProfiles("test")
class OpsProductStatsFlowTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private SkuMapper skuMapper;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
                        .springSecurity())
                .build();
    }

    private JsonNode stats(String token) throws Exception {
        String body = mvc().perform(get("/ops/product/stats?days=7")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data");
    }

    @Test
    @DisplayName("★★★ 空串不算「填了条码」—— 只判 NULL 的话覆盖率虚高，而它是扫码功能的天花板")
    void emptyStringIsNotAFilledBarcode() throws Exception {
        String token = TestLogin.admin(mvc(), json);
        JsonNode before = stats(token);

        Long empty = seedSku("", "");
        Long real = seedSku("6901234567892", "LZ-001");
        try {
            JsonNode after = stats(token);
            assertThat(after.get("skus").asLong())
                    .as("两条 SKU 都该进分母")
                    .isEqualTo(before.get("skus").asLong() + 2);
            assertThat(after.get("skusWithBarcode").asLong())
                    .as("空串被当成填了条码 —— 覆盖率会虚高，而这个数决定要不要推扫码入库")
                    .isEqualTo(before.get("skusWithBarcode").asLong() + 1);
            assertThat(after.get("skusWithCode").asLong())
                    .isEqualTo(before.get("skusWithCode").asLong() + 1);
        } finally {
            DataScopeContext.executeWithoutScope(() -> {
                skuMapper.deleteById(empty);
                return skuMapper.deleteById(real);
            });
        }
    }

    @Test
    @DisplayName("★★ 分子不超过分母 —— 「用过的类目比类目还多」是这类页面最典型的坏法")
    void numeratorsNeverExceedDenominators() throws Exception {
        JsonNode s = stats(TestLogin.admin(mvc(), json));
        assertThat(s.get("categoriesUsed").asLong())
                .as("用过的类目比类目总数还多 —— 两个数不是从同一批行里数出来的")
                .isLessThanOrEqualTo(s.get("categories").asLong());
        assertThat(s.get("skusWithBarcode").asLong()).isLessThanOrEqualTo(s.get("skus").asLong());
        assertThat(s.get("skusWithCode").asLong()).isLessThanOrEqualTo(s.get("skus").asLong());
        assertThat(s.get("specDimsBound").asLong())
                .as("挂上类目的维度比维度总数还多 —— prd_category_spec 里有指向已删维度的行？")
                .isLessThanOrEqualTo(s.get("specDims").asLong());
        assertThat(s.get("auditDays").asInt()).isEqualTo(7);
    }

    private Long seedSku(String barcode, String code) {
        PrdSku sku = new PrdSku();
        sku.setSkuNo("SKUSTAT-" + System.nanoTime());
        sku.setGoodsNo("GSTAT-PROBE");
        sku.setEntityNo("E-STAT-PROBE");
        sku.setMarket("CN");
        sku.setBarcode(barcode);
        sku.setMerchantSkuCode(code);
        sku.setPrice(100L);
        sku.setStock(0);
        sku.setDeleted(0);
        DataScopeContext.executeWithoutScope(() -> skuMapper.insert(sku));
        return sku.getId();
    }
}
