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
 * <b>J1：一个商家从零走到能做生意。</b>
 *
 * <p>这是全链路里最长的一条路，也是最能说明「系统能不能用」的一条：
 * 注册 → 申请 → 审核 → 激活 → 进件 → 上架 → 收单 → 发货 → 送达。
 *
 * <p><b>一条旅程中间不重置数据</b> —— 真实世界里也不重置。
 * 每一步的输入都是上一步的产出，这正是集成测试里最容易被绕开的部分：
 * 分开测的话，每个方法都能自己造一份「刚刚好」的数据，
 * 于是「上一步的结果不能喂给下一步」这类问题永远测不出来。
 */
@DisplayName("J1 · 商家从零到能做生意（真 HTTP + 真库）")
class J1MerchantGoLiveE2eTest extends E2eBase {

    @BeforeEach
    void setUp() {
        resetDatabaseOnce();
    }

    @Test
    @DisplayName("14 步全链路")
    void merchantGoesLive() {
        String merchantPhone = nextPhone();
        String buyerPhone = nextPhone();

        // ── 1. 消费者注册登录（入驻目前仍由消费者账号发起）──────────────
        String userToken = loginConsumer(merchantPhone);
        step(1, "消费者登录", merchantPhone);
        assertThat(userToken).as("登录必须拿到令牌").isNotBlank();

        // ── 2. 提交入驻申请：带行业与服务范围 ─────────────────────────
        var applyReq = new LinkedHashMap<String, Object>();
        applyReq.put("name", "E2E 粮油店");
        applyReq.put("subject", "NATURAL_PERSON");
        applyReq.put("industry", "RETAIL");
        applyReq.put("contactName", "E2E 老板");
        applyReq.put("contactPhone", merchantPhone);
        applyReq.put("category", "粮油");
        applyReq.put("desc", "E2E 旅程用");
        applyReq.put("serviceScope", "COMMUNITY");
        applyReq.put("communityNos", List.of("CM001"));
        String applyNo = post("/mp/merchant/apply", userToken, applyReq).get("applyNo").asString();
        step(2, "提交入驻申请", applyNo);

        // ── 3. 运营登录，待办里能看到它 ──────────────────────────────
        String ops = loginOps("admin", "admin123");
        JsonNode queue = get("/ops/merchant/apply/search", ops);
        step(3, "运营待办", queue.get("total").asInt() + " 条");
        assertThat(containsField(queue.get("records"), "applyNo", applyNo))
                .as("刚提交的申请必须出现在待办里").isTrue();

        // ── 4. 受理：让商家知道有人在看 ──────────────────────────────
        post("/ops/merchant/apply/" + applyNo + "/accept", ops, null);
        step(4, "受理", "REVIEWING");

        // ── 5. 通过：五件套必须同事务生成 ────────────────────────────
        post("/ops/merchant/apply/" + applyNo + "/audit", ops,
                Map.of("approved", true));
        String merchantToken = loginConsumer(merchantPhone);   // 作用域在登录时解析
        JsonNode ctx = get("/biz/context", merchantToken);
        String entityNo = ctx.get("merchantNo").asString();
        String defaultStore = ctx.get("currentStoreNo").asString();
        step(5, "审核通过 → 主体/默认店", entityNo + " / " + defaultStore);
        assertThat(entityNo).as("通过后必须有主体").isNotBlank();
        assertThat(defaultStore).as("通过后必须有默认门店 —— 少了它商家没有可经营的门店").isNotBlank();

        // ── 6/7. 进件还没过：工作台该说「还不能收款」──────────────────
        JsonNode payments = get("/biz/merchant/payment", merchantToken);
        JsonNode wechat = payments.get(0);
        step(6, "进件初始态", wechat.get("applyStatus").asString());
        assertThat(wechat.get("canReceiveMoney").asBoolean())
                .as("能开店 ≠ 能收钱：激活后只是占位记录").isFalse();
        assertThat(wechat.get("missing").toString())
                .as("要说清缺什么 —— 「还差结算账户」比「审核中」有用得多")
                .contains("settleAccount");

        // ── 8. 补资料提交进件 ───────────────────────────────────────
        String plainAccount = "6222020000123456789";
        JsonNode done = post("/biz/merchant/payment", merchantToken, new LinkedHashMap<>(Map.of(
                "payChannel", "WECHAT",
                "settleAccount", plainAccount,
                "licenses", List.of("https://cdn/e2e-license.jpg"),
                "contactName", "E2E 老板",
                "contactPhone", merchantPhone)));
        step(8, "进件完成", done.get("payMerchantNo").asString());
        assertThat(done.get("canReceiveMoney").asBoolean()).as("进件通过后应当能收钱").isTrue();
        assertThat(done.get("payMerchantNo").asString()).as("开户成功要生成收款商户号").startsWith("PM");
        /*
         * ★ 明文账号不许出现在任何响应里。
         * 这条只有走真 HTTP 才验得到 —— MockMvc 里也能验，但那时验的是「我们打算不返回」；
         * 这里验的是「序列化之后真的没有」。
         */
        assertThat(lastBody).as("结算账号明文绝不能回显").doesNotContain(plainAccount);

        // ── 9. 再看工作台：卡应该消失 ────────────────────────────────
        boolean stillBlocked = !get("/biz/merchant/payment", merchantToken)
                .get(0).get("canReceiveMoney").asBoolean();
        step(9, "工作台阻塞项", stillBlocked ? "仍有" : "已清");
        assertThat(stillBlocked).as("进件过了就不该再提示「还不能收款」").isFalse();

        // ── 10. 上架商品：录入 → 平台审核 ────────────────────────────
        var goods = new LinkedHashMap<String, Object>();
        goods.put("title", "E2E 五常大米");
        goods.put("subtitle", "旅程用");
        goods.put("type", "NORMAL");
        goods.put("cover", "🍚");
        goods.put("images", List.of());
        goods.put("specGroups", List.of());
        goods.put("skus", List.of(Map.of("optionValues", List.of(), "price", 3980, "stock", 20)));
        String goodsNo = post("/biz/goods/save", merchantToken, goods).get("goodsNo").asString();
        String goodsOps = loginOps("goods", "goods123");
        JsonNode audited = post("/ops/goods/" + goodsNo + "/audit", goodsOps, Map.of("approved", true));
        step(10, "商品过审", audited.get("status").asString());
        assertThat(audited.get("status").asString())
                .as("过审只解锁「可以卖」，上架是商家自己按的按钮")
                .isEqualTo("OFF_SALE");

        // ── 11. 商家上架 → C 端能搜到 ───────────────────────────────
        post("/biz/goods/" + goodsNo + "/toggle", merchantToken, Map.of("onSale", true));
        JsonNode list = get("/mp/goods?page=1&size=100&communityNo=CM001", null);
        step(11, "C 端可见", list.get("total").asInt() + " 件");
        assertThat(containsField(list.get("records"), "goodsNo", goodsNo))
                .as("上架后买家必须能搜到 —— 搜不到多半是服务范围为空").isTrue();

        // ── 12. 买家下单并支付 ──────────────────────────────────────
        String buyerToken = loginConsumer(buyerPhone);
        String skuNo = get("/mp/goods/" + goodsNo, null).get("skus").get(0).get("skuNo").asString();
        JsonNode order = post("/mp/order", buyerToken, new LinkedHashMap<>(Map.of(
                "fulfillment", "EXPRESS",
                "items", List.of(Map.of("goodsNo", goodsNo, "skuNo", skuNo, "qty", 1)))));
        String payOrderNo = order.get("payOrderNo").asString();
        // 真正把单推到待履约的是**通道回调**，不是 /pay（那只是拿收银台参数）
        http().post().uri("/pay/callback/stub")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(Map.of("outTradeNo", payOrderNo, "transactionId", "TX-" + payOrderNo,
                        "sign", "stub-secret"))
                .retrieve().body(String.class);
        JsonNode bizOrders = get("/biz/order", merchantToken, defaultStore);
        step(12, "商家看到订单", bizOrders.get("total").asInt() + " 单");
        assertThat(bizOrders.get("total").asInt())
                .as("商家必须看得到卖出去的单 —— 曾经这里被数据域按 user_no 过滤成 0")
                .isGreaterThan(0);
        String subOrderNo = bizOrders.get("records").get(0).get("orderNo").asString();

        // ── 13. 发货：买家侧必须能看到快递单号 ──────────────────────
        String expressNo = "SF-E2E-0001";
        JsonNode shipped = post("/biz/order/" + subOrderNo + "/ship", merchantToken, defaultStore,
                Map.of("expressNo", expressNo));
        step(13, "发货", shipped.get("status").asString() + " / " + shipped.get("expressNo").asString());
        // 买家侧看到的是展示状态：快递履约的 FULFILLING 就是「已发货」
        assertThat(shipped.get("status").asString()).isEqualTo("SHIPPED");
        /*
         * ★ 这一条是 D2 交付的意义所在：没有单号的「已发货」对买家没有任何用处。
         * 此前 OrderVO 里根本没有 expressNo —— 库里有、契约里有，而后端没带出来。
         */
        JsonNode buyerView = get("/mp/order/" + subOrderNo, buyerToken);
        assertThat(buyerView.get("expressNo").asString())
                .as("买家要靠快递单号查物流；看不到的话发货这件事对他没有意义")
                .isEqualTo(expressNo);

        // ── 14. 标记送达 ────────────────────────────────────────────
        JsonNode delivered = post("/biz/order/" + subOrderNo + "/delivered", merchantToken,
                defaultStore, null);
        step(14, "标记送达", delivered.get("status").asString());
        assertThat(delivered.get("status").asString()).isEqualTo("COMPLETED");
    }

    /** 在数组里找某个字段等于某值的元素 —— 断言「它在列表里」比断言下标稳 */
    private boolean containsField(JsonNode array, String field, String value) {
        for (JsonNode n : array) {
            if (n.get(field) != null && value.equals(n.get(field).asString())) {
                return true;
            }
        }
        return false;
    }
}
