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
         * ★ **必须显式把 B 店关掉**，不能指望「过审后默认不在售」。
         *
         * 2026-08-25 的 d614cb30「过审即在售」改了这个前提：过审那一刻实体级 on_sale
         * 就是 true 了，而 setStoreOnSale 第一次转店级管理时会**把其他门店按当时的
         * 实体级状态固化下来**（它注释里讲的那个坑：不固化的话在 A 店点下架会把
         * B 店的货一起弄没）。于是 toggle A 店之后，B 店被固化成「在卖」。
         *
         * 那个行为是对的 —— 过审后两家店都在卖，符合「保持现状」。
         * 错的是这条用例原来的写法：它靠「过审后不在售」这个副作用来表达
         * 「这货只在 A 店」，而那从来不是它该依赖的东西。
         */
        offShelfAt(biz, storeB, goodsNo);

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
    @DisplayName("★★★ 挑门店是兜底不是择优：默认店服务得了就不动它")
    void defaultStoreKeepsTheOrderWhenItServes() throws Exception {
        /*
         * 用一个**真的存在于 cmt_community 的社区**，而不是 CM001 ——
         * CM001 只是申请单里的一个字符串，库里没有那一行，也就没有坐标可设。
         */
        String cm = openCommunityWithCoords("SVC-NEAR", 30_000_000, 120_000_000);
        String biz = merchant("12600180003", "两家店都送同一片区", cm);
        String merchantNo = merchantNoOf(biz);
        TestPlan.grantQuota(planMapper, merchantNo, 3);
        String defaultStore = defaultStoreNo(biz);
        String other = createStore(biz, "另一家也送这儿");

        /*
         * 两家店都是 ALL 范围（默认），也就是**两家都服务 CM001** ——
         * 这正是线上那个多门店主体今天的样子（三家店全 ALL）。
         *
         * ★ 一开始我写的是「取最近的那家」，那样这一单会从默认店挪到另一家，
         * 而订单的 store_no 决定结算归属、门店级活动匹配、跨店报表。
         * 线上那三家里两家坐标相同、一家没坐标，最后是靠 storeNo 字符串排序
         * 才碰巧仍然选中默认店 —— 这条用例把「不许靠巧合」钉住。
         */
        // 两家店都开商家自送、都是 ALL 范围 —— 也就是两家都服务 CM001
        for (String st : List.of(defaultStore, other)) {
            fulfillmentService.save(merchantNo, st, List.of(
                    new ChannelCmd(Fulfillments.MERCHANT_DELIVERY, true, null, null, "ALL", null)));
        }

        /*
         * ★ **坐标要摆成「最近的不是默认店」**，否则这条用例分辨不出两种实现。
         *
         * 第一版我没设坐标，结果两家店都算不出距离、回落到 storeNo 排序，
         * 而默认店恰好排在前面 —— 用例绿着，但把「一律取最近」改回去它也绿。
         * 那正是这条用例要消除的那个巧合，反倒让它通过了。
         *
         * 现在：另一家店与社区**同一个点**（距离 0），默认店在 ~110 公里外。
         * 「取最近」会选另一家，只有「默认店优先」才会选默认店。
         */
        setStoreCoords(defaultStore, 31_000_000, 120_000_000);   // ~110 公里外
        setStoreCoords(other, 30_000_000, 120_000_000);          // 与社区同一个点
        assertThat(merchantQuery.reachableCommunities(merchantNo, defaultStore)).contains(cm);
        assertThat(merchantQuery.reachableCommunities(merchantNo, other)).contains(cm);

        String storeNo = orderedStoreNo(biz, "12600180013", cm);
        assertThat(storeNo)
                .as("默认店服务得了这个社区，单就该还落在它身上 —— 与改造前逐字相同")
                .isEqualTo(defaultStore);
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

    @Autowired
    private ai.neargo.shop.trade.mapper.TradeMappers.SubOrderMapper subOrderMapper;

    @Autowired
    private ai.neargo.shop.community.mapper.CommunityMappers.CommunityMapper communityMapper;

    @Autowired
    private ai.neargo.shop.merchant.mapper.MerchantMappers.MchStoreMapper storeMapper;

    @Autowired
    private ai.neargo.shop.user.mapper.UserMappers.UserMapper userMapper;

    // ------------------------------------------------------------ 脚手架

    /**
     * 让一个买家下一单，返回子单落在哪家门店。
     *
     * <p>走真实链路（建货 → 上架 → 买家设社区 → 下单），因为「单落到哪家店」
     * 是 {@code storesOfEntities} 在下单那一刻算的 —— 直接调服务测不到它。
     * 买家的社区必须先设：挑店那一步正是按它找「谁服务这儿」。
     */
    private String orderedStoreNo(String bizToken, String buyerPhone, String communityNo) throws Exception {
        String goodsNo = onSaleGoods(bizToken, "定门店用的抽纸 " + buyerPhone);
        String buyer = login(buyerPhone);
        /*
         * 直接写买家的社区，**不走 /mp/user/community** —— 那个接口要求自提点属于该社区
         * （防「按社区取货、按自提点履约」的错配），而这条用例既不用自提点也不测绑定接口。
         *
         * <p>这一步必须真的生效：买家社区为空的话，挑门店那一段整个走兜底，
         * 用例绿着却什么都没验到 —— 第一版就是这样，把实现改回「一律取最近」它照样绿。
         * 所以下面回读一次确认。
         */
        setBuyerCommunity(buyer, communityNo);
        /*
         * 自送要有收货地址（70014）。
         *
         * **收货人电话不用 buyerPhone**：测试的登录号一律走 `126` 前缀（约定俗成的
         * 「一眼假」号段，保证不会撞上真号），而 `126` 不是大陆手机号段 ——
         * `SaveAddressReq` 现在按 `Phones.CN_MOBILE` 判格式，会拒。
         * 收货人电话本来就与账号手机号是两个字段（家里的座机、代收人的号都可能填在这），
         * 所以这里填一个格式合法的号，`126` 那条约定原样留着。
         */
        String addressId = json.readTree(mvc().perform(post("/mp/user/address")
                        .header("Authorization", "Bearer " + buyer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"买家\",\"phone\":\"13600180013\",\"province\":\"浙江省\","
                                + "\"city\":\"杭州市\",\"district\":\"西湖区\",\"detail\":\"文三路 1 号\","
                                + "\"isDefault\":true,\"tag\":\"家\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString())
                // /mp/user/address 返回的是**整份地址列表**，不是刚存的那一条。
                // 这个买家只有这一条，取第一条即可
                .get("data").get(0).get("addressId").asString();
        String skuNo = json.readTree(mvc().perform(get("/mp/goods/" + goodsNo))
                        .andReturn().getResponse().getContentAsString())
                .get("data").get("skus").get(0).get("skuNo").asString();
        String body = mvc().perform(post("/mp/order")
                        .header("Authorization", "Bearer " + buyer)
                        .contentType(MediaType.APPLICATION_JSON)
                        /*
                         * **必须用商家自送，不能用门店自取**：自提点所属门店在
                         * storesOfEntities 里是**第一分支**，会先于「默认店服务得了吗」定下门店 ——
                         * 用自提就把这条用例要验的那一段整个跳过了。
                         * 自送没有落点约束，门店由买家社区决定，正是要测的那条路。
                         */
                        .content("{\"fulfillment\":\"MERCHANT_DELIVERY\",\"addressId\":\"" + addressId + "\","
                                + "\"items\":[{\"goodsNo\":\""
                                + goodsNo + "\",\"skuNo\":\"" + skuNo + "\",\"qty\":1}]}"))
                .andReturn().getResponse().getContentAsString();
        var data = json.readTree(body).get("data");
        assertThat(data).as("下单没成功：%s", body).isNotNull();
        assertThat(data.get("orderNo")).as("下单响应里没有 orderNo：%s", body).isNotNull();
        String orderNo = data.get("orderNo").asString();
        /*
         * **直接查子单表**：OrderVO 不含 storeNo（C 端本来就不该看到从哪家店发货），
         * 而「单落在哪家店」正是这条用例要断的事实。
         */
        var subs = ai.neargo.common.data.scope.DataScopeContext.executeWithoutScope(() ->
                subOrderMapper.selectList(com.baomidou.mybatisplus.core.toolkit.Wrappers
                        .<ai.neargo.shop.trade.entity.OrdSubOrder>lambdaQuery()
                        .eq(ai.neargo.shop.trade.entity.OrdSubOrder::getOrderNo, orderNo)));
        assertThat(subs).as("下单了却没有子单？orderNo=%s", orderNo).isNotEmpty();
        return subs.get(0).getStoreNo();
    }

    /** 建一个开放中的社区并给上坐标 —— 距离要算得出来，社区这一端也得有点 */
    private String openCommunityWithCoords(String communityNo, int latE6, int lngE6) {
        ai.neargo.common.data.scope.DataScopeContext.executeWithoutScope(() -> {
            var c = new ai.neargo.shop.community.entity.CmtCommunity();
            c.setCommunityNo(communityNo);
            c.setName("按门店算可见性测试小区");
            c.setStatus("OPEN");
            c.setFenceRadius(1000);
            c.setLatE6(latE6);
            c.setLngE6(lngE6);
            return communityMapper.insert(c);
        });
        return communityNo;
    }

    /** 直接写买家的默认社区，并回读确认 —— 这一步悄悄失败会让整条用例失去意义 */
    private void setBuyerCommunity(String buyerToken, String communityNo) throws Exception {
        String userNo = json.readTree(mvc().perform(get("/mp/user/profile")
                        .header("Authorization", "Bearer " + buyerToken))
                .andReturn().getResponse().getContentAsString())
                .get("data").get("userNo").asString();
        ai.neargo.common.data.scope.DataScopeContext.executeWithoutScope(() -> {
            var u = userMapper.selectOne(com.baomidou.mybatisplus.core.toolkit.Wrappers
                    .<ai.neargo.shop.user.entity.UsrAccount>lambdaQuery()
                    .eq(ai.neargo.shop.user.entity.UsrAccount::getUserNo, userNo).last("limit 1"));
            assertThat(u).as("买家 %s 不存在", userNo).isNotNull();
            u.setCommunityNo(communityNo);
            return userMapper.updateById(u);
        });
    }

    private void setStoreCoords(String storeNo, int latE6, int lngE6) {
        ai.neargo.common.data.scope.DataScopeContext.executeWithoutScope(() -> {
            var st = storeMapper.selectOne(com.baomidou.mybatisplus.core.toolkit.Wrappers
                    .<ai.neargo.shop.merchant.entity.MchStore>lambdaQuery()
                    .eq(ai.neargo.shop.merchant.entity.MchStore::getStoreNo, storeNo)
                    .last("limit 1"));
            assertThat(st).as("门店 %s 不存在", storeNo).isNotNull();
            st.setLatE6(latE6);
            st.setLngE6(lngE6);
            return storeMapper.updateById(st);
        });
    }

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

    /** 在指定门店下架一件货 —— 用来表达「这家店不卖它」 */
    private void offShelfAt(String token, String storeNo, String goodsNo) throws Exception {
        mvc().perform(post("/biz/goods/" + goodsNo + "/toggle")
                        .header("Authorization", "Bearer " + token)
                        .header("X-Store-No", storeNo)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"onSale\":false}"))
                .andExpect(jsonPath("$.code").value(0));
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
        return merchant(phone, name, "CM001");
    }

    private String merchant(String phone, String name, String communityNo) throws Exception {
        String user = login(phone);
        String applyNo = json.readTree(mvc().perform(post("/mp/merchant/apply")
                        .header("Authorization", "Bearer " + user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"subject\":\"INDIVIDUAL_BIZ\","
                                + "\"contactName\":\"张三\",\"contactPhone\":\"13900000000\","
                                + "\"category\":\"食品\",\"serviceScope\":\"COMMUNITY\","
                                + "\"communityNos\":[\"" + communityNo + "\"]}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString())
                .get("data").get("applyNo").asString();
        mvc().perform(post("/ops/merchant/apply/" + applyNo + "/audit")
                        .header("Authorization", "Bearer " + opsLogin("bd", "bd123"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"approved\":true}"))
                .andExpect(jsonPath("$.code").value(0));
        // A7：/biz/** 只认 btk_，这里必须换 B 端令牌
        return TestLogin.merchantOwner(mvc(), json, otpStore, phone);
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
