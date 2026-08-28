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
 * <b>J4：进件的失败与重试。</b>
 *
 * <p>进件是**通道批**不是平台批，所以它有一套自己的失败：资料不齐、通道拒、重复开户。
 * 这三种在真实世界里比「一次就过」常见得多，而它们的共同点是
 * <b>不影响开店</b> —— 店照开、货照上架，只是收不了钱。
 *
 * <p>假网关按主体名判：名字里带「驳回」就返回 REJECTED。
 * 这么做是因为**恒成功的假网关会把驳回分支永久藏起来** ——
 * 而那正是进件里最容易写错的一段。
 */
@DisplayName("J4 · 进件驳回与重试")
class J4PaymentApplymentE2eTest extends E2eBase {

    @BeforeEach
    void setUp() {
        resetDatabaseOnce();
    }

    @Test
    @DisplayName("资料不齐被本地拦 → 通道驳回带原因 → 正常主体开通 → 重复开户被拒")
    void applymentFailurePaths() {
        String ops = loginOps("admin", "admin123");

        // ── 1. 一个会被通道拒的主体（名字里带「驳回」）─────────────────
        String rejectPhone = nextPhone();
        String rejectToken = activateMerchant(rejectPhone, "J4 驳回测试店", ops);
        step(1, "已开店（还不能收钱）", "J4 驳回测试店");

        // ── 2. 资料不齐：**本地就拦**，不往通道发 ────────────────────
        /*
         * 通道拒一次要等一个工作日，而缺什么我们自己就能看出来 ——
         * 发过去再被拒，等于白白让商家多等一天。
         */
        int localReject = expectFail("POST", "/biz/merchant/payment", rejectToken,
                Map.of("payChannel", "WECHAT"));
        step(2, "资料不齐 → 本地拦下", localReject);

        // ── 3. 资料齐了，通道驳回：必须带原因 ────────────────────────
        JsonNode rejected = post("/biz/merchant/payment", rejectToken, submitBody("6222020000111122223"));
        step(3, "通道驳回", rejected.get("applyStatus").asString());
        assertThat(rejected.get("applyStatus").asString()).isEqualTo("REJECTED");
        assertThat(rejected.get("canReceiveMoney").asBoolean())
                .as("被拒就是收不了钱 —— 端上照这个布尔显示，不要自己去比状态串").isFalse();
        assertThat(rejected.get("rejectReason").asString())
                .as("驳回必须带原因 —— 不给原因商家只能反复重提同一份资料")
                .isNotBlank();

        // ── 4. 被拒之后店照常开着 ───────────────────────────────────
        /*
         * 这条容易被忽略：进件失败**不影响开店**。
         * 如果被拒会把主体也停掉，商家就会以为自己被平台封了。
         */
        JsonNode ctx = get("/biz/context", rejectToken);
        step(4, "被拒后主体仍在", ctx.get("merchantNo").asString());
        assertThat(ctx.get("merchantNo").asString()).isNotBlank();

        // ── 5. 换一个正常主体：进件应当开通 ──────────────────────────
        String okPhone = nextPhone();
        String okToken = activateMerchant(okPhone, "J4 正常进件店", ops);
        String plain = "6222020000999988887";
        JsonNode active = post("/biz/merchant/payment", okToken, submitBody(plain));
        step(5, "进件开通", active.get("payMerchantNo").asString());
        assertThat(active.get("canReceiveMoney").asBoolean()).isTrue();
        assertThat(active.get("settleAccountMasked").asString())
                .as("只回显掩码 —— B 端也可能被别人拿到（ADR-002 §5）")
                .isEqualTo("****8887");
        assertThat(lastBody).as("明文结算账号绝不能出现在响应里").doesNotContain(plain);

        // ── 6. 已开通再提交 → 必须被拒 ──────────────────────────────
        /*
         * 通道侧重复进件会得到一个**新的二级商户号**，
         * 而历史订单的分账仍指向旧号 —— 那是对不上账的开始，
         * 且要过几个账期才会被发现。
         */
        int dup = expectFail("POST", "/biz/merchant/payment", okToken, submitBody(plain));
        step(6, "重复开户 → 被拒", dup);

        // ── 7. 回查幂等：重复回执不换号 ─────────────────────────────
        String before = active.get("payMerchantNo").asString();
        JsonNode refreshed = post("/biz/merchant/payment/WECHAT/refresh", okToken, null);
        step(7, "回查后收款号", refreshed.get("payMerchantNo").asString());
        assertThat(refreshed.get("payMerchantNo").asString())
                .as("通道重推回执是常态；每次换号的话，门店挂的收款号会指向一个不存在的行")
                .isEqualTo(before);
    }

    // ------------------------------------------------------------------ 辅助

    /** 走完入驻拿到商家令牌 —— 进件的前置是「已经有主体」。 */
    private String activateMerchant(String phone, String shopName, String opsToken) {
        String userToken = loginConsumer(phone);
        var body = new LinkedHashMap<String, Object>();
        body.put("name", shopName);
        body.put("subject", "INDIVIDUAL");
        body.put("industry", "RETAIL");
        body.put("contactName", "J4 老板");
        body.put("contactPhone", phone);
        body.put("category", "日用");
        body.put("desc", "J4 旅程用");
        body.put("serviceScope", "COMMUNITY");
        body.put("communityNos", List.of("CM001"));
        String applyNo = post("/mp/merchant/apply", userToken, body).get("applyNo").asString();
        post("/ops/merchant/apply/" + applyNo + "/audit", opsToken, Map.of("approved", true));
        // 作用域在登录时解析，旧令牌上还没有商家身份
        // A7：/biz/** 只认 btk_
        return loginMerchantOwner(phone);
    }

    private LinkedHashMap<String, Object> submitBody(String account) {
        var body = new LinkedHashMap<String, Object>();
        body.put("payChannel", "WECHAT");
        body.put("settleAccount", account);
        // 个体户必须传执照，小微免 —— 这条规则由 missingOf 判
        body.put("licenses", List.of("https://cdn/j4-license.jpg"));
        body.put("contactName", "J4 老板");
        body.put("contactPhone", "13500000001");
        return body;
    }
}
