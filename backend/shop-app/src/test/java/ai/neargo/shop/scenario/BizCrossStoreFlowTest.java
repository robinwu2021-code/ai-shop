package ai.neargo.shop.scenario;

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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 增值包 P2 · 跨店总览与对比（B-11.12.5 / 11.12.6）。
 *
 * <p>这是增值包**真正卖的东西**。需求 §2.2 原话：只做「能开第二家店」不做总览与对比，
 * 商家花了钱仍然要切来切去，他会觉得被骗了。
 *
 * <p>这个文件守三条，按重要性排：
 * <ol>
 *   <li><b>跨店数字与逐店明细对得上</b>（{@link #overviewMatchesPerStoreOrderList}）——
 *       全文件最重的一条。它守的是「<b>不另存计数器</b>」：总览必须与
 *       {@code /biz/order} 是同一批行。另存一份的下场是「总览说 3 单、
 *       点进去只有 2 单」，而两边的代码各自都说得通 ——
 *       商家从此不再相信任何一个数字，包括对的那些。</li>
 *   <li><b>没买的人被明确拒绝，不是看到空数据</b>（{@link #freePlanIsDeniedThenProCanSee}）——
 *       空列表把「你还没买这个」说成了「它坏了」。</li>
 *   <li><b>子账号只看被授权的店</b>（{@link #staffOnlySeesAuthorizedStore}）——
 *       跨店总览是卖点，不是一道绕过门店授权的后门。</li>
 * </ol>
 *
 * <p><b>下单一律付到款</b>：{@code WAIT_PAY} 不计入成交口径（与
 * {@code MerchantOrderService.stats} 逐字相同）。只下单不付款的话每一条断言都会
 * 少算，而那种失败看起来像聚合写错了。
 */
@SpringBootTest
@ActiveProfiles("test")
class BizCrossStoreFlowTest {

    @Autowired
    private ai.neargo.shop.common.OtpStore otpStore;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private ai.neargo.shop.merchant.mapper.MerchantMappers.EntityPlanMapper planMapper;

    @Autowired
    private ai.neargo.shop.community.mapper.CommunityMappers.PickupPointMapper pickupMapper;

    @Autowired
    private ai.neargo.shop.product.mapper.ProductMappers.StoreStockMapper storeStockMapper;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    // ---------------------------------------------------------------- 能力位门禁

    @Test
    @DisplayName("★★ FREE 访问两个端点都被明确拒绝（70023 且说清当前档位），升到 PRO 才能看")
    void freePlanIsDeniedThenProCanSee() throws Exception {
        String biz = merchant("12601000001", "跨店·免费店");

        /*
         * ★ 拒绝要**明确**，不是返回空数据。
         *
         * 空列表看起来更友好，实际是把「你还没买这个」说成了「它坏了」：
         * 商家有两家店、这一页却什么都没有，他的下一步是打客服电话 ——
         * 而这本该是这个包唯一的一次说服机会。
         */
        for (String path : java.util.List.of("/biz/cross-store/overview", "/biz/cross-store/compare")) {
            JsonNode root = json.readTree(mvc().perform(get(path)
                            .header("Authorization", "Bearer " + biz))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString());
            assertThat(root.get("code").asInt())
                    .as("%s 对 FREE 必须报能力位不足（70023），不是 0 也不是 403", path)
                    .isEqualTo(70023);
            // 文案要说清「当前是哪一档」—— 只说「无权访问」，商家不知道该升到哪
            assertThat(root.get("msg").asString())
                    .as("%s 的错误文案要带上当前档位", path)
                    .contains("FREE");
            // Jackson 的 NullNode 不是 Java null —— 两者都算「没有数据」
            JsonNode data = root.get("data");
            assertThat(data == null || data.isNull())
                    .as("被拒时不该顺带下发数据，实际：%s", data).isTrue();
        }

        // 升到 PRO：同样两个端点，同一个 token，现在通
        TestPlan.grantPro(planMapper, merchantNoOf(biz));
        assertThat(overview(biz).get("code").asInt()).isZero();
        assertThat(compare(biz, 30).get("code").asInt()).isZero();
    }

    // ---------------------------------------------------------------- 与逐店明细对账

    @Test
    @DisplayName("★★★ 跨店总览的数字 = 各店订单列表的真实单量与金额 —— 守的是「不另存计数器」")
    void overviewMatchesPerStoreOrderList() throws Exception {
        String biz = merchant("12601000010", "跨店·对账总店");
        TestPlan.grantPro(planMapper, merchantNoOf(biz));

        String storeA = defaultStoreNo(biz);
        String storeB = createStore(biz, "跨店·对账分店");
        String pickupA = seedStorePickup(storeA, "对账总店自提点");
        String pickupB = seedStorePickup(storeB, "对账分店自提点");

        String goodsNo = listedGoods(biz, "对账测试品", 1000, 100);
        String skuNo = firstSku(goodsNo);

        // A 店两单：1 件 + 3 件 = 4000 分；B 店一单：2 件 = 2000 分。
        // **金额刻意不同** —— 都一样的话「两行搞反了」这种错测不出来
        buyAndPay("13001000010", goodsNo, skuNo, 1, pickupA, "xs-a1");
        buyAndPay("13001000011", goodsNo, skuNo, 3, pickupA, "xs-a2");
        buyAndPay("13001000012", goodsNo, skuNo, 2, pickupB, "xs-b1");
        // 只下单不付款的那一笔**不算成交**，两边都不该出现它
        buyOnly("13001000013", goodsNo, skuNo, 5, pickupB, "xs-b2-unpaid");

        JsonNode rows = overview(biz).get("data").get("stores");
        assertThat(rows).as("两家店都要在总览里").hasSize(2);
        JsonNode rowA = rowOf(rows, storeA);
        JsonNode rowB = rowOf(rows, storeB);

        // ① 与真实下单事实对得上
        assertThat(rowA.get("todayOrders").asInt()).isEqualTo(2);
        assertThat(rowA.get("todayGmvMinor").asLong()).isEqualTo(4000L);
        assertThat(rowB.get("todayOrders").asInt()).isEqualTo(1);
        assertThat(rowB.get("todayGmvMinor").asLong()).isEqualTo(2000L);

        /*
         * ② ★ 与**逐店明细**对得上 —— 这一条才是真正的守卫。
         *
         * 上面那组数字是我在这个用例里下的单，两边可以一起错（比如聚合把未付款的也算进来，
         * 而我恰好把期望值也写成了含未付款的）。跟 `/biz/order` 比才能证明
         * 总览与商家点进去看到的那一页是**同一批行**。
         */
        for (String storeNo : java.util.List.of(storeA, storeB)) {
            JsonNode detail = ordersOfStore(biz, storeNo);
            JsonNode row = rowOf(rows, storeNo);
            long detailPaid = 0L;
            int detailCount = 0;
            for (JsonNode o : detail) {
                // 待支付的单在订单列表里看得到，但不是成交 —— 两边用同一个口径剔除
                if ("WAIT_PAY".equals(o.get("status").asString())) {
                    continue;
                }
                detailCount += 1;
                detailPaid += o.get("amount").get("paidMinor").asLong();
            }
            assertThat(row.get("todayOrders").asInt())
                    .as("门店 %s：总览的单量与订单列表对不上 —— 是不是另存了一份计数器", storeNo)
                    .isEqualTo(detailCount);
            assertThat(row.get("todayGmvMinor").asLong())
                    .as("门店 %s：总览的销售额与订单列表对不上", storeNo)
                    .isEqualTo(detailPaid);
        }

        // ③ 本月口径同样按店分开（今天的单必然落在本月内）
        assertThat(rowA.get("monthOrders").asInt()).isEqualTo(2);
        assertThat(rowA.get("monthGmvMinor").asLong()).isEqualTo(4000L);
        assertThat(rowB.get("monthOrders").asInt()).isEqualTo(1);
        assertThat(rowB.get("monthGmvMinor").asLong()).isEqualTo(2000L);

        // ④ 待办也按店分开：自提单付款后进「待备货」
        assertThat(rowA.get("toStock").asInt()).isEqualTo(2);
        assertThat(rowB.get("toStock").asInt()).isEqualTo(1);

        // ⑤ 门店档案字段跟着一起下发 —— 只给一串门店号，商家分不清哪行是哪家
        assertThat(rowA.get("storeName").asString()).isNotBlank();
        assertThat(rowA.get("isDefault").asBoolean()).isTrue();
        assertThat(rowB.get("isDefault").asBoolean()).isFalse();
        assertThat(rowB.get("status").asString()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("★ 单店商家看到的总览 = 他那一家的数据（不是空列表，也不是报错）")
    void singleStoreMerchantSeesHisOwnStore() throws Exception {
        String biz = merchant("12601000020", "跨店·单店铺");
        // 单店商家也可能买了包（他正准备开第二家）—— 这一页在那之前就该是对的
        TestPlan.grantPro(planMapper, merchantNoOf(biz));

        String store = defaultStoreNo(biz);
        String pickup = seedStorePickup(store, "单店铺自提点");
        String goodsNo = listedGoods(biz, "单店测试品", 700, 50);
        buyAndPay("13001000020", goodsNo, firstSku(goodsNo), 2, pickup, "xs-single-1");

        JsonNode rows = overview(biz).get("data").get("stores");
        assertThat(rows).as("单店商家不该看到空列表").hasSize(1);
        assertThat(rows.get(0).get("storeNo").asString()).isEqualTo(store);
        assertThat(rows.get(0).get("todayOrders").asInt()).isEqualTo(1);
        assertThat(rows.get(0).get("todayGmvMinor").asLong()).isEqualTo(1400L);

        // 与他自己的工作台是同一个数 —— 单店时「跨店总览」与「工作台」必须逐字相等
        JsonNode stats = json.readTree(mvc().perform(get("/biz/dashboard/stats")
                        .header("Authorization", "Bearer " + biz))
                .andReturn().getResponse().getContentAsString()).get("data");
        assertThat(rows.get(0).get("todayGmvMinor").asLong())
                .as("单店商家的跨店总览与工作台对不上 —— 那是两套口径")
                .isEqualTo(stats.get("todayGmvMinor").asLong());
    }

    // ---------------------------------------------------------------- 对比的四个指标

    @Test
    @DisplayName("★★ 复购率 = 窗口内下过 ≥2 单的买家 ÷ 下过单的买家；没单的店是 0 不是除零")
    void repeatRateCountsBuyersWhoCameBack() throws Exception {
        String biz = merchant("12601000030", "跨店·复购总店");
        TestPlan.grantPro(planMapper, merchantNoOf(biz));

        String storeA = defaultStoreNo(biz);
        String storeB = createStore(biz, "跨店·复购分店");
        String pickupA = seedStorePickup(storeA, "复购总店自提点");
        seedStorePickup(storeB, "复购分店自提点");

        String goodsNo = listedGoods(biz, "复购测试品", 500, 100);
        String skuNo = firstSku(goodsNo);

        // A 店：老顾客来了两次，另一位只来过一次 → 2 个买家里 1 个复购 = 0.5
        buyAndPay("13001000030", goodsNo, skuNo, 1, pickupA, "xs-r1");
        buyAndPay("13001000030", goodsNo, skuNo, 1, pickupA, "xs-r2");
        buyAndPay("13001000031", goodsNo, skuNo, 1, pickupA, "xs-r3");

        JsonNode rows = compare(biz, 30).get("data").get("stores");
        JsonNode rowA = rowOf(rows, storeA);
        assertThat(rowA.get("buyers").asInt()).isEqualTo(2);
        assertThat(rowA.get("repeatBuyers").asInt()).isEqualTo(1);
        assertThat(rowA.get("repeatRate").asDouble()).isEqualTo(0.5d);
        assertThat(rowA.get("orders").asInt()).isEqualTo(3);
        assertThat(rowA.get("gmvMinor").asLong()).isEqualTo(1500L);

        /*
         * ★ B 店一单都没有：复购率是 **0**，不是除零、不是 null、也不是整行消失。
         * 一家刚开的店恰恰是最常被拿来对比的那一家 —— 它让整页 500 的话，
         * 商家开新店的第一天就打不开这个功能。
         */
        JsonNode rowB = rowOf(rows, storeB);
        assertThat(rowB.get("orders").asInt()).isZero();
        assertThat(rowB.get("buyers").asInt()).isZero();
        assertThat(rowB.get("repeatRate").asDouble()).isEqualTo(0d);
    }

    @Test
    @DisplayName("★★ 评分是主体级、放在顶层 —— 每店那一行里**没有**这个字段")
    void ratingIsEntityLevelAndNotPerStore() throws Exception {
        String biz = merchant("12601000035", "跨店·评分店");
        TestPlan.grantPro(planMapper, merchantNoOf(biz));
        createStore(biz, "跨店·评分分店");

        JsonNode data = compare(biz, 30).get("data");
        assertThat(data.has("rating")).as("评分要在返回体顶层给一次").isTrue();
        assertThat(data.has("ratingCount")).isTrue();

        /*
         * ★ 每店那一行**要有**自己的评分（V155 起，见 TDD-评价归门店）。
         *
         * 【这条断言反过来了，理由要留档】V155 之前 `rvw_review` 只有 entity_no
         * 没有 store_no，门店维度的评分**没有数据源** —— 那时塞进每一行的后果是
         * 三家店显示同一个数字，商家会把它当成 bug 报上来。所以当时断的是「不能有」。
         *
         * 现在评价落 store_no、门店分由 recomputeRating 覆盖写，
         * 每一行的数是这家店自己的 —— ADR-011 第 3 行要的正是这个。
         * 顶层那个主体分保留：它是 C 端商家卡上显示的那个，两个都给才解释得通
         * 「为什么我的店 4.9 而搜索里是 4.6」。
         */
        for (JsonNode row : data.get("stores")) {
            assertThat(row.has("rating"))
                    .as("门店行里要有自己的评分 —— 否则页面还是只能显示一个主体分")
                    .isTrue();
            assertThat(row.has("ratingCount"))
                    .as("条数也要给：0 条时端上显示「暂无评价」而不是 0 颗星").isTrue();
        }
    }

    @Test
    @DisplayName("★★ 缺货数只数已启用店级库存的 SKU —— 没设过店级库存的不算这家店缺货")
    void outOfStockCountsOnlyStoreManagedSkus() throws Exception {
        String biz = merchant("12601000040", "跨店·缺货总店");
        TestPlan.grantPro(planMapper, merchantNoOf(biz));

        String storeA = defaultStoreNo(biz);
        String storeB = createStore(biz, "跨店·缺货分店");

        String managed = listedGoods(biz, "分店管理品", 900, 30);
        String managedSku = firstSku(managed);
        // 另一件商品**从不设店级库存** —— 它走主体总量，对谁都不算缺货
        String unmanaged = listedGoods(biz, "总量管理品", 900, 0);

        // 起点：一条店级行都没有 → 两家店都是 0
        JsonNode before = compare(biz, 30).get("data").get("stores");
        assertThat(rowOf(before, storeA).get("outOfStockSkus").asInt()).isZero();
        assertThat(rowOf(before, storeB).get("outOfStockSkus").asInt()).isZero();

        // A 店把这个 SKU 设成 0 —— 它现在是「已启用店级库存且卖光了」
        setStoreStock(biz, storeA, managed, managedSku, 0);
        // B 店设成 10 —— 同一个 SKU，B 店不缺货
        setStoreStock(biz, storeB, managed, managedSku, 10);

        JsonNode after = compare(biz, 30).get("data").get("stores");
        assertThat(rowOf(after, storeA).get("outOfStockSkus").asInt())
                .as("A 店把这个 SKU 设成 0，缺货数该 +1").isEqualTo(1);
        assertThat(rowOf(after, storeB).get("outOfStockSkus").asInt())
                .as("B 店还有 10 件，不该算缺货").isZero();

        /*
         * ★ 反面：`unmanaged` 那件商品主体总量是 0，但它**一条店级行都没有**，
         * 走的是主体总量 —— 不算任何一家店缺货。
         *
         * 把它算进来的话，一家什么都没配过的店会显示「缺货 200 件」，
         * 而店主没有任何可做的动作 —— 那个数字只会让他学会忽略这一列。
         */
        assertThat(rowOf(after, storeA).get("outOfStockSkus").asInt())
                .as("没启用店级库存的 SKU（%s）不该被数进来", unmanaged).isEqualTo(1);
        assertThat(storeStockRows(firstSku(unmanaged)))
                .as("这件商品不该有任何店级库存行，否则上面那条断言测的不是它").isZero();
    }

    // ---------------------------------------------------------------- 子账号作用域

    @Test
    @DisplayName("★★ 子账号只被授权 A 店 —— 总览里就只有 A 店，看不到 B 店的流水")
    void staffOnlySeesAuthorizedStore() throws Exception {
        String owner = merchant("12601000050", "跨店·授权总店");
        TestPlan.grantPro(planMapper, merchantNoOf(owner));

        String storeA = defaultStoreNo(owner);
        String storeB = createStore(owner, "跨店·授权分店");
        String pickupA = seedStorePickup(storeA, "授权总店自提点");
        String pickupB = seedStorePickup(storeB, "授权分店自提点");

        String goodsNo = listedGoods(owner, "授权测试品", 1200, 50);
        String skuNo = firstSku(goodsNo);
        buyAndPay("13001000050", goodsNo, skuNo, 1, pickupA, "xs-s-a");
        buyAndPay("13001000051", goodsNo, skuNo, 1, pickupB, "xs-s-b");

        // 老板看两家
        assertThat(overview(owner).get("data").get("stores")).hasSize(2);

        // 只把 A 店授权给店长（MANAGER 有 biz:customer，能看经营数据）
        String staffPhone = "12601000059";
        String accountNo = json.readTree(mvc().perform(post("/biz/staff")
                        .header("Authorization", "Bearer " + owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginPhone\":\"" + staffPhone + "\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString())
                .get("data").get("mchAccountNo").asString();
        mvc().perform(post("/biz/staff/" + accountNo + "/store")
                        .header("Authorization", "Bearer " + owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"storeNo\":\"" + storeA + "\",\"role\":\"MANAGER\"}"))
                .andExpect(jsonPath("$.code").value(0));

        String staff = TestLogin.merchantStaff(mvc(), json, otpStore, staffPhone);
        JsonNode rows = overview(staff).get("data").get("stores");
        /*
         * ★ 跨店总览是这个包的卖点，但它**不是一道绕过门店授权的后门**。
         * 只被授权到 A 店的店员在这里看到 B 店的流水，
         * 与他在订单页越权看到 B 店的单是同一件事 —— 只是更不容易被发现。
         */
        assertThat(rows).as("店员只被授权 A 店，总览里就该只有 A 店").hasSize(1);
        assertThat(rows.get(0).get("storeNo").asString()).isEqualTo(storeA);
        assertThat(rows.get(0).get("todayOrders").asInt()).isEqualTo(1);

        // 对比页同一条口径
        JsonNode cmp = compare(staff, 30).get("data").get("stores");
        assertThat(cmp).hasSize(1);
        assertThat(cmp.get(0).get("storeNo").asString()).isEqualTo(storeA);
    }

    // ---------------------------------------------------------------- 装配

    private JsonNode overview(String token) throws Exception {
        return json.readTree(mvc().perform(get("/biz/cross-store/overview")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private JsonNode compare(String token, int days) throws Exception {
        return json.readTree(mvc().perform(get("/biz/cross-store/compare?days=" + days)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private static JsonNode rowOf(JsonNode rows, String storeNo) {
        for (JsonNode r : rows) {
            if (storeNo.equals(r.get("storeNo").asString())) {
                return r;
            }
        }
        throw new AssertionError("总览/对比里没有门店 " + storeNo + "，实际内容：" + rows);
    }

    /** 某家店的订单明细（商家切到这家店时看到的那一页） */
    private JsonNode ordersOfStore(String token, String storeNo) throws Exception {
        return json.readTree(mvc().perform(get("/biz/order?size=100")
                        .header("Authorization", "Bearer " + token)
                        .header("X-Store-No", storeNo))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString())
                .get("data").get("records");
    }

    /**
     * 给这家门店挂一个自提点，好让买家的单**真的落在这家店**。
     *
     * <p>下单时的履约门店取自自提点的 {@code owner_ref}（{@code OrderServiceImpl.storesOf}），
     * 取不到才回落默认店。不挂点的话所有单都会记在默认店上 ——
     * 「按店分组」那几条断言会全部假绿（两行数字一样对，因为压根只有一家店在收单）。
     *
     * <p>直接插库而不是调接口：建自提点是运营端的动作，B 端没有这个入口。
     * 字段照 {@code DevSeeder.pickup} 那一份。
     */
    private String seedStorePickup(String storeNo, String name) {
        String pickupNo = "PPX-" + storeNo;
        return ai.neargo.common.data.scope.DataScopeContext.executeWithoutScope(() -> {
            var p = new ai.neargo.shop.community.entity.CmtPickupPoint();
            p.setPickupNo(pickupNo);
            p.setCommunityNo("C0001");
            p.setName(name);
            p.setAddress("测试路 1 号");
            p.setType("STORE");
            p.setScope("PERMANENT");
            // STORE 类型的 owner_ref 存的是**门店号**（V16 起）—— 存错这里，
            // 单会落到默认店上，而没有任何地方会报错
            p.setOwnerRef(storeNo);
            p.setOpenHours("08:00-21:00");
            p.setServiceFeeRate(0);
            p.setStatus("ACTIVE");
            pickupMapper.insert(p);
            return pickupNo;
        });
    }

    private long storeStockRows(String skuNo) {
        return ai.neargo.common.data.scope.DataScopeContext.executeWithoutScope(() ->
                storeStockMapper.selectCount(com.baomidou.mybatisplus.core.toolkit.Wrappers
                        .<ai.neargo.shop.product.entity.PrdStoreStock>lambdaQuery()
                        .eq(ai.neargo.shop.product.entity.PrdStoreStock::getSkuNo, skuNo)));
    }

    private void setStoreStock(String token, String storeNo, String goodsNo, String skuNo, int stock)
            throws Exception {
        mvc().perform(post("/biz/goods/" + goodsNo + "/store-stock")
                        .header("Authorization", "Bearer " + token)
                        .header("X-Store-No", storeNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"skuNo\":\"" + skuNo + "\",\"stock\":" + stock + "}"))
                .andExpect(jsonPath("$.code").value(0));
    }

    /** 下单 + **付款**。不付款的单是 WAIT_PAY，不计入成交口径 */
    private void buyAndPay(String buyerPhone, String goodsNo, String skuNo, int qty,
                           String pickupNo, String idem) throws Exception {
        String payOrderNo = buyOnly(buyerPhone, goodsNo, skuNo, qty, pickupNo, idem);
        mvc().perform(post("/callback/pay/stub").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outTradeNo\":\"" + payOrderNo + "\",\"transactionId\":\"TX-" + idem
                                + "\",\"sign\":\"stub-secret\"}"))
                .andExpect(status().isOk());
    }

    /** @return payOrderNo —— 回调认的是它，不是 orderNo */
    private String buyOnly(String buyerPhone, String goodsNo, String skuNo, int qty,
                           String pickupNo, String idem) throws Exception {
        String buyer = login(buyerPhone);
        String body = mvc().perform(post("/mp/order").header("Authorization", "Bearer " + buyer)
                        .header("Idempotency-Key", idem)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"goodsNo\":\"" + goodsNo + "\",\"skuNo\":\"" + skuNo
                                + "\",\"qty\":" + qty + "}],\"fulfillment\":\"STORE_PICKUP\","
                                + "\"pickupNo\":\"" + pickupNo + "\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("payOrderNo").asString();
    }

    private String listedGoods(String token, String title, long price, int stock) throws Exception {
        String body = mvc().perform(post("/biz/goods/save").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\",\"type\":\"NORMAL\","
                                + "\"skus\":[{\"optionValues\":[],\"price\":" + price
                                + ",\"stock\":" + stock + "}]}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        String goodsNo = json.readTree(body).get("data").get("goodsNo").asString();
        mvc().perform(post("/ops/goods/" + goodsNo + "/audit")
                        .header("Authorization", "Bearer " + TestLogin.admin(mvc(), json))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"approved\":true}"))
                .andExpect(jsonPath("$.code").value(0));
        mvc().perform(post("/biz/goods/" + goodsNo + "/toggle").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"onSale\":true}"))
                .andExpect(jsonPath("$.code").value(0));
        return goodsNo;
    }

    private String firstSku(String goodsNo) throws Exception {
        String body = mvc().perform(get("/mp/goods/" + goodsNo))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("skus").get(0).get("skuNo").asString();
    }

    private String defaultStoreNo(String token) throws Exception {
        String body = mvc().perform(get("/biz/store/list").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get(0).get("storeNo").asString();
    }

    private String createStore(String token, String name) throws Exception {
        String body = mvc().perform(post("/biz/store/create").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"address\":\"某某路 8 号\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("storeNo").asString();
    }

    private String merchantNoOf(String bizToken) throws Exception {
        String body = mvc().perform(get("/biz/merchant/profile").header("Authorization", "Bearer " + bizToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("merchantNo").asString();
    }

    private String merchant(String phone, String name) throws Exception {
        String user = login(phone);
        String body = mvc().perform(post("/mp/merchant/apply").header("Authorization", "Bearer " + user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"subject\":\"INDIVIDUAL_BIZ\","
                                + "\"contactName\":\"张三\",\"contactPhone\":\"13900000000\","
                                + "\"category\":\"食品\",\"serviceScope\":\"COMMUNITY\","
                                + "\"communityNos\":[\"CM001\"]}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        String applyNo = json.readTree(body).get("data").get("applyNo").asString();
        mvc().perform(post("/ops/merchant/apply/" + applyNo + "/audit")
                        .header("Authorization", "Bearer " + TestLogin.admin(mvc(), json))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"approved\":true}"))
                .andExpect(jsonPath("$.code").value(0));
        return login(phone);
    }

    private String login(String phone) throws Exception {
        return TestLogin.consumer(mvc(), json, otpStore, phone);
    }
}
