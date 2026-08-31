package ai.neargo.shop.scenario;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.PayModes;
import ai.neargo.shop.common.PayScenes;
import ai.neargo.shop.pay.PointsService;
import ai.neargo.shop.pay.entity.PtsUserAccount;
import ai.neargo.shop.pay.mapper.SettleMappers;
import ai.neargo.shop.community.entity.CmtCommunity;
import ai.neargo.shop.merchant.entity.MchEntity;
import ai.neargo.shop.spi.platform.SettingPort;
import ai.neargo.shop.spi.settle.PointsPort;
import ai.neargo.shop.support.TestLogin;
import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.mapper.TradeMappers;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 积分的<b>端开关</b>：发放读快照、核销读当前端，而回收与解冻谁也拦不住。
 *
 * <p>这一步最容易写错的是<b>发放读了当前请求的端</b>。发分发生在支付成功那一刻，
 * 而那条路上<b>根本没有用户在场</b> —— 支付回调是通道打过来的，没有任何请求头。
 * 读当前端的话，同一笔订单发不发积分会取决于是谁触发的支付确认，
 * 不可复现也无法对账。所以本类的第一条用例<b>刻意从 markPaid 触发</b>
 * （支付回调走的正是它），而不是从 HTTP 请求触发。
 *
 * <p>⚠️ 端标识来自客户端请求头、<b>天然可伪造</b>，只许用于平台策略，
 * 绝不能用于权限或资金判定。本类里所有断言都建立在这个前提上。
 */
@SpringBootTest
@ActiveProfiles("test")
class PointsClientSwitchFlowTest {

    private static final String KEY = "points.client.policy";
    private static final String ALL_OPEN = "{\"earnDeny\":[],\"redeemDeny\":[],\"offlineRedeem\":true}";
    private static final String MERCHANT = "M0001";

    @Autowired
    private ai.neargo.shop.common.OtpStore otpStore;
    @Autowired
    private WebApplicationContext context;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private PointsService pointsService;
    @Autowired
    private PointsPort pointsPort;
    @Autowired
    private SettingPort settingPort;
    @Autowired
    private SettleMappers.PointsAccountMapper accountMapper;
    @Autowired
    private TradeMappers.OrderMapper orderMapper;
    @Autowired
    private TradeMappers.SubOrderMapper subOrderMapper;
    @Autowired
    private ai.neargo.shop.community.mapper.CommunityMappers.CommunityMapper communityMapper;
    @Autowired
    private ai.neargo.shop.merchant.mapper.MerchantMappers.MchEntityMapper merchantMapper;

    private final java.util.Set<String> communityPointsOff = new java.util.HashSet<>();
    private final java.util.Set<String> merchantPointsOff = new java.util.HashSet<>();

    /**
     * ⚠️ <b>必须还原</b>。策略存在 setting 表里，而同一个 Spring 上下文被整个测试套件共用 ——
     * 留一条「禁 MP_WECHAT 发放」在库里，后面几十个跟积分沾边的用例会莫名其妙变红，
     * 而它们的失败信息里不会有任何一个字提到端策略。
     */
    /**
     * 打开积分的四级开关。<b>默认全是关的</b>（{@code points_enabled DEFAULT 0}）——
     * 那是逐级灰度的设计。不开的话本类每一条「应当发/应当抵」的断言都会绿着骗人：
     * 拿到 0 分的真正原因是商家开关，而不是被测的端策略。
     */
    @BeforeEach
    void openPointsSwitches() {
        DataScopeContext.executeWithoutScope(() -> {
            for (CmtCommunity c : communityMapper.selectList(null)) {
                if (!Boolean.TRUE.equals(c.getPointsEnabled())) {
                    communityPointsOff.add(c.getCommunityNo());
                    c.setPointsEnabled(true);
                    communityMapper.updateById(c);
                }
            }
            for (MchEntity m : merchantMapper.selectList(null)) {
                if (!Boolean.TRUE.equals(m.getPointsEnabled())) {
                    merchantPointsOff.add(m.getEntityNo());
                    m.setPointsEnabled(true);
                    merchantMapper.updateById(m);
                }
            }
            return null;
        });
        settingPort.put(KEY, ALL_OPEN, "TEST");
    }

    /**
     * ⚠️ 只把**本用例真的打开过**的那些关回去。
     *
     * 留着的话，此后所有下单链路都会真的发分、真的建积分账户 ——
     * 而别的用例里「这个用户还没有积分账户」是个隐含前提
     * （{@code PointsDeductFlowTest.givePoints} 用的是 insert 不是 upsert）。
     * 撞上就是 DuplicateKeyException，报错里一个字都不会提到积分开关。
     */
    private void restorePointsSwitches() {
        if (communityPointsOff.isEmpty() && merchantPointsOff.isEmpty()) {
            return;
        }
        DataScopeContext.executeWithoutScope(() -> {
            for (CmtCommunity c : communityMapper.selectList(null)) {
                if (communityPointsOff.contains(c.getCommunityNo())) {
                    c.setPointsEnabled(false);
                    communityMapper.updateById(c);
                }
            }
            for (MchEntity m : merchantMapper.selectList(null)) {
                if (merchantPointsOff.contains(m.getEntityNo())) {
                    m.setPointsEnabled(false);
                    merchantMapper.updateById(m);
                }
            }
            return null;
        });
        communityPointsOff.clear();
        merchantPointsOff.clear();
    }

    @AfterEach
    void restorePolicy() {
        settingPort.put(KEY, ALL_OPEN, "TEST");
        restorePointsSwitches();
    }

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
                        .springSecurity())
                .build();
    }

    // ── 发放：读订单快照 ──────────────────────────────────────

    @Test
    @DisplayName("★★★ 禁掉小程序发放 → 小程序下的单在【支付回调】里也不发分（那条路上没有当前端）")
    void earnDeniedBySnapshotSceneEvenWithoutCurrentClient() throws Exception {
        denyEarn(PayScenes.MP_WECHAT);
        String user = "U-PCS-1";
        String subOrderNo = orderPaidFrom(user, PayScenes.MP_WECHAT);

        /*
         * 直接调 grant —— 支付回调走的就是这条（OrderServiceImpl.markPaid 里）。
         * 整条链路上没有任何 HTTP 请求头，所以「读当前端」的实现在这里会读到 null，
         * 从而放行；只有真的读了订单快照才拦得住。
         */
        PointsPort.GrantResult r = pointsPort.grant(user, MERCHANT,
                List.of(new PointsPort.EarnLine("G0001", null, 10_000L)), subOrderNo);

        assertThat(r.points())
                .as("读当前端的实现会在这里发出分来 —— 这条用例存在的全部意义")
                .isZero();
        assertThat(pointsService.canEarn(subOrderNo).reason()).isNotBlank();
    }

    @Test
    @DisplayName("★★ 对照：同一条策略下，H5 下的单照发 —— 否则上一条可能只是「全关了」")
    void earnStillWorksOnOtherClients() throws Exception {
        denyEarn(PayScenes.MP_WECHAT);
        String user = "U-PCS-2";
        String subOrderNo = orderPaidFrom(user, PayScenes.H5);

        PointsPort.GrantResult r = pointsPort.grant(user, MERCHANT,
                List.of(new PointsPort.EarnLine("G0001", null, 10_000L)), subOrderNo);

        assertThat(r.points()).isPositive();
    }

    @Test
    @DisplayName("★★ 认不出的端一律放行 —— 存量订单的 pay_scene 全是空的")
    void unknownSceneIsAllowed() throws Exception {
        denyEarn(PayScenes.MP_WECHAT);
        String user = "U-PCS-3";
        String subOrderNo = orderPaidFrom(user, null);

        assertThat(pointsService.canEarn(subOrderNo).allowed())
                .as("按允许名单实现的话，这里会把存量单全部拦掉，而且是静默的")
                .isTrue();
    }

    @Test
    @DisplayName("★★★ 事后关掉某个端，【不追回】已经发出去的分 —— 关一次开关就是一次资金事故")
    void closingSwitchDoesNotClawBackGrantedPoints() throws Exception {
        String user = "U-PCS-4";
        String subOrderNo = orderPaidFrom(user, PayScenes.MP_WECHAT);
        PointsPort.GrantResult granted = pointsPort.grant(user, MERCHANT,
                List.of(new PointsPort.EarnLine("G0001", null, 10_000L)), subOrderNo);
        assertThat(granted.points()).isPositive();

        denyEarn(PayScenes.MP_WECHAT);

        assertThat(pending(user))
                .as("与商家开关同一条规矩：关闭只影响将来，已发出的分仍然有效")
                .isEqualTo(granted.points());
    }

    // ── 线下：收不到费用金，所以不发分 ────────────────────────

    @Test
    @DisplayName("★★★ 当面付款的单不发积分 —— 那笔费用金收不到，发了就是给池子挂一笔永远不到的钱")
    void offlineOrderEarnsNothing() {
        String user = "U-PCS-9";
        String subOrderNo = paidOrderWith(user, PayScenes.MP_WECHAT, PayModes.OFFLINE);

        var r = pointsPort.grant(user, MERCHANT,
                List.of(new PointsPort.EarnLine("G0001", null, 10_000L)), subOrderNo);

        assertThat(r.points())
                .as("线上靠分账扣费用金，自营从应付货款里净出来 —— 线下两条路都没有")
                .isZero();
        assertThat(r.feeMinor())
                .as("**费用金也必须是 0**：只挡分不挡费的话，池子反而多收了一笔没有对价的钱")
                .isZero();
        assertThat(pointsService.canEarn(subOrderNo).reason())
                .as("要说得出原因，否则商家问「为什么这单没发分」没人答得上来")
                .isNotBlank();
    }

    @Test
    @DisplayName("★★ 对照：同一个商家的线上单照发 —— 否则上一条可能只是「谁都不发」")
    void onlineOrderStillEarns() {
        String user = "U-PCS-10";
        String subOrderNo = paidOrderWith(user, PayScenes.MP_WECHAT, "WECHAT");

        assertThat(pointsPort.grant(user, MERCHANT,
                List.of(new PointsPort.EarnLine("G0001", null, 10_000L)), subOrderNo).points())
                .isPositive();
    }

    @Test
    @DisplayName("★★ 线下单仍然可以【用】积分抵扣 —— 发放与核销是两件事，别一起关掉")
    void offlineCanStillRedeem() {
        String user = "U-PCS-11";
        balance(user, 5_000L);

        assertThat(pointsService.deductible(user, MERCHANT, 100_000L,
                PayModes.OFFLINE, PayScenes.MP_WECHAT).maxPoints())
                .as("抵扣的成本本来就在商家（当面少收即是抵扣），与收不收得到费用金无关")
                .isPositive();
    }

    // ── 回收：不经任何端判定 ──────────────────────────────────

    @Test
    @DisplayName("★★★ 在被禁的端上退回积分照常执行 —— 这是资金正确性，不是策略")
    void reverseIgnoresClientPolicy() throws Exception {
        String user = "U-PCS-5";
        long before = balance(user, 5_000L);
        var deduction = pointsPort.deduct(user, 100L,
                List.of(new PointsPort.Target(MERCHANT, 100_000L, "SUB-PCS-5")),
                PayModes.ONLINE, PayScenes.MP_WECHAT);
        assertThat(deduction.points()).isPositive();

        // 扣完之后再把这个端两头都禁掉，然后退款
        denyBoth(PayScenes.MP_WECHAT);
        pointsPort.reverse("SUB-PCS-5", "测试退款");

        assertThat(balance(user, -1))
                .as("退款收不回已扣的分 = 用户白花了钱；反过来收不回已发的分 = 账上凭空多出积分。"
                        + "两个方向都是资金事故，不能因为「端被禁了」就不做")
                .isEqualTo(before);
    }

    // ── 核销：读当前端 ────────────────────────────────────────

    @Test
    @DisplayName("★★ 禁掉小程序核销 → 结算页拿不到额度且带得出原因；同一用户在 H5 照常")
    void redeemDeniedOnDeniedClientOnly() {
        String user = "U-PCS-6";
        balance(user, 5_000L);
        denyRedeem(PayScenes.MP_WECHAT);

        var onMp = pointsService.deductible(user, MERCHANT, 100_000L,
                PayModes.ONLINE, PayScenes.MP_WECHAT);
        assertThat(onMp.maxPoints()).isZero();
        assertThat(onMp.disabledReason())
                .as("只给 0 不给原因的话，端上只能显示「积分不可用」，客服每通电话都得靠猜")
                .isNotBlank();

        var onH5 = pointsService.deductible(user, MERCHANT, 100_000L,
                PayModes.ONLINE, PayScenes.H5);
        assertThat(onH5.maxPoints()).isPositive();
    }

    @Test
    @DisplayName("★★ 关掉「线下可抵扣」→ 线下单拿不到额度，线上单不受影响")
    void offlineRedeemSwitch() {
        String user = "U-PCS-7";
        balance(user, 5_000L);
        policy("[]", "[]", false);

        assertThat(pointsService.deductible(user, MERCHANT, 100_000L,
                PayModes.OFFLINE, PayScenes.MP_WECHAT).maxPoints()).isZero();
        assertThat(pointsService.deductible(user, MERCHANT, 100_000L,
                PayModes.ONLINE, PayScenes.MP_WECHAT).maxPoints()).isPositive();
    }

    @Test
    @DisplayName("★★ 试算与下单【调同一个判定】—— 两处走岔就是「说能抵 30、只抵了 25」")
    void quoteAndPlaceAgree() {
        String user = "U-PCS-8";
        balance(user, 5_000L);
        denyRedeem(PayScenes.MP_WECHAT);

        assertThat(pointsService.deductible(user, MERCHANT, 100_000L,
                PayModes.ONLINE, PayScenes.MP_WECHAT).maxPoints()).isZero();
        assertThat(pointsPort.deduct(user, 100L,
                List.of(new PointsPort.Target(MERCHANT, 100_000L, "SUB-PCS-8")),
                PayModes.ONLINE, PayScenes.MP_WECHAT).points()).isZero();
    }

    // ── 运营端开关 ────────────────────────────────────────────

    @Test
    @DisplayName("★★ 端名拼错写不进去 —— 存进去一个错词不会报错，只会安静地谁也拦不住")
    void opsRejectsUnknownSceneName() throws Exception {
        String ops = TestLogin.admin(mvc(), json);
        String body = mvc().perform(post("/ops/points/client-policy")
                        .header("Authorization", "Bearer " + ops)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"earnDeny\":[\"WECHAT_MP\"],\"redeemDeny\":[],"
                                + "\"offlineRedeem\":true}"))
                .andReturn().getResponse().getContentAsString();

        // 这个应用错误也返回 HTTP 200，业务码在响应体里
        assertThat(json.readTree(body).path("code").asInt())
                .as("MP_WECHAT 写成 WECHAT_MP：运营会以为已经关掉了")
                .isNotZero();
    }

    // ── helpers ──────────────────────────────────────────────

    private void denyEarn(String scene) {
        policy("[\"" + scene + "\"]", "[]", true);
    }

    private void denyRedeem(String scene) {
        policy("[]", "[\"" + scene + "\"]", true);
    }

    private void denyBoth(String scene) {
        policy("[\"" + scene + "\"]", "[\"" + scene + "\"]", true);
    }

    private void policy(String earnDeny, String redeemDeny, boolean offlineRedeem) {
        settingPort.put(KEY, "{\"earnDeny\":" + earnDeny + ",\"redeemDeny\":" + redeemDeny
                + ",\"offlineRedeem\":" + offlineRedeem + "}", "TEST");
    }

    /**
     * 造一张已支付的订单并返回子单号，{@code pay_scene} 落成指定的端。
     *
     * <p>直接建表数据而不是走下单接口：这几条用例要验的是<b>发分那一刻读了哪里的端</b>，
     * 把整条下单链路拉进来的话，任何一处不相干的改动都会让它们红。
     */
    private String orderPaidFrom(String userNo, String scene) {
        return paidOrderWith(userNo, scene, "WECHAT");
    }

    private String paidOrderWith(String userNo, String scene, String payChannel) {
        String orderNo = "ORD-PCS-" + System.nanoTime();
        String subOrderNo = "SUB-" + orderNo;
        DataScopeContext.executeWithoutScope(() -> {
            OrdOrder o = new OrdOrder();
            o.setOrderNo(orderNo);
            o.setUserNo(userNo);
            o.setStatus(OrdOrder.PAID);
            o.setPayAmount(10_000L);
            o.setPayScene(scene);
            o.setPayChannel(payChannel);
            orderMapper.insert(o);

            OrdSubOrder sub = new OrdSubOrder();
            sub.setSubOrderNo(subOrderNo);
            sub.setOrderNo(orderNo);
            sub.setUserNo(userNo);
            sub.setEntityNo(MERCHANT);
            sub.setStatus(OrdOrder.PAID);
            sub.setPayAmount(10_000L);
            subOrderMapper.insert(sub);
            return null;
        });
        return subOrderNo;
    }

    private long pending(String userNo) {
        PtsUserAccount a = DataScopeContext.executeWithoutScope(() ->
                accountMapper.selectOne(Wrappers.<PtsUserAccount>lambdaQuery()
                        .eq(PtsUserAccount::getUserNo, userNo).last("LIMIT 1")));
        return a == null || a.getPendingBalance() == null ? 0L : a.getPendingBalance();
    }

    /** 读余额；{@code seed >= 0} 时先把余额设成 seed。 */
    private long balance(String userNo, long seed) {
        return DataScopeContext.executeWithoutScope(() -> {
            PtsUserAccount a = accountMapper.selectOne(Wrappers.<PtsUserAccount>lambdaQuery()
                    .eq(PtsUserAccount::getUserNo, userNo).last("LIMIT 1"));
            if (seed >= 0) {
                if (a == null) {
                    a = new PtsUserAccount();
                    a.setUserNo(userNo);
                    a.setMarket("CN");
                    a.setBalance(seed);
                    a.setPendingBalance(0L);
                    a.setTotalEarn(seed);
                    accountMapper.insert(a);
                } else {
                    a.setBalance(seed);
                    accountMapper.updateById(a);
                }
            }
            return a == null || a.getBalance() == null ? 0L : a.getBalance();
        });
    }
}
