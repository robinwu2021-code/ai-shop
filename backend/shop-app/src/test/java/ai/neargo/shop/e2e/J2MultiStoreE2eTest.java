package ai.neargo.shop.e2e;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>J2：多门店隔离与员工视角。</b>
 *
 * <p>多门店的价值不在「能建出三家店」，而在**三家店的账能分开**。
 * 建出来但订单混在一起，等于只加了一个名字。
 *
 * <p>这条旅程补上了一直缺的那条用例：**店员只看得到被授权门店的单**。
 * 此前只验过老板视角 —— 而老板本来就能看到全部，
 * 「A 店店员能翻出 B 店订单」这种越权不会报错，只会安静地多看到一些东西。
 */
@DisplayName("J2 · 多门店隔离与员工视角")
class J2MultiStoreE2eTest extends E2eBase {

    @BeforeEach
    void setUp() {
        resetDatabaseOnce();
    }

    @Test
    @DisplayName("分店 0 单 → 跨店汇总有单 → 店员只看得到授权门店")
    void ordersAreIsolatedPerStore() {
        String ops = loginOps("admin", "admin123");
        String merchantPhone = nextPhone();
        String buyerPhone = nextPhone();

        // ── 1. 开店并卖出一单（落在默认店）─────────────────────────
        String merchantToken = activateMerchant(merchantPhone, "J2 多店测试", ops);
        String defaultStore = get("/biz/context", merchantToken).get("currentStoreNo").asString();
        String goodsNo = putGoodsOnSale(merchantToken, ops, "J2 测试商品");
        placeAndPay(buyerPhone, goodsNo);
        step(1, "默认店", defaultStore);

        // ── 2. 新开一家分店 ────────────────────────────────────────
        String branch = post("/biz/store/create", merchantToken,
                Map.of("name", "J2 文三路分店")).get("storeNo").asString();
        JsonNode stores = get("/biz/store/list", merchantToken);
        step(2, "门店数", stores.size());
        assertThat(stores.size()).as("新建后应当有两家店").isEqualTo(2);

        // ── 3. 分店应当 0 单 ───────────────────────────────────────
        /*
         * 没有门店维度之前，这里会返回默认店的单 —— 而那正是
         * 「建了三家店，订单还是混在一起」的样子：数字是真的，只是不是这家店的。
         */
        int branchOrders = get("/biz/order", merchantToken, branch).get("total").asInt();
        step(3, "分店订单", branchOrders);
        assertThat(branchOrders).as("刚开的分店不该有任何单").isZero();

        // ── 4. 默认店有单；跨店汇总也有 ────────────────────────────
        assertThat(get("/biz/order", merchantToken, defaultStore).get("total").asInt())
                .as("单落在默认店").isGreaterThan(0);
        int all = get("/biz/order?allStores=true", merchantToken, branch).get("total").asInt();
        step(4, "跨店汇总", all);
        assertThat(all).as("老板要跨店汇总时显式要 allStores=true").isGreaterThan(0);

        // ── 5. 加一个店员，只授权到分店 ────────────────────────────
        String staffPhone = nextPhone();
        String mchAccountNo = post("/biz/staff", merchantToken,
                Map.of("loginPhone", staffPhone)).get("mchAccountNo").asString();
        post("/biz/staff/" + mchAccountNo + "/store", merchantToken,
                Map.of("storeNo", branch, "role", "CLERK"));
        step(5, "店员授权到分店", mchAccountNo);

        // ── 6. ★ 店员登录：只看得到分店的单（分店 0 单）────────────
        String staffToken = loginStaff(staffPhone);
        JsonNode staffCtx = get("/biz/context", staffToken);
        step(6, "店员作用域", staffCtx.get("storeNos"));
        assertThat(staffCtx.get("storeNos").toString())
                .as("店员的门店集合只应含被授权的那家 —— 按主体展开等于把所有店交出去")
                .contains(branch).doesNotContain(defaultStore);
        assertThat(get("/biz/order", staffToken).get("total").asInt())
                .as("店员看到的是他那家店的单，不是主体全部").isZero();

        // ── 7. 店员拿默认店的门店号：越权号必须被丢弃 ──────────────
        /*
         * 不抛错而是回落自己的授权店：多半只是端上缓存了一个旧门店号
         * （店被停用、授权被收回），让整个 App 报错不如把他带回能用的那家。
         * 但**越权门店的数据一行都不能出去**。
         */
        JsonNode forged = get("/biz/context", staffToken, defaultStore);
        step(7, "越权门店号", forged.get("currentStoreNo").asString());
        assertThat(forged.get("currentStoreNo").asString())
                .as("店员传别人的门店号必须被丢弃").isNotEqualTo(defaultStore);
        assertThat(get("/biz/order", staffToken, defaultStore).get("total").asInt())
                .as("★ 越权门店号不能让他查出默认店的单").isZero();

        // ── 8. 店员即使要跨店汇总，也只汇总他有权限的店 ────────────
        assertThat(get("/biz/order?allStores=true", staffToken).get("total").asInt())
                .as("allStores 是「我的全部店」不是「主体的全部店」")
                .isZero();
    }

    // ------------------------------------------------------------------ 辅助

    private String activateMerchant(String phone, String shopName, String opsToken) {
        String userToken = loginConsumer(phone);
        var body = new LinkedHashMap<String, Object>();
        body.put("name", shopName);
        body.put("subject", "MICRO");
        body.put("industry", "RETAIL");
        body.put("contactName", "J2 老板");
        body.put("contactPhone", phone);
        body.put("category", "日用");
        body.put("desc", "J2 旅程用");
        body.put("serviceScope", "COMMUNITY");
        body.put("communityNos", List.of("CM001"));
        String applyNo = post("/mp/merchant/apply", userToken, body).get("applyNo").asString();
        post("/ops/merchant/apply/" + applyNo + "/audit", opsToken, Map.of("approved", true));
        return loginConsumer(phone);
    }

    private String putGoodsOnSale(String merchantToken, String opsToken, String title) {
        var goods = new LinkedHashMap<String, Object>();
        goods.put("title", title);
        goods.put("subtitle", "J2");
        goods.put("type", "NORMAL");
        goods.put("cover", "🧺");
        goods.put("images", List.of());
        goods.put("specGroups", List.of());
        goods.put("skus", List.of(Map.of("optionValues", List.of(), "price", 1200, "stock", 30)));
        String goodsNo = post("/biz/goods/save", merchantToken, goods).get("goodsNo").asString();
        post("/ops/goods/" + goodsNo + "/audit", loginOps("goods", "goods123"),
                Map.of("approved", true));
        post("/biz/goods/" + goodsNo + "/toggle", merchantToken, Map.of("onSale", true));
        return goodsNo;
    }

    private void placeAndPay(String buyerPhone, String goodsNo) {
        String buyer = loginConsumer(buyerPhone);
        String skuNo = get("/mp/goods/" + goodsNo, null).get("skus").get(0).get("skuNo").asString();
        String payOrderNo = post("/mp/order", buyer, new LinkedHashMap<>(Map.of(
                "fulfillment", "EXPRESS",
                "items", List.of(Map.of("goodsNo", goodsNo, "skuNo", skuNo, "qty", 1)))))
                .get("payOrderNo").asString();
        // 推进状态的是通道回调，不是 /pay
        http().post().uri("/callback/pay/stub")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(Map.of("outTradeNo", payOrderNo, "transactionId", "TX-" + payOrderNo,
                        "sign", "stub-secret"))
                .retrieve().body(String.class);
    }
}
