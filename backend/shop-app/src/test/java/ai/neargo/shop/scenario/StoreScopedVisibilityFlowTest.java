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
 * <b>买家在 C 端到底搜不搜得到</b> —— 中间还隔着店级货架、上架总闸、审核状态。
 *
 * <p>为什么必须分开验：端口对了而买家那一步没跟上，症状是「商家侧显示在售、
 * 买家哪儿都搜不到」，两边都不报错。这个仓库 2026-08-25 一天之内踩过两次
 * （补证照通过、改经营范围）—— 当时买家读的是社区池，两次都是端口对、池不对。
 * 现在买家侧查询时现算（方案-商品可见性改查询时关联），这里照样从买家那一侧验。
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

        /*
         * 经营范围门店级（V381）：每家店框自己的 —— A 店只框 CM001，B 店只框 CM002。
         * 此前这条用例是「主体足迹两块都框、再用 SUBSET 给每家店各收一块」，
         * 那正是这次修掉的定位错误：范围本来就该在门店上，不该先放主体再收窄。
         */
        storeService.save(merchantNo, storeA, new MerchantStoreService.SaveCommand(
                null, null, null, null, null, null, null, null, null, null, List.of(
                        new MerchantStoreService.AreaCommand("COMMUNITY", "CM001")), null, null));
        storeService.save(merchantNo, storeB, new MerchantStoreService.SaveCommand(
                null, null, null, null, null, null, null, null, null, null, List.of(
                        new MerchantStoreService.AreaCommand("COMMUNITY", "CM002")), null, null));

        fulfillmentService.save(merchantNo, storeA, List.of(new ChannelCmd(
                Fulfillments.MERCHANT_DELIVERY, true, null, null, "ALL", null)));
        fulfillmentService.save(merchantNo, storeB, List.of(new ChannelCmd(
                Fulfillments.MERCHANT_DELIVERY, true, null, null, "ALL", null)));

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
         * ★ 本类的核心断言。按主体并集算的话，这件货会同时出现在 CM001 与 CM002 ——
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
        String biz = merchant("12600180019", "连存两次的店");
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
    @DisplayName("★★★ 现算的可见集合 = 可达集合 —— 楼栋与排除落地之后仍然对得上")
    void visibilityMatchesReachableExactly() throws Exception {
        /*
         * **B 端算出来的和 C 端看到的必须是同一件事。**
         *
         * 直接量「这件货在哪些小区送得到」（GoodsService.deliverableTo，与列表同一个判定），
         * 不走 /mp/goods 列表 —— 列表还自带主体总闸（on_sale ∧ 过审），
         * 从列表断言对「可见性这一步算没算对」天生不敏感（2026-10-07 消融过：113 条用例一条都没红）。
         *
         * EXCLUDE 那一条如果只减了小区没减它的楼，B 端显示「已排除」、C 端站在楼下照样看得到。
         */
        String estate = openCommunityWithCoords("SVC-EST", 30_010_000, 120_010_000);
        String keep = openBuildingUnder("SVC-BLD-KEEP", estate);
        String drop = openBuildingUnder("SVC-BLD-DROP", estate);

        /*
         * 独占一个号（此前与 pendingGoodsStayInAllTabRegardlessOfStore 共用 12600180014，两条用例落在同一个主体上）。
         * 经营范围门店级之后（V381），那条用例开的第二家店保留着它自己的 CM001 范围，
         * 主体并集里就多出 CM001 —— 而这件货只在默认店上架，现算可见里自然没有它。
         * 以前范围是主体级、后一次保存把 CM001 覆盖掉了，共用主体这件事被巧合盖住。
         */
        String biz = merchant("12600180050", "框了小区又单独框了楼", estate);
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

        var candidates = new java.util.LinkedHashSet<>(reach);
        candidates.add(drop);
        var deliverable = candidates.stream()
                .filter(c -> Boolean.TRUE.equals(goodsService.deliverableTo(goodsNo, c)))
                .toList();
        assertThat(deliverable)
                .as("现算的可见集合与可达集合对不上 = 两条路各算各的")
                .containsExactlyInAnyOrderElementsOf(reach);
        assertThat(buyerSees(drop, goodsNo)).as("排除掉的楼里不该看得到").isFalse();
        assertThat(buyerSees(keep, goodsNo)).as("没排除的楼里要看得到").isTrue();
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
    private ai.neargo.shop.product.service.GoodsService goodsService;

    @Autowired
    private ai.neargo.shop.product.service.impl.GoodsVisibility visibility;

    @Autowired
    private ai.neargo.shop.merchant.service.MerchantGovernService governService;

    @Autowired
    private ai.neargo.shop.event.SysOutboxMapper outboxMapper;

    @Autowired
    private ai.neargo.shop.product.mapper.ProductMappers.StoreGoodsMapper storeGoodsMapper;

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
        String body = placeDeliveryOrder(bizToken, buyerPhone, communityNo);
        var data = json.readTree(body).get("data");
        assertThat(data).as("下单没成功：%s", body).isNotNull();
        assertThat(data.get("orderNo")).as("下单响应里没有 orderNo：%s", body).isNotNull();
        String orderNo = data.get("orderNo").asString();
        var subs = ai.neargo.common.data.scope.DataScopeContext.executeWithoutScope(() ->
                subOrderMapper.selectList(com.baomidou.mybatisplus.core.toolkit.Wrappers
                        .<ai.neargo.shop.trade.entity.OrdSubOrder>lambdaQuery()
                        .eq(ai.neargo.shop.trade.entity.OrdSubOrder::getOrderNo, orderNo)));
        assertThat(subs).as("下单了却没有子单？orderNo=%s", orderNo).isNotEmpty();
        return subs.get(0).getStoreNo();
    }

    /** 这家商家上一件新货，买家（住在 communityNo）用商家自送下一单。返回响应原文，成败由调用方判 */
    private String placeDeliveryOrder(String bizToken, String buyerPhone, String communityNo) throws Exception {
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
        return body;
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

    /**
     * 买家在这个社区的目录里<b>翻遍所有页</b>能不能看到这件货。
     *
     * <p><b>为什么翻页而不是只看前 50</b>：{@code /mp/goods} 服务端 {@code size} 封顶 50，
     * CM001 这类共享种子社区在全量跑时累积了别的用例建的大量货。此前这条靠「新货号大、排在前 50」
     * 才绿；业务码改随机段后（ADR-033）排序里不再有单调的号，命中的那件会落到任意一页，
     * 「只看第一页」就成了随机假红/假绿。翻遍所有页只由「社区可见性」决定命中，测的东西不变。
     */
    private boolean buyerSees(String communityNo, String goodsNo) throws Exception {
        for (long page = 1; ; page++) {
            String body = mvc().perform(get("/mp/goods")
                            .param("communityNo", communityNo)
                            .param("page", String.valueOf(page)).param("size", "50"))
                    .andReturn().getResponse().getContentAsString();
            var data = json.readTree(body).get("data");
            var records = data.get("records");
            if (records == null || records.isEmpty()) {
                return false;
            }
            for (var r : records) {
                if (goodsNo.equals(r.get("goodsNo").asString())) {
                    return true;
                }
            }
            if (page * 50 >= data.get("total").asLong()) {
                return false;
            }
        }
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
    @DisplayName("★★★ 停用门店，它的货买家当场看不到 —— 再启用当场回来")
    void suspendingAStoreHidesItsGoodsFromBuyers() throws Exception {
        String biz = merchant("12600180010", "会关掉一家店的商家");
        String merchantNo = merchantNoOf(biz);
        TestPlan.grantQuota(planMapper, merchantNo, 3);

        String storeA = defaultStoreNo(biz);
        String storeB = createStore(biz, "要被停用的第二家店");
        // 新店的经营类目是空的，不开这一项在 B 店上架会被 GOODS_CATEGORY_NOT_IN_STORE 拒
        TestStoreCategory.open(mvc(), json, biz, storeB, "CAT210");

        /*
         * 这件货**只在 B 店卖**：A 店那行显式下架。
         * 不这么做的话 A 店会一直让买家看得到它，停用 B 店也看不出差别 ——
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
         * 停用只写 mch_store.status 一行；买家侧在查询时只认 ACTIVE 门店（StoreReachLoader.allServing）。
         * 撤掉那一个过滤，这条用例就红（消融判据）。
         *
         * 历史：2026-09-29 线上停用「虹选鲜果·福田店」后，它的 4 件货 × 2859 个社区一行未从社区池撤出 ——
         * 那时门店状态在可见性链路上没有任何读者。
         */
        assertThat(buyerSees("CM001", goodsNo))
                .as("B 店已停用，它是唯一在卖这件货的店 —— 买家不该再搜得到")
                .isFalse();

        // 再启用回来，货要当场回来：停用不是单向门
        mvc().perform(post("/biz/store/" + storeB + "/status")
                        .header("Authorization", "Bearer " + biz)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"active\":true}"))
                .andExpect(jsonPath("$.code").value(0));
        assertThat(buyerSees("CM001", goodsNo))
                .as("重新启用后要能再被搜到 —— 否则停用一次就等于永久下架")
                .isTrue();
    }

    @Test
    @DisplayName("★★★ 所属门店下架的货下不了单，也不会被落到同主体别家店 —— 端上带别家店号也不行")
    void goodsOffSaleAtThisStoreCannotBeOrdered() throws Exception {
        String biz = merchant("12600180012", "两家店卖法不同的商家");
        String merchantNo = merchantNoOf(biz);
        TestPlan.grantQuota(planMapper, merchantNo, 3);

        String storeA = defaultStoreNo(biz);
        String storeB = createStore(biz, "只有这家店卖它");
        TestStoreCategory.open(mvc(), json, biz, storeB, "CAT210");

        String goodsNo = onSaleGoodsAt(biz, storeB, "B 店的柠檬");
        String skuNo = firstSkuNo(goodsNo);
        String buyer = login("13800180012");
        addToCart(buyer, goodsNo, skuNo, 1);

        assertThat(previewOk(buyer, merchantNo, storeA))
                .as("前置：在架时结算页算得出来 —— 端上带的门店号是 A 也一样，货是 B 店的（ADR-031）")
                .isTrue();

        offShelfAt(biz, storeB, goodsNo);
        /*
         * 所属门店下架就是下架：要说『已下架』（70076）不是『商品不存在』，
         * 而且不能因为端上带了 A 店就落到 A 店去 —— 那正是 2026-10-09 盐被落到鲜果店的形状。
         */
        assertThat(previewCode(buyer, merchantNo, storeA)).isEqualTo(70076);
        assertThat(previewCode(buyer, merchantNo, storeB)).isEqualTo(70076);
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

        // 商品只属于一家门店（ADR-031）：B 店的货不是 A 店的，A 店「已下架」里也不列它
        assertThat(offSaleTabAt(biz, storeA))
                .as("别家店的货不进本店任何页签 —— A 店要卖同款，是在 A 店建它自己那一件")
                .doesNotContain(onlyAtB);
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
    @DisplayName("★★ 配过送货方式、但没有一路送得到买家的小区 → 结算拒绝，不能当成「没配过」放行")
    void orderRejectedWhenNoRouteReachesBuyer() throws Exception {
        /*
         * 此前结算校验只问一个集合：「这家店在买家小区能选哪几路」。某一路选了子集、买家又不在子集里时，
         * 它把那一路裁掉；全裁光就返回空集 —— 而空集在那里约定的是「没配过、兼容期放行」，
         * 于是商家明明没框的地方照样下得了单。现在先问「配过没有」，再问「所选那一路送不送得到」。
         */
        String in = openCommunityWithCoords("SVC-SUB-IN", 30_020_000, 120_020_000);
        String out = openCommunityWithCoords("SVC-SUB-OUT", 30_030_000, 120_030_000);
        String biz = merchant("12600180033", "自送只送一个小区", in);
        String merchantNo = merchantNoOf(biz);
        String store = defaultStoreNo(biz);
        storeService.save(merchantNo, new MerchantStoreService.SaveCommand(
                null, null, null, null, null, null, null, null, null, null, List.of(
                        new MerchantStoreService.AreaCommand("COMMUNITY", in),
                        new MerchantStoreService.AreaCommand("COMMUNITY", out)), null, null));
        fulfillmentService.save(merchantNo, store, List.of(new ChannelCmd(
                Fulfillments.MERCHANT_DELIVERY, true, null, null, "SUBSET", List.of(areaNoOf(merchantNo, in)))));

        String body = placeDeliveryOrder(biz, "12600180034", out);
        assertThat(json.readTree(body).get("code").asInt())
                .as("自送只送 %s，住在 %s 的买家下自送单必须被拒：%s", in, out, body)
                .isEqualTo(ai.neargo.shop.common.ErrorCode.FULFILLMENT_NOT_SUPPORTED.code());
    }

    @Test
    @DisplayName("★★ 保存送货方式不再顺带给每件货发「上下架变化」—— 设置只改它自己那一行")
    void savingFulfillmentEmitsNoOnSaleEvents() throws Exception {
        /*
         * 此前每保存一次设置都要重建整个主体的社区池，而重建走的是上下架那条链 ——
         * 主体下每件货都多发一条 GOODS_ON_SALE_CHANGED（上下架状态其实没变），进销存白收一遍。
         */
        String biz = merchant("12600180042", "存送货方式的店");
        String merchantNo = merchantNoOf(biz);
        String store = defaultStoreNo(biz);
        String goodsNo = onSaleGoods(biz, "存设置时不该被打扰的纸巾");
        long before = onSaleEvents(goodsNo);
        assertThat(before).as("对照量：上架那一下本来就该发一条").isPositive();

        fulfillmentService.save(merchantNo, store, List.of(new ChannelCmd(
                Fulfillments.MERCHANT_DELIVERY, true, null, null, "ALL", null)));

        assertThat(onSaleEvents(goodsNo)).as("保存送货方式不该给商品发上下架事件").isEqualTo(before);
        assertThat(buyerSees("CM001", goodsNo)).as("货照样看得到").isTrue();
    }

    private long onSaleEvents(String goodsNo) {
        return outboxMapper.selectCount(com.baomidou.mybatisplus.core.toolkit.Wrappers
                .<ai.neargo.shop.event.SysOutbox>lambdaQuery()
                .eq(ai.neargo.shop.event.SysOutbox::getEventType, "GOODS_ON_SALE_CHANGED")
                .like(ai.neargo.shop.event.SysOutbox::getPayload, goodsNo));
    }

    @Test
    @DisplayName("★★★ 主体被处置停业，它的货买家当场看不到 —— 不用任何人去重建什么")
    void suspendedEntityGoodsVanishImmediately() throws Exception {
        /*
         * 此前这是个漏洞：处置停业只改 mch_entity.status，社区池没人重建，
         * 而 C 端列表只看商品自己的 on_sale 与审核 —— 停业商家的货照样被搜到，下单时才被拦。
         * 现在买家侧在查询时只认 ACTIVE 主体（R1），状态一改，下一次查询就对。
         */
        String biz = merchant("12600180041", "会被处置停业的商家");
        String merchantNo = merchantNoOf(biz);
        String goodsNo = onSaleGoods(biz, "停业前在卖的纸巾");
        assertThat(visibility.goodsNos("CM001", null)).as("前置：营业中看得到").contains(goodsNo);

        governService.recordViolation(merchantNo, null, "SERVICE", "SUSPEND", "测试：处置停业", "OPS-TEST");

        assertThat(visibility.goodsNos("CM001", null)).as("主体停业后现算结果里不该有它").doesNotContain(goodsNo);
        assertThat(buyerSees("CM001", goodsNo)).as("买家列表也不该有它").isFalse();
    }

    @Test
    @DisplayName("★★★ 下架后现算结果里就没有它，再上架就回来 —— 直接量可见性这一步，不经过列表的总闸")
    void offSaleLeavesVisibilityAndOnSaleBringsItBack() throws Exception {
        /*
         * **这条用例量的是可见性那一步本身（GoodsVisibility），不是「买家搜不到」。**
         *
         * 2026-10-07 消融过（当时还是社区池）：把「下架撤池」整段注掉，113 条池相关场景用例一条都没红 ——
         * C 端每条读商品的查询都自带主体总闸（on_sale ∧ 过审），从买家侧断言对「可见性算没算对」天生不敏感。
         * 换成查询时现算之后盲区还在同一个位置，所以这里直接量现算的结果。
         * 消融判据：把 GoodsVisibility 里「只取在架的」那个条件去掉，这条必须红。
         */
        String biz = merchant("12600180030", "下架再上架的店");
        String storeNo = defaultStoreNo(biz);
        String goodsNo = onSaleGoods(biz, "上架下架再上架的抽纸");

        assertThat(visibility.goodsNos("CM001", null)).as("前置：在架时现算结果里有它").contains(goodsNo);

        offShelfAt(biz, storeNo, goodsNo);
        assertThat(visibility.goodsNos("CM001", null)).as("下架后现算结果里还有它").doesNotContain(goodsNo);
        assertThat(visibility.deliverable(goodsNo, "CM001")).as("下架后详情还说送得到").isFalse();

        mvc().perform(post("/biz/goods/" + goodsNo + "/toggle")
                        .header("Authorization", "Bearer " + biz)
                        .header("X-Store-No", storeNo)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"onSale\":true}"))
                .andExpect(jsonPath("$.code").value(0));

        assertThat(visibility.goodsNos("CM001", null))
                .as("重新上架后要回来 —— 回不来就等于下架一次永久不可见")
                .contains(goodsNo);
    }

    @Test
    @DisplayName("★★★ 新建的货过审上架就要有店级行 —— 不播的话它会出现在不卖这一类的那家店里")
    void newGoodsOfMultiStoreMerchantGetsStoreRows() throws Exception {
        /*
         * **零行只应属于「主体级时代」的存量商家。**
         *
         * 店级行此前只有一个写入点（setStoreOnSale），而它只被 toggle 调用 ——
         * 让一件货变成在架的另外三条路（免审直通 / 换版收尾 / 过审兑现）一条都不播行。
         * 于是多门店改造之后**新建的每一件货都是零行**，直到有人手动点一次上下架。
         *
         * 零行被当成「未按店管理，跟随主体」，后果有两层：
         *   · B 端：每家店的商品列表里都列着它；
         *   · C 端：买家在不经营这一类的那家店的服务范围里**真能买到**。
         *
         * 线上实测（2026-10-07，虹选科技 4 店 16 件货）：10-05 建的那件柿子是唯一的零行货，
         * 于是它出现在只卖粮油（CAT710）的店的列表里，社区池也在三家店各 23656 行。
         *
         * ⚠️ **这条路不能走 onSaleGoodsAt** —— 它点的是 toggle，而 toggle 里的
         * setStoreOnSale 本来就会播种，走它等于绕开被测的那一段，又是一条假绿。
         * 这里走真实的新品路径：建品 → 提交审核（记下「我要卖它」）→ 运营过审兑现。
         */
        String biz = merchant("12600180031", "两业态的多门店商家·新品");
        String merchantNo = merchantNoOf(biz);
        TestPlan.grantQuota(planMapper, merchantNo, 3);

        String grain = defaultStoreNo(biz);              // 只经营 CAT210
        /*
         * **先给粮油店开 CAT210，再开新店 —— 顺序不能倒。**
         * 新店继承的是「开店那一刻主体已有的经营类目」。倒过来的话（第一版就是）
         * 粮油店此时还没有 CAT210（saveGoods 要到下面才开它），新店继承到的是空集，
         * 于是 dropStoreCategory 一行都删不到，用例红在自己的前置上、走不到被测那段。
         */
        TestStoreCategory.open(mvc(), json, biz, "CAT210");
        String fruit = createStore(biz, "只卖水果的那家·新品");
        dropStoreCategory(fruit, "CAT210");              // 新店会继承主体类目，要换掉不是加上
        TestStoreCategory.open(mvc(), json, biz, fruit, "CAT120");

        // 真实新品路径：建品 → 提交审核 → 过审。**全程没有 toggle**
        String goodsNo = saveGoods(biz, "新建就该有店级行的抽纸");
        mvc().perform(post("/biz/goods/" + goodsNo + "/submit")
                        .header("Authorization", "Bearer " + biz)
                        .header("X-Store-No", grain))
                .andExpect(jsonPath("$.code").value(0));
        approveGoods(goodsNo);

        assertThat(entityOnSale(goodsNo))
                .as("前置：过审要把它兑现成在架，否则下面测的是「没上架所以没播」")
                .isTrue();

        // AC1 + AC2：行要有，且不卖这一类的那家店是 on_sale=0
        assertThat(storeRowsOf(goodsNo))
                .as("新建的货一条店级行都没有 —— 零行会被当成「跟随主体」，它就串到每家店去了")
                .containsOnlyKeys(grain, fruit);
        assertThat(storeRowsOf(goodsNo).get(grain)).as("建品那家店经营 CAT210，要在架").isTrue();
        assertThat(storeRowsOf(goodsNo).get(fruit)).as("只卖水果的那家不经营 CAT210，不该在架").isFalse();

        // AC4：买家侧 —— 断言写成与实现模型无关的形状（走 /mp/goods，不碰任何表）
        assertThat(allTabAt(biz, fruit))
                .as("不卖这一类的那家店的商品列表里不该有它")
                .doesNotContain(goodsNo);
        assertThat(allTabAt(biz, grain))
                .as("对照量：建品那家店必须看得到它，否则可能只是把整条路测坏了")
                .contains(goodsNo);
    }

    @Test
    @DisplayName("★★ 单店商家仍然零行 —— 那时零行就是「跟随主体」，行为一个字都不该变")
    void singleStoreMerchantStillHasNoStoreRows() throws Exception {
        /*
         * 播种只对多门店主体做。单店也播的话，等于把所有存量单店商家的货
         * 一次性转成「店级管理」—— 那是个大得多的改动，而且没有任何人要求过。
         */
        String biz = merchant("12600180032", "就一家店的商家");
        String goodsNo = saveGoods(biz, "单店商家的抽纸");
        mvc().perform(post("/biz/goods/" + goodsNo + "/submit")
                        .header("Authorization", "Bearer " + biz))
                .andExpect(jsonPath("$.code").value(0));
        approveGoods(goodsNo);

        assertThat(entityOnSale(goodsNo)).as("前置：过审要兑现成在架").isTrue();
        assertThat(storeRowsOf(goodsNo))
                .as("单店商家被播了店级行 —— 那是未经要求的语义变更")
                .isEmpty();
    }

    /** 这件货的店级行：门店号 → 在架与否。**空 Map = 零行** */
    private java.util.Map<String, Boolean> storeRowsOf(String goodsNo) {
        return ai.neargo.common.data.scope.DataScopeContext.executeWithoutScope(() ->
                storeGoodsMapper.selectList(com.baomidou.mybatisplus.core.toolkit.Wrappers
                                .<ai.neargo.shop.product.entity.PrdStoreGoods>lambdaQuery()
                                .eq(ai.neargo.shop.product.entity.PrdStoreGoods::getGoodsNo, goodsNo))
                        .stream()
                        .collect(java.util.stream.Collectors.toMap(
                                ai.neargo.shop.product.entity.PrdStoreGoods::getStoreNo,
                                r -> Boolean.TRUE.equals(r.getOnSale()))));
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
    /** 在这家店下建并上架（商品只属于建它的那家店，ADR-031） */
    private String onSaleGoodsAt(String token, String storeNo, String title) throws Exception {
        String goodsNo = saveGoods(token, title, storeNo);
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
        return saveGoods(token, title, null);
    }

    private String saveGoods(String token, String title, String storeNo) throws Exception {
        if (storeNo == null) {
            TestStoreCategory.open(mvc(), json, token, "CAT210");
        } else {
            TestStoreCategory.open(mvc(), json, token, storeNo, "CAT210");
        }
        var req = post("/biz/goods/save").header("Authorization", "Bearer " + token);
        if (storeNo != null) {
            req = req.header("X-Store-No", storeNo);
        }
        return json.readTree(mvc().perform(req
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
