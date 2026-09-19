package ai.neargo.shop.scenario;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.AfterEach;
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

/**
 * 商品接口要把「平台自营」带给买家端。
 *
 * <p><b>电商法 §37</b>：平台自营业务必须以显著方式与第三方区分标记。
 * 库里一直有 {@code mch_entity.self_operated}，端上的商家条也早有自营标 ——
 * 但 {@code MerchantQueryPort.MerchantBrief} 里没有这个字段，商品接口一路都不带，
 * 于是商品页、首页、团购卡上的自营标**一次都没显示过**，而两侧都不报错。
 *
 * <p>成对测：自营为 true、第三方为 false。只测一边的话，
 * 「恒为 true」与「恒为 false」总有一种会漏过去。
 */
@SpringBootTest
@ActiveProfiles("test")
class MerchantSelfOperatedFlowTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private ai.neargo.shop.spi.user.MerchantQueryPort merchantQuery;

    @Autowired
    private ai.neargo.shop.merchant.mapper.MerchantMappers.MchEntityMapper merchantMapper;

    @Autowired
    private ai.neargo.shop.product.mapper.ProductMappers.GoodsMapper goodsMapper;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
                        .springSecurity())
                .build();
    }

    private String merchant(boolean selfOperated) {
        var m = new ai.neargo.shop.merchant.entity.MchEntity();
        m.setEntityNo(ai.neargo.shop.common.BizKey.next(ai.neargo.shop.common.BizKey.MERCHANT));
        m.setName("自营标测试-" + (selfOperated ? "自营" : "第三方"));
        m.setStatus("ACTIVE");
        m.setSelfOperated(selfOperated ? 1 : 0);
        merchantMapper.insert(m);
        return m.getEntityNo();
    }

    private String goods(String entityNo) {
        var g = new ai.neargo.shop.product.entity.PrdGoods();
        g.setGoodsNo(ai.neargo.shop.common.BizKey.next(ai.neargo.shop.common.BizKey.GOODS));
        g.setEntityNo(entityNo);
        g.setTitle("自营标测试商品");
        g.setType("STANDARD");
        g.setOnSale(true);
        goodsMapper.insert(g);
        return g.getGoodsNo();
    }

    private JsonNode detailMerchant(String goodsNo) throws Exception {
        return json.readTree(mvc().perform(get("/mp/goods/" + goodsNo))
                .andReturn().getResponse().getContentAsString()).get("data").get("merchant");
    }

    @Test
    @DisplayName("★★★ 自营商家的商品：merchant.selfOperated = true —— 端上据此在店名前标「自营」")
    void selfOperatedMerchantIsMarked() throws Exception {
        JsonNode m = detailMerchant(goods(merchant(true)));
        assertThat(m.has("selfOperated")).as("商品接口的商家简介里要有这个字段").isTrue();
        assertThat(m.get("selfOperated").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("★★★ 第三方商家的商品：merchant.selfOperated = false —— 标错了就是对买家的虚假告知")
    void thirdPartyMerchantIsNotMarked() throws Exception {
        assertThat(detailMerchant(goods(merchant(false))).get("selfOperated").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("★★ Port 两条路（单查 / 批量）口径一致")
    void portFindAndFindAllAgree() {
        String self = merchant(true);
        String other = merchant(false);
        assertThat(merchantQuery.find(self).orElseThrow().selfOperated()).isTrue();
        assertThat(merchantQuery.find(other).orElseThrow().selfOperated()).isFalse();
        var all = merchantQuery.findAll(java.util.List.of(self, other));
        assertThat(all.get(self).selfOperated()).isTrue();
        assertThat(all.get(other).selfOperated()).isFalse();
    }

    @Test
    @DisplayName("★★★ 「我买过的」那份 VO 也带自营 —— 店铺页第一档此前把虹选鲜果显示成一个「虹」字（真机发现）")
    void visitedMerchantCarriesSelfOperated() {
        var self = merchantMapper.selectOne(Wrappers.<ai.neargo.shop.merchant.entity.MchEntity>lambdaQuery()
                .eq(ai.neargo.shop.merchant.entity.MchEntity::getEntityNo, merchant(true)));
        var other = merchantMapper.selectOne(Wrappers.<ai.neargo.shop.merchant.entity.MchEntity>lambdaQuery()
                .eq(ai.neargo.shop.merchant.entity.MchEntity::getEntityNo, merchant(false)));
        assertThat(ai.neargo.shop.merchant.dto.VisitedMerchantVO.of(self, 2, 0L).selfOperated()).isTrue();
        assertThat(ai.neargo.shop.merchant.dto.VisitedMerchantVO.of(other, 2, 0L).selfOperated()).isFalse();
    }

    @AfterEach
    void cleanup() {
        var mine = merchantMapper.selectList(Wrappers.<ai.neargo.shop.merchant.entity.MchEntity>lambdaQuery()
                .likeRight(ai.neargo.shop.merchant.entity.MchEntity::getName, "自营标测试-"));
        for (var m : mine) {
            goodsMapper.delete(Wrappers.<ai.neargo.shop.product.entity.PrdGoods>lambdaQuery()
                    .eq(ai.neargo.shop.product.entity.PrdGoods::getEntityNo, m.getEntityNo()));
            merchantMapper.deleteById(m.getId());
        }
    }
}
