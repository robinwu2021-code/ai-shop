package ai.neargo.shop.scenario;

import ai.neargo.shop.common.Fulfillments;
import ai.neargo.shop.merchant.service.MerchantStoreService;
import ai.neargo.shop.merchant.service.StoreFulfillmentService;
import ai.neargo.shop.merchant.service.StoreFulfillmentService.ChannelCmd;
import ai.neargo.shop.support.TestLogin;
import ai.neargo.shop.support.TestPlan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * 可见性按门店算：**从买家那一侧验**。
 *
 * <p>与 {@code StoreFulfillmentFlowTest} 里那几条的分工：那些断的是
 * {@code reachableCommunities} 这个**端口**给出什么，这里断的是
 * <b>买家在 C 端到底搜不搜得到</b> —— 中间还隔着社区池、上架总闸、审核状态。
 *
 * <p>为什么必须分开验：端口对了而池没跟着重建，症状是「商家侧显示在售、
 * 买家哪儿都搜不到」，两边都不报错。这个仓库 2026-08-25 一天之内踩过两次
 * （补证照通过、改经营范围），两次都是端口对、池不对。
 */
@SpringBootTest
@ActiveProfiles("test")
class StoreScopedVisibilityFlowTest {

    @Autowired
    private ai.neargo.shop.common.OtpStore otpStore;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private StoreFulfillmentService fulfillmentService;

    @Autowired
    private MerchantStoreService storeService;

    @Autowired
    private ai.neargo.shop.merchant.mapper.MerchantMappers.ServiceAreaMapper serviceAreaMapper;

    @Autowired
    private ai.neargo.shop.merchant.mapper.MerchantMappers.EntityPlanMapper planMapper;

    @Autowired
    private ai.neargo.shop.spi.user.MerchantQueryPort merchantQuery;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
                        .springSecurity())
                .build();
    }

    @Test
    @DisplayName("★★★ 买家侧：A 店的货不出现在只有 B 店服务的社区里")
    void buyerInCommunityBOnlySeesGoodsFromStoreB() throws Exception {
        String biz = merchant("12600180001", "两片区的店");
        String merchantNo = merchantNoOf(biz);
        TestPlan.grantQuota(planMapper, merchantNo, 3);

        String storeA = defaultStoreNo(biz);
        String storeB = createStore(biz, "B 片区店");

        // 主体足迹两块都要 —— 门店子集是从主体足迹里挑的
        storeService.save(merchantNo, new MerchantStoreService.SaveCommand(
                null, null, null, null, null, null, null, null, null, null, List.of(
                        new MerchantStoreService.AreaCommand("COMMUNITY", "CM001"),
                        new MerchantStoreService.AreaCommand("COMMUNITY", "CM002")), null, null));

        // A 店只送 CM001，B 店只送 CM002
        fulfillmentService.save(merchantNo, storeA, List.of(new ChannelCmd(
                Fulfillments.MERCHANT_DELIVERY, true, null, null, "SUBSET", List.of(areaNoOf(merchantNo, "CM001")))));
        fulfillmentService.save(merchantNo, storeB, List.of(new ChannelCmd(
                Fulfillments.MERCHANT_DELIVERY, true, null, null, "SUBSET", List.of(areaNoOf(merchantNo, "CM002")))));

        /*
         * 这件货**只摆在 A 店的货架上**（门店选品三态：一旦有了任意一条店级行，
         * 该商品转为店级管理，没有行的店视为未上架）。
         */
        String goodsNo = onSaleGoodsAt(biz, storeA, "只在 A 店卖的抽纸");

        /*
         * ★ 本类的核心断言。改造之前可见性取主体并集，这件货会同时进 CM001 与 CM002 的池 ——
         * CM002 的买家搜到它、下了单，而 A 店根本不送 CM002、B 店也没有这件货。
         */
        assertThat(buyerSees("CM001", goodsNo)).as("A 店服务的社区里当然要看得到").isTrue();
        assertThat(buyerSees("CM002", goodsNo))
                .as("只有 B 店服务的社区里不该出现 A 店的货 —— 送不到，也没有货")
                .isFalse();
    }

    @Test
    @DisplayName("★★ 运营端一次性重建：把池删空之后，跑一次就该全回来")
    void opsResyncRebuildsEverything() throws Exception {
        String biz = merchant("12600180002", "要重建池的店");
        String merchantNo = merchantNoOf(biz);
        String goodsNo = onSaleGoods(biz, "重建前就在卖的抽纸");
        assertThat(buyerSees("CM001", goodsNo)).isTrue();

        /*
         * 模拟「派生索引与事实脱节」：直接把池清掉。
         * 这正是那两次回归的形状 —— 事实（商品在架、门店可达）没变，而索引没了。
         */
        ai.neargo.common.data.scope.DataScopeContext.executeWithoutScope(() ->
                poolMapper.delete(com.baomidou.mybatisplus.core.toolkit.Wrappers
                        .<ai.neargo.shop.product.entity.PrdCommunityPool>lambdaQuery()
                        .eq(ai.neargo.shop.product.entity.PrdCommunityPool::getEntityNo, merchantNo)));
        assertThat(buyerSees("CM001", goodsNo)).as("先确认真的搜不到了").isFalse();

        mvc().perform(post("/ops/community-pool/resync")
                        .header("Authorization", "Bearer " + opsLogin("goods", "goods123"))
                        .param("entityNo", merchantNo))
                .andExpect(jsonPath("$.code").value(0));

        assertThat(buyerSees("CM001", goodsNo))
                .as("跑完重建就该全回来 —— 这是运维手上唯一的兜底")
                .isTrue();
    }

    @Autowired
    private ai.neargo.shop.product.mapper.ProductMappers.CommunityPoolMapper poolMapper;

    // ------------------------------------------------------------ 脚手架

    private boolean buyerSees(String communityNo, String goodsNo) throws Exception {
        String body = mvc().perform(get("/mp/goods")
                        .param("communityNo", communityNo).param("size", "50"))
                .andReturn().getResponse().getContentAsString();
        return body.contains(goodsNo);
    }

    private String areaNoOf(String merchantNo, String communityNo) {
        return serviceAreaMapper.selectList(com.baomidou.mybatisplus.core.toolkit.Wrappers
                        .<ai.neargo.shop.merchant.entity.MchServiceArea>lambdaQuery()
                        .eq(ai.neargo.shop.merchant.entity.MchServiceArea::getEntityNo, merchantNo)
                        .eq(ai.neargo.shop.merchant.entity.MchServiceArea::getRefCode, communityNo))
                .get(0).getAreaNo();
    }

    /** 建一件货、过审、在**指定门店**上架 */
    private String onSaleGoodsAt(String token, String storeNo, String title) throws Exception {
        String goodsNo = saveGoods(token, title);
        approveGoods(goodsNo);
        mvc().perform(post("/biz/goods/" + goodsNo + "/toggle")
                        .header("Authorization", "Bearer " + token)
                        .header("X-Store-No", storeNo)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"onSale\":true}"))
                .andExpect(jsonPath("$.code").value(0));
        return goodsNo;
    }

    private String onSaleGoods(String token, String title) throws Exception {
        String goodsNo = saveGoods(token, title);
        approveGoods(goodsNo);
        mvc().perform(post("/biz/goods/" + goodsNo + "/toggle")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"onSale\":true}"))
                .andExpect(jsonPath("$.code").value(0));
        return goodsNo;
    }

    private String saveGoods(String token, String title) throws Exception {
        return json.readTree(mvc().perform(post("/biz/goods/save")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryNo\":\"CAT210\",\"title\":\"" + title + "\","
                                + "\"subtitle\":\"\",\"cover\":\"🧻\",\"images\":[],"
                                + "\"specGroups\":[],\"skus\":[{\"optionValues\":[],\"price\":500,\"stock\":9}]}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString())
                .get("data").get("goodsNo").asString();
    }

    private void approveGoods(String goodsNo) throws Exception {
        mvc().perform(post("/ops/goods/" + goodsNo + "/audit")
                        .header("Authorization", "Bearer " + opsLogin("goods", "goods123"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"approved\":true}"))
                .andExpect(jsonPath("$.code").value(0));
    }

    private String createStore(String token, String name) throws Exception {
        return json.readTree(mvc().perform(post("/biz/store/create")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString())
                .get("data").get("storeNo").asString();
    }

    private String defaultStoreNo(String token) throws Exception {
        return json.readTree(mvc().perform(get("/biz/context").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString())
                .get("data").get("currentStoreNo").asString();
    }

    private String merchantNoOf(String token) throws Exception {
        return json.readTree(mvc().perform(get("/biz/merchant/profile")
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString())
                .get("data").get("merchantNo").asString();
    }

    private String merchant(String phone, String name) throws Exception {
        String user = login(phone);
        String applyNo = json.readTree(mvc().perform(post("/mp/merchant/apply")
                        .header("Authorization", "Bearer " + user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"subject\":\"INDIVIDUAL_BIZ\","
                                + "\"contactName\":\"张三\",\"contactPhone\":\"13900000000\","
                                + "\"category\":\"食品\",\"serviceScope\":\"COMMUNITY\","
                                + "\"communityNos\":[\"CM001\"]}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString())
                .get("data").get("applyNo").asString();
        mvc().perform(post("/ops/merchant/apply/" + applyNo + "/audit")
                        .header("Authorization", "Bearer " + opsLogin("bd", "bd123"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"approved\":true}"))
                .andExpect(jsonPath("$.code").value(0));
        return login(phone);
    }

    private String login(String phone) throws Exception {
        return TestLogin.consumer(mvc(), json, otpStore, phone);
    }

    private String opsLogin(String user, String pwd) throws Exception {
        return json.readTree(mvc().perform(post("/ops/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + user + "\",\"password\":\"" + pwd + "\"}"))
                .andReturn().getResponse().getContentAsString())
                .get("data").get("token").asString();
    }
}
