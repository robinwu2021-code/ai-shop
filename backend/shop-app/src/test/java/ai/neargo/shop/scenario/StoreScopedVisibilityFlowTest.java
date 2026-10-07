package ai.neargo.shop.scenario;

import ai.neargo.shop.support.TestStoreCategory;
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
    @DisplayName("★★★ 送货方式原样再存一次不能 500 —— 范围子集是物理删后重插，逻辑删的墓碑会撞唯一键")
    void savingSameSubsetTwiceDoesNotCollide() throws Exception {
        String biz = merchant("12600180009", "连存两次的店");
        String merchantNo = merchantNoOf(biz);
        String store = defaultStoreNo(biz);
        storeService.save(merchantNo, new MerchantStoreService.SaveCommand(
                null, null, null, null, null, null, null, null, null, null, List.of(
                        new MerchantStoreService.AreaCommand("COMMUNITY", "CM001"),
                        new MerchantStoreService.AreaCommand("COMMUNITY", "CM002")), null, null));
        String a1 = areaNoOf(merchantNo, "CM001");
        String a2 = areaNoOf(merchantNo, "CM002");

        /*
         * 2026-09-28 生产：商家在 App 里只把「快递」打开、自送的范围原样带回去，保存即 500
         * （Duplicate entry … for key uk_channel_area）。delete(wrapper) 被全局逻辑删改写成 UPDATE deleted=1，
         * 而 uk_channel_area 不含 deleted —— 第二次插同一个 area_no 必撞。
         */
        for (List<String> areas : List.of(List.of(a1), List.of(a1), List.of(a2), List.of(a1, a2), List.of(a1))) {
            fulfillmentService.save(merchantNo, store, List.of(new ChannelCmd(
                    Fulfillments.MERCHANT_DELIVERY, true, null, null, "SUBSET", areas)));
        }
        assertThat(merchantQuery.reachableCommunities(merchantNo, store))
                .as("最后一次存的是 CM001，就只送 CM001")
                .containsExactly("CM001");
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

    @Test
    @DisplayName("★★★ 池 = 可达集合，一个社区一行 —— 楼栋与排除落地之后仍然对得上")
    void poolMatchesReachableExactly() throws Exception {
        /*
         * **B 端算出来的和 C 端看到的必须是同一件事。**
         *
         * 池是派生索引，reachableCommunities 是事实。楼栋展开进来之后，
         * 「框了小区，又单独框了里面那栋楼」会让同一个聚落从两条路各进来一次 ——
         * 去重由展开那一步负责（判据在 ServiceAreaExcludeFlowTest —— 池写入
         * 自己会吞掉重复，放在这儿断言是断不出来的，消融过）；
         * 而 EXCLUDE 那一条如果只减了聚落没减它的楼，B 端显示「已排除」、
         * C 端站在楼下照样看得到，说的和做的对不上，且不报任何错。
         */
        String estate = openCommunityWithCoords("SVC-EST", 30_010_000, 120_010_000);
        String keep = openBuildingUnder("SVC-BLD-KEEP", estate);
        String drop = openBuildingUnder("SVC-BLD-DROP", estate);

        String biz = merchant("12600180014", "框了小区又单独框了楼", estate);
        String merchantNo = merchantNoOf(biz);
        TestPlan.grantQuota(planMapper, merchantNo, 3);
        storeService.save(merchantNo, new MerchantStoreService.SaveCommand(
                null, null, null, null, null, null, null, null, null, null,
                List.of(new MerchantStoreService.AreaCommand("COMMUNITY", estate, "INCLUDE"),
                        // 同一个聚落从两条路进来：小区展开一次，自己又被框了一次
                        new MerchantStoreService.AreaCommand("COMMUNITY", keep, "INCLUDE"),
                        new MerchantStoreService.AreaCommand("COMMUNITY", drop, "EXCLUDE")),
                null, null));

        String goodsNo = onSaleGoods(biz, "既在小区也在楼里卖的抽纸");

        var reach = merchantQuery.reachableCommunities(merchantNo);
        assertThat(reach).contains(estate, keep).doesNotContain(drop);

        var rows = poolRows(merchantNo, goodsNo);
        assertThat(rows)
                .as("池里的社区集合与可达集合对不上 = 两条路各算各的，而买家只看得见池")
                .containsExactlyInAnyOrderElementsOf(reach);
        assertThat(buyerSees(drop, goodsNo)).as("排除掉的楼里不该看得到").isFalse();
        assertThat(buyerSees(keep, goodsNo)).as("没排除的楼里要看得到").isTrue();
    }

    @Test
    @DisplayName("★★★ 重建幂等：连跑两次，池不多不少")
    void resyncIsIdempotent() throws Exception {
        /*
         * 重建是上线后要手工跑一遍的动作（V321 的说明），运维多点一次是常态。
         * 不幂等的症状不是报错，是池行翻倍 —— 而翻倍之后首页重复、
         * 「这个社区有几家在卖」那类计数也跟着错，没人会想到是点了两次重建。
         */
        String biz = merchant("12600180015", "要连跑两次重建的店");
        String merchantNo = merchantNoOf(biz);
        String goodsNo = onSaleGoods(biz, "重建两次的抽纸");
        String ops = "Bearer " + opsLogin("goods", "goods123");

        mvc().perform(post("/ops/community-pool/resync").header("Authorization", ops)
                .param("entityNo", merchantNo)).andExpect(jsonPath("$.code").value(0));
        var once = poolRows(merchantNo, goodsNo);

        mvc().perform(post("/ops/community-pool/resync").header("Authorization", ops)
                .param("entityNo", merchantNo)).andExpect(jsonPath("$.code").value(0));

        assertThat(poolRows(merchantNo, goodsNo))
                .as("第二次重建改变了池 = 不幂等，运维多点一次就把数据点坏了")
                .containsExactlyInAnyOrderElementsOf(once);
        assertThat(once).as("对照量本身要非零，否则这条用例在比两个空集").isNotEmpty();
    }

    /** 池里这件货落在哪些社区。**带出重复行** —— 去重与否正是被测的东西 */
    private List<String> poolRows(String merchantNo, String goodsNo) {
        return ai.neargo.common.data.scope.DataScopeContext.executeWithoutScope(() ->
                poolMapper.selectList(com.baomidou.mybatisplus.core.toolkit.Wrappers
                                .<ai.neargo.shop.product.entity.PrdCommunityPool>lambdaQuery()
                                .eq(ai.neargo.shop.product.entity.PrdCommunityPool::getEntityNo, merchantNo)
                                .eq(ai.neargo.shop.product.entity.PrdCommunityPool::getGoodsNo, goodsNo))
                        .stream()
                        .map(ai.neargo.shop.product.entity.PrdCommunityPool::getCommunityNo)
                        .toList());
    }

    /** 楼栋：与小区同点，只是多了一个 parentNo —— 归属是声明的，不靠围栏几何 */
    private String openBuildingUnder(String communityNo, String parentNo) {
        ai.neargo.common.data.scope.DataScopeContext.executeWithoutScope(() -> {
            var c = new ai.neargo.shop.community.entity.CmtCommunity();
            c.setCommunityNo(communityNo);
            c.setName("按门店算可见性测试楼栋");
            c.setStatus("OPEN");
            c.setKind(ai.neargo.shop.community.entity.CmtCommunity.KIND_BUILDING);
            c.setParentNo(parentNo);
            c.setFenceRadius(150);
            return communityMapper.insert(c);
        });
        return communityNo;
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

    @Autowired
    private ai.neargo.shop.product.mapper.ProductMappers.GoodsMapper goodsMapper;

    @Autowired
    private ai.neargo.shop.merchant.mapper.MerchantMappers.MchStoreCategoryMapper storeCategoryMapper;

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

    @Test
    @DisplayName("★★★ 同一门店连着下架两次不许报错 —— 播种门店行是 check-then-act，连点会撞唯一键")
    void togglingOffTwiceAtTheSameStoreDoesNotBlowUp() throws Exception {
        String biz = merchant("12600180009", "连点下架的店");
        String merchantNo = merchantNoOf(biz);
        TestPlan.grantQuota(planMapper, merchantNo, 3);

        String storeA = defaultStoreNo(biz);
        createStore(biz, "第二家店");   // 多门店才会走 setStoreOnSale

        String goodsNo = onSaleGoodsAt(biz, storeA, "连点下架的柠檬");

        /*
         * 第一次下架会**播种另一家店的行**（把它当时的在售状态固化下来）。
         * 第二次原先仍按「整体为空」判要不要播种 —— 而在并发/连点下那个判据不成立：
         * 第一个事务插了行还没提交，第二个读到的还是空，于是又播一遍，
         * 撞 uk_store_goods，异常被包成通用 500「系统开小差」。
         *
         * 线上实测撞到过（2026-09-20 17:58 柠檬下架）：**第一次其实成功了**，
         * 商家看到的却是「开小差」，以为整个操作没生效。
         */
        offShelfAt(biz, storeA, goodsNo);
        offShelfAt(biz, storeA, goodsNo);   // ← 修之前这一下是 500

        /*
         * ⚠️ **这条用例撤掉任意一道防线都不会红，两道一起撤才红**（实测过）：
         *   ① 按已有 store_no 去重  ② 撞唯一键时当成「别人刚播过」
         * 顺序调用走的是 ①（第一个事务已提交，读得到行），真并发走的是 ②。
         * 所以别因为「删了一道测试还是绿的」就把另一道删掉 ——
         * 它们挡的是两种不同的时序。
         */
    }

    @Test
    @DisplayName("★★★ 停用门店，它的货要从社区池里撤出 —— 只改 mch_store.status 对买家完全无效")
    void suspendingAStoreWithdrawsItsGoodsFromTheCommunityPool() throws Exception {
        String biz = merchant("12600180010", "会关掉一家店的商家");
        String merchantNo = merchantNoOf(biz);
        TestPlan.grantQuota(planMapper, merchantNo, 3);

        String storeA = defaultStoreNo(biz);
        String storeB = createStore(biz, "要被停用的第二家店");
        // 新店的经营类目是空的，不开这一项在 B 店上架会被 GOODS_CATEGORY_NOT_IN_STORE 拒
        TestStoreCategory.open(mvc(), json, biz, storeB, "CAT210");

        /*
         * 这件货**只在 B 店卖**：A 店那行显式下架。
         * 不这么做的话 A 店会一直把它带进池里，停用 B 店也看不出差别 ——
         * 那就变成一条永远绿的用例。
         */
        String goodsNo = onSaleGoodsAt(biz, storeB, "只有第二家店卖的柠檬");
        offShelfAt(biz, storeA, goodsNo);

        assertThat(buyerSees("CM001", goodsNo))
                .as("前置：B 店在营业，买家应当搜得到")
                .isTrue();

        // 商家在「门店管理」里把 B 店停用
        mvc().perform(post("/biz/store/" + storeB + "/status")
                        .header("Authorization", "Bearer " + biz)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("READONLY"));

        /*
         * ★ 修之前这一行是 false（= 买家还搜得到）。
         *
         * 两处缺一不可，**撤掉任意一处这条用例都会红**（做过消融）：
         *   ① {@code StoreAdminServiceImpl.setStatus} 改完状态要 resyncPools ——
         *      不重建的话池行原封不动，停用对买家毫无影响；
         *   ② {@code MerchantGoodsServiceImpl.storesSelling} 要用 activeStoreNos ——
         *      仍用 storeNos 的话重建一遍会把同样的行再写回来，白重建。
         *
         * 线上实测（2026-09-29）：停用「虹选鲜果·福田店」后手工触发重算，
         * 它的 4 件货 × 2859 个社区一行未少。
         */
        assertThat(buyerSees("CM001", goodsNo))
                .as("B 店已停用，它是唯一在卖这件货的店 —— 买家不该再搜得到")
                .isFalse();

        // 再启用回来，货要回到池里：停用不是单向门
        mvc().perform(post("/biz/store/" + storeB + "/status")
                        .header("Authorization", "Bearer " + biz)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"active\":true}"))
                .andExpect(jsonPath("$.code").value(0));
        assertThat(buyerSees("CM001", goodsNo))
                .as("重新启用后要能再被搜到 —— 否则停用一次就等于永久下架")
                .isTrue();
    }

    @Test
    @DisplayName("★★★ 在 A 店下架的货，A 店的买家下不了单 —— prd_store_goods.on_sale 此前在下单链路上没有读者")
    void goodsOffSaleAtThisStoreCannotBeOrdered() throws Exception {
        String biz = merchant("12600180012", "两家店卖法不同的商家");
        String merchantNo = merchantNoOf(biz);
        TestPlan.grantQuota(planMapper, merchantNo, 3);

        String storeA = defaultStoreNo(biz);
        String storeB = createStore(biz, "只有这家店还卖它");
        TestStoreCategory.open(mvc(), json, biz, storeB, "CAT210");

        /*
         * 两家店都上架 → 这件货转成「按店管理」，而且主体总闸是开的。
         * **主体总闸必须留着开**，否则下架 A 店之后主体也跟着关，
         * 那条旧的主体级判据就能拦住下单，这条用例会变成永远绿的。
         */
        String goodsNo = onSaleGoodsAt(biz, storeB, "A 店不卖了的柠檬");
        mvc().perform(post("/biz/goods/" + goodsNo + "/toggle")
                        .header("Authorization", "Bearer " + biz)
                        .header("X-Store-No", storeA)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"onSale\":true}"))
                .andExpect(jsonPath("$.code").value(0));

        String skuNo = firstSkuNo(goodsNo);
        String buyer = login("13800180012");
        addToCart(buyer, goodsNo, skuNo, 1);

        /*
         * **判据用 preview + storeChoices，不用加购。**
         * 加购那条路根本不判上下架（`CartServiceImpl.add` 只判「仅活动可售」），
         * 拿它当判据的话这条用例两边都绿，什么也说明不了。
         * storeChoices 让「买家要去哪家店」变成用例自己说的事，
         * 而不是靠自提点或默认社区去猜 —— 猜错了测到的就是另一家店。
         */
        assertThat(previewOk(buyer, merchantNo, storeA))
                .as("前置：A 店还在卖的时候，结算页本来是算得出来的")
                .isTrue();

        offShelfAt(biz, storeA, goodsNo);
        assertThat(entityOnSale(goodsNo))
                .as("前置：B 店还在卖，主体总闸必须仍是开的，否则这条用例测不到门店级那一层")
                .isTrue();

        /*
         * ★ 修之前这里是通的：`GoodsQueryPortImpl.snapshot` 的 onSale 只读
         * `prd_goods.on_sale`（主体总闸），而门店行在下单链路上**没有任何读者**。
         * 更糟的是，带门店上下文的那一支此前还挂在「有没有配门店价」这个开关后面
         * （`OrderServiceImpl` 里的 if），不分店定价的商家连那一支都走不到。
         *
         * 消融：把 snapshot 里的 `&& !offHere.contains(...)` 去掉，这条必红。
         */
        assertThat(previewCode(buyer, merchantNo, storeA))
                .as("这件货在买家要去的那家店已经下架 —— 结算页不该还算得出来，"
                        + "而且要说『已下架』（70076）不是『商品不存在』："
                        + "买家正看着这件货的详情页，说它不存在他只会反复重试")
                .isEqualTo(70076);
        assertThat(previewOk(buyer, merchantNo, storeB))
                .as("对照量：B 店还在卖，那边必须仍然通 —— 否则这条用例可能只是把整条路测坏了")
                .isTrue();
    }

    @Test
    @DisplayName("★★ B 端「全部」页签要按门店筛 —— 此前切哪家店都是同一批货")
    void allTabIsScopedToCurrentStore() throws Exception {
        String biz = merchant("12600180013", "两家店卖不同品类的商家");
        String merchantNo = merchantNoOf(biz);
        TestPlan.grantQuota(planMapper, merchantNo, 3);

        String storeA = defaultStoreNo(biz);
        String storeB = createStore(biz, "第二家店");
        TestStoreCategory.open(mvc(), json, biz, storeB, "CAT210");

        String onlyAtB = onSaleGoodsAt(biz, storeB, "只有 B 店卖的货");
        offShelfAt(biz, storeA, onlyAtB);

        assertThat(allTabAt(biz, storeB))
                .as("B 店在卖它，B 店的「全部」里当然要有")
                .contains(onlyAtB);

        /*
         * ★ 修之前这里是包含的：「全部」页签走主体级全量，只有「在售/已下架」按店筛。
         * 证照合并之后最明显 —— 四家店并成一个主体，鲜果店的列表里列着粮油。
         *
         * 消融：把 list() 里的 excludeOffSaleHere 调用去掉，这条必红。
         */
        assertThat(allTabAt(biz, storeA))
                .as("A 店没上架它，A 店的「全部」里不该有")
                .doesNotContain(onlyAtB);

        // 上架路径不丢：它还在「已下架」页签里，店主能在那儿把它重新上架
        assertThat(offSaleTabAt(biz, storeA))
                .as("本店未上架的货要留在「已下架」页签里 —— 否则店主永远上不了架")
                .contains(onlyAtB);
    }

    @Test
    @DisplayName("★★ 审核中/已驳回的货不受门店筛影响 —— 那是等店主动手的一批，藏起来他就找不到了")
    void pendingGoodsStayInAllTabRegardlessOfStore() throws Exception {
        String biz = merchant("12600180014", "有待审商品的商家");
        String merchantNo = merchantNoOf(biz);
        TestPlan.grantQuota(planMapper, merchantNo, 3);

        String storeA = defaultStoreNo(biz);
        String storeB = createStore(biz, "第二家店");
        TestStoreCategory.open(mvc(), json, biz, storeB, "CAT210");

        // 先让这家商家进入「按店管理」时代：不这么做 excludeOffSaleHere 整段跳过，用例测不到东西
        String sold = onSaleGoodsAt(biz, storeB, "让商家转成按店管理的货");
        offShelfAt(biz, storeA, sold);

        String pending = saveGoods(biz, "还在审核里的货");   // 不过审
        assertThat(allTabAt(biz, storeA))
                .as("审核中的货在哪家店的「全部」里都要看得见")
                .contains(pending);
    }

    /** 「全部」页签（不传 status）在指定门店下看到的货号 */
    private String allTabAt(String token, String storeNo) throws Exception {
        return mvc().perform(get("/biz/goods").param("size", "50")
                        .header("Authorization", "Bearer " + token)
                        .header("X-Store-No", storeNo))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
    }

    /** 「已下架」页签 —— 上架入口就在这里，它必须仍然收得住本店未上架的货 */
    private String offSaleTabAt(String token, String storeNo) throws Exception {
        return mvc().perform(get("/biz/goods").param("status", "OFF_SALE").param("size", "50")
                        .header("Authorization", "Bearer " + token)
                        .header("X-Store-No", storeNo))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
    }

    private String firstSkuNo(String goodsNo) throws Exception {
        return json.readTree(mvc().perform(get("/mp/goods/" + goodsNo))
                        .andReturn().getResponse().getContentAsString())
                .get("data").get("skus").get(0).get("skuNo").asString();
    }

    private void addToCart(String token, String goodsNo, String skuNo, int qty) throws Exception {
        mvc().perform(post("/mp/cart/add").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"goodsNo\":\"" + goodsNo + "\",\"skuNo\":\"" + skuNo
                                + "\",\"qty\":" + qty + "}"))
                .andExpect(jsonPath("$.code").value(0));
    }

    private boolean previewOk(String token, String merchantNo, String storeNo) throws Exception {
        return previewCode(token, merchantNo, storeNo) == 0;
    }

    /** 结算页的返回码 —— 回码不回布尔，用例才能断言「因为什么拒的」 */
    private int previewCode(String token, String merchantNo, String storeNo) throws Exception {
        String body = mvc().perform(post("/mp/order/preview")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fulfillment\":\"STORE_PICKUP\",\"storeChoices\":[{"
                                + "\"merchantNo\":\"" + merchantNo + "\",\"storeNo\":\"" + storeNo + "\"}]}"))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("code").asInt();
    }

    private boolean entityOnSale(String goodsNo) {
        return ai.neargo.common.data.scope.DataScopeContext.executeWithoutScope(() ->
                Boolean.TRUE.equals(goodsMapper.selectOne(com.baomidou.mybatisplus.core.toolkit.Wrappers
                        .<ai.neargo.shop.product.entity.PrdGoods>lambdaQuery()
                        .eq(ai.neargo.shop.product.entity.PrdGoods::getGoodsNo, goodsNo)
                        .last("limit 1")).getOnSale()));
    }

    @Test
    @DisplayName("★★★ 在粮油店下架一件粮油，不该把它播进只卖水果的那家店 —— 而且是在架的")
    void seedingDoesNotPushGoodsIntoStoresThatDoNotSellThatCategory() throws Exception {
        String biz = merchant("12600180015", "两家店两个业态的商家");
        String merchantNo = merchantNoOf(biz);
        TestPlan.grantQuota(planMapper, merchantNo, 3);

        String grain = defaultStoreNo(biz);          // 只经营 CAT210

        /*
         * **顺序就是这条用例的全部。** 先上架、再开新店、最后下架 ——
         * 这正是证照合并的形状：门店是**后来**才进这个主体的。
         *
         * 第一版我把开店写在上架前面，于是第一次上架那一下就把两家店的行都建了，
         * 后面的 toggle 因为「已经有行」直接跳过播种 —— 被测的那一段根本没跑，
         * 而断言照样绿。消融（把 sells 改回 current）没红才发现。
         */
        String goodsNo = onSaleGoodsAt(biz, grain, "只有粮油店卖的小麦粉");
        assertThat(entityOnSale(goodsNo)).as("前置：主体总闸要开着，否则测不到播种那一步").isTrue();

        String fruit = createStore(biz, "只卖水果的那家");
        /*
         * **要「换成」不要「加上」。**新建门店会继承主体已有的经营类目
         * （实测：新店的类目里带着 CAT210，还挂着 goodsCount=1）。
         * 追加一个 CAT120 的话，这家店在系统看来是既卖纸品又卖水果，
         * 于是判据通过，被测的那一段照样不生效 —— 又是一条假绿。
         *
         * 线上那家「虹选鲜果」是证照合并时搬过来的，保留着自己原来的类目，
         * 只有水果。这里用全量替换造出同一个形状。
         */
        dropStoreCategory(fruit, "CAT210");
        TestStoreCategory.open(mvc(), json, biz, fruit, "CAT120");

        // 店主在粮油店把它下架 —— 这一下才会给「还没有行」的那家新店播种
        offShelfAt(biz, grain, goodsNo);

        /*
         * ★ 修之前：播种把**其他所有门店**都固化成当时的主体级 on_sale（= true），
         * 于是这件粮油以「在架」的身份出现在只卖水果的那家店里。
         *
         * 线上实测（2026-09-30 06:57，虹选科技）：店主在「虹选粮油」下架一件小麦粉，
         * 「虹选鲜果」与「虹选鲜果·福田店」各被插了一行 on_sale=1，
         * 打开鲜果店的商品列表，头四条全是小麦粉。
         *
         * 消融：把 seed.setOnSale(sells) 改回 setOnSale(current)，这条必红。
         */
        assertThat(allTabAt(biz, fruit))
                .as("只卖水果的那家店不经营这一类 —— 播种不该把它塞进去，更不该是在架的")
                .doesNotContain(goodsNo);

        // 对照量：粮油店自己仍看得到它（在「已下架」里），否则可能只是把整条路测坏了
        assertThat(offSaleTabAt(biz, grain))
                .as("粮油店把它下架了，它要留在粮油店的「已下架」里 —— 那是重新上架的入口")
                .contains(goodsNo);
    }

    @Test
    @DisplayName("★★★ 整件下架要把池行清空，再上架要回来 —— 此前这一支一条用例都没看着")
    void offSaleEmptiesThePoolAndOnSaleBringsItBack() throws Exception {
        /*
         * **这条用例钉的是池本身，不是「买家搜不到」。**
         *
         * 2026-10-07 做过消融：把 {@code syncPool} 的「下架 = 从所有池里撤出」整段注掉，
         * 113 条池相关场景用例**一条都没红**（StoreScopedVisibility / StoreGoods /
         * OpsProductGovern / OpsStoreGovern / QuickStart / ServiceArea × 2 /
         * CoarseLocationRegionPool / SuspendedStoreWindDown / GoodsSaleScope /
         * ConsumerBrowse，M9b 的「下架后立刻消失」也是绿的）。
         *
         * 原因是 C 端每一条读商品的查询都自带主体总闸
         * （{@code GoodsServiceImpl} 里六处 {@code on_sale=true AND audit_status=APPROVED}），
         * 池只是个范围筛选视图 —— 主体一关，池里留不留行买家都看不见。
         * 所以凡是从买家侧断言的用例，对「撤没撤池」这件事天生不敏感。
         *
         * 把撤池改成一条批量 UPDATE（原先是逐行 deleteById）之后，这一支必须有人看着：
         * 写错 WHERE 子句的表现是池行一行不少，而买家侧照旧什么都看不到。
         */
        String biz = merchant("12600180030", "要把池清空再填回去的店");
        String merchantNo = merchantNoOf(biz);
        String storeNo = defaultStoreNo(biz);
        String goodsNo = onSaleGoods(biz, "上架下架再上架的抽纸");

        assertThat(poolRows(merchantNo, goodsNo))
                .as("对照量要非零，否则下面在比两个空集")
                .isNotEmpty();
        var before = poolRows(merchantNo, goodsNo);

        offShelfAt(biz, storeNo, goodsNo);

        assertThat(poolRows(merchantNo, goodsNo))
                .as("下架后池里还有行 —— 撤池那条语句的 WHERE 写错了，而买家侧看不出任何差别")
                .isEmpty();

        // 再上架：撤池必须是**逻辑删**，否则 revive 救不回来，而 uk_community_goods_store
        // 不含 deleted 列 —— 直接 insert 会撞唯一键，表现为上架接口 500
        mvc().perform(post("/biz/goods/" + goodsNo + "/toggle")
                        .header("Authorization", "Bearer " + biz)
                        .header("X-Store-No", storeNo)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"onSale\":true}"))
                .andExpect(jsonPath("$.code").value(0));

        assertThat(poolRows(merchantNo, goodsNo))
                .as("重新上架后池要回到原样 —— 回不来就等于下架一次永久不可见")
                .containsExactlyInAnyOrderElementsOf(before);
    }

    /**
     * 直接删掉一家门店的某个经营类目。
     *
     * <p><b>为什么绕过 /biz 接口</b>：走接口会被 {@code STORE_CATEGORY_IN_USE}(80008) 拒 ——
     * 这一类底下有商品就撤不掉，那道闸是对的。但这里要造的状态**本来就不是通过接口达成的**：
     * 线上那家「虹选鲜果」是证照合并时从另一个主体搬过来的，保留着自己原来的类目，
     * 从来没经营过粮油。合并没有走「新建门店」这条路，所以也没有继承。
     *
     * <p>新建门店会继承主体已有的全部经营类目并自动在卖那些货（实测：新店的类目里
     * 带着 CAT210，goodsCount=1）。对单业态商家那是对的 —— 开分店当然卖一样的货。
     * 这条用例要的是合并之后那种**一个主体两个业态**的形状。
     */
    private void dropStoreCategory(String storeNo, String categoryNo) {
        int n = ai.neargo.common.data.scope.DataScopeContext.executeWithoutScope(() ->
                storeCategoryMapper.delete(com.baomidou.mybatisplus.core.toolkit.Wrappers
                        .<ai.neargo.shop.merchant.entity.MchStoreCategory>lambdaQuery()
                        .eq(ai.neargo.shop.merchant.entity.MchStoreCategory::getStoreNo, storeNo)
                        .eq(ai.neargo.shop.merchant.entity.MchStoreCategory::getCategoryNo, categoryNo)));
        assertThat(n).as("没删掉 %s 的 %s —— 这条用例的前提就不成立了", storeNo, categoryNo).isPositive();
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
        TestStoreCategory.open(mvc(), json, token, "CAT210");
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
