package ai.neargo.shop.scenario;

import ai.neargo.shop.notify.port.StubPushGateway;
import ai.neargo.shop.merchant.entity.MchEntity;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchEntityMapper;
import ai.neargo.shop.message.entity.MsgPushToken;
import ai.neargo.shop.message.entity.SysNotifyLog;
import ai.neargo.shop.message.mapper.MessageMappers.NotifyLogMapper;
import ai.neargo.shop.message.mapper.MessageMappers.PushTokenMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * App 推送全链路（TDD-通知与消息推送 §三期，ADR-018）。
 *
 * <p>走真实链路：绑定设备 → C 下单支付 → 事件投递 → 商家的设备收到**响铃级**推送。
 * 断言里最重要的两条是「新订单是 RING、其余是 NORMAL」（每条都响等于没有响）
 * 和「登出解绑后不再收到」（共用设备换班是隐私事故，不是多推一条）。
 */
@SpringBootTest
@ActiveProfiles("test")
class PushNotifyFlowTest {

    private static final String STUB_SECRET = "stub-secret";

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private ai.neargo.shop.common.OtpStore otpStore;
    @Autowired
    private ai.neargo.shop.event.OutboxDispatcher dispatcher;
    @Autowired
    private StubPushGateway pushStub;
    @Autowired
    private PushTokenMapper tokenMapper;
    @Autowired
    private ai.neargo.shop.message.notify.NotifyLogService notifyLogService;
    @Autowired
    private NotifyLogMapper notifyLogMapper;
    @Autowired
    private MchEntityMapper merchantMapper;
    @Autowired
    private ai.neargo.shop.merchant.mapper.MerchantMappers.MchAccountMapper merchantStaffMapper;
    @Autowired
    private ai.neargo.shop.community.mapper.CommunityMappers.PickupPointMapper pickupMapper;
    @Autowired
    private ai.neargo.shop.merchant.mapper.MerchantMappers.MchStoreMapper storeMapper;
    @Autowired
    private ai.neargo.shop.product.mapper.ProductMappers.SkuMapper skuMapper;

    @BeforeEach
    void drainOutbox() {
        for (int i = 0; i < 50 && dispatcher.pendingCount() > 0; i++) {
            dispatcher.dispatchPending();
        }
        pushStub.clear();
    }

    /** 种子复位，理由见 WxNotifyFlowTest#healPickupSeed。 */
    @BeforeEach
    void healSeeds() {
        var p = pickupMapper.selectOne(Wrappers
                .<ai.neargo.shop.community.entity.CmtPickupPoint>lambdaQuery()
                .eq(ai.neargo.shop.community.entity.CmtPickupPoint::getPickupNo, "PP0001")
                .last("limit 1"));
        if (p != null && (!"ACTIVE".equals(p.getStatus()) || !"ST-M0001".equals(p.getOwnerRef()))) {
            p.setStatus("ACTIVE");
            p.setOwnerRef("ST-M0001");
            p.setType("STORE");
            pickupMapper.updateById(p);
        }
        var s = storeMapper.selectOne(Wrappers
                .<ai.neargo.shop.merchant.entity.MchStore>lambdaQuery()
                .eq(ai.neargo.shop.merchant.entity.MchStore::getStoreNo, "ST-M0001")
                .last("limit 1"));
        if (s != null && !ai.neargo.shop.merchant.entity.MchStore.ACTIVE.equals(s.getStatus())) {
            s.setStatus(ai.neargo.shop.merchant.entity.MchStore.ACTIVE);
            storeMapper.updateById(s);
        }
        var sku = skuMapper.selectOne(Wrappers
                .<ai.neargo.shop.product.entity.PrdSku>lambdaQuery()
                .eq(ai.neargo.shop.product.entity.PrdSku::getSkuNo, "SK0003").last("limit 1"));
        if (sku != null && sku.getStock() < 50) {
            sku.setStock(500);
            skuMapper.updateById(sku);
        }
    }

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup
                        .SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @Test
    @DisplayName("★ 绑定设备 → 新订单 → 商家收到**响铃级**推送 + 留痕")
    void newOrderRingsMerchantDevice() throws Exception {
        String buyer = ai.neargo.shop.support.TestLogin.consumer(mvc(), json, otpStore, "12700127401");
        String owner = loginAsOwnerOf("M0001", "12700127402");
        bindBiz(owner, "cid-owner-1");

        buyAndPay(buyer, "push-new-order");
        dispatcher.dispatchPending();

        List<StubPushGateway.Sent> sent = pushStub.sent().stream()
                .filter(x -> "cid-owner-1".equals(x.clientId())).toList();
        assertThat(sent).hasSize(1);
        assertThat(sent.getFirst().title()).contains("新订单");
        // 响铃是「新订单」独有的：其余经营事件一律常规级
        assertThat(sent.getFirst().level()).isEqualTo("RING");
        // 点开要能落到订单列表 —— 停在首页的推送等于没推
        assertThat(sent.getFirst().link()).contains("/pages/orders/index");

        assertThat(notifyLogMapper.selectCount(Wrappers.<SysNotifyLog>lambdaQuery()
                .eq(SysNotifyLog::getChannel, SysNotifyLog.PUSH)
                .eq(SysNotifyLog::getStatus, SysNotifyLog.SENT))).isPositive();
    }

    @Test
    @DisplayName("★ 登出解绑后不再收到 —— 共用设备换班不能推给上一班的人")
    void unregisteredDeviceStopsReceiving() throws Exception {
        String buyer = ai.neargo.shop.support.TestLogin.consumer(mvc(), json, otpStore, "12700127403");
        String owner = loginAsOwnerOf("M0001", "12700127404");
        bindBiz(owner, "cid-shift-1");

        mvc().perform(post("/biz/push-token/unregister").header("Authorization", "Bearer " + owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientId\":\"cid-shift-1\"}"))
                .andExpect(status().isOk());

        buyAndPay(buyer, "push-after-logout");
        dispatcher.dispatchPending();

        assertThat(pushStub.sent().stream().filter(x -> "cid-shift-1".equals(x.clientId()))).isEmpty();
    }

    @Test
    @DisplayName("★ 换人登录同一台设备：旧绑定被抢占，只有新主人收到")
    void reRegisterTakesOverDevice() throws Exception {
        String buyer = ai.neargo.shop.support.TestLogin.consumer(mvc(), json, otpStore, "12700127405");
        String first = loginAsOwnerOf("M0001", "12700127406");
        bindBiz(first, "cid-shared-1");
        String firstUserNo = userNoOfPhone("12700127406");

        // 同一台设备换人登录（B 端店员换班的真实形态）
        // A7：喂给 bindBiz（/biz/push-token），必须是 btk_
        String second = ai.neargo.shop.support.TestLogin.merchantOwner(mvc(), json, otpStore, "12700127407");
        bindBiz(second, "cid-shared-1");

        // 前一个人名下已经没有这台设备了
        assertThat(tokenMapper.selectCount(Wrappers.<MsgPushToken>lambdaQuery()
                .eq(MsgPushToken::getReceiverNo, firstUserNo)
                .eq(MsgPushToken::getClientId, "cid-shared-1"))).isZero();

        buyAndPay(buyer, "push-takeover");
        dispatcher.dispatchPending();

        // 这家店的新订单不再推给前一个人（他已不是这台设备的主人）
        assertThat(pushStub.sent().stream().filter(x -> "cid-shared-1".equals(x.clientId())))
                .as("设备已归属新主人，旧主人的订单不该再推到这台机器")
                .isEmpty();
    }

    @Test
    @DisplayName("没绑设备的商家：站内信照常，推送静默跳过（多数商家从没装过 App）")
    void noDeviceIsNotAnError() throws Exception {
        String buyer = ai.neargo.shop.support.TestLogin.consumer(mvc(), json, otpStore, "12700127408");
        String owner = loginAsOwnerOf("M0001", "12700127409");

        buyAndPay(buyer, "push-no-device");
        dispatcher.dispatchPending();

        String body = mvc().perform(get("/biz/message").header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).contains("新订单");
        assertThat(pushStub.sent()).isEmpty();
    }

    @Test
    @DisplayName("C 端到货推送是常规级 —— 买家不该被从睡梦中叫醒去取货")
    void arrivalPushIsNotRing() throws Exception {
        String buyer = ai.neargo.shop.support.TestLogin.consumer(mvc(), json, otpStore, "12700127410");
        bindMp(buyer, "cid-buyer-1");
        String owner = loginAsOwnerOf("M0001", "12700127411");

        buyAndPay(buyer, "push-arrive");
        dispatcher.dispatchPending();
        pushStub.clear();   // 只看到货这一段

        String subOrderNo = latestSubOrderNo(buyer);
        mvc().perform(post("/biz/pickup/arrived").header("Authorization", "Bearer " + owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pickupNo\":\"PP0001\",\"orderNos\":[\"" + subOrderNo + "\"]}"))
                .andExpect(status().isOk());
        dispatcher.dispatchPending();

        List<StubPushGateway.Sent> sent = pushStub.sent().stream()
                .filter(x -> "cid-buyer-1".equals(x.clientId())).toList();
        assertThat(sent).hasSize(1);
        assertThat(sent.getFirst().title()).contains("到货");
        assertThat(sent.getFirst().level()).isEqualTo("NORMAL");
    }

    @Test
    @DisplayName("★ 运营端「选择终端」：列出某人绑定的设备，cid 只回掩码不吐全量")
    void pushDevicesListsBoundDevicesMasked() throws Exception {
        String owner = loginAsOwnerOf("M0001", "12700127412");
        bindBiz(owner, "cid-pick-verify-01");
        String userNo = userNoOfPhone("12700127412");

        List<ai.neargo.shop.message.notify.NotifyLogService.PushDeviceVO> devices =
                notifyLogService.pushDevices(userNo);

        var d = devices.stream().filter(x -> "cid-pick-verify-01".equals(x.clientId()))
                .findFirst().orElseThrow(() -> new AssertionError("绑定的设备没出现在列表里"));
        assertThat(d.platform()).isEqualTo("APP_ANDROID");
        // 掩码只露尾部：这份列表是给人挑的，不该把完整 cid 摊在运营眼前
        assertThat(d.clientIdMask()).startsWith("****").doesNotContain("cid-pick-verify-01");
        // 没绑设备的人拿到的是空列表，不是报错（多数用户从没装过 App）
        assertThat(notifyLogService.pushDevices("U-NOBODY-XYZ")).isEmpty();
    }

    // ---------------------------------------------------------------- helpers

    private void bindBiz(String token, String clientId) throws Exception {
        mvc().perform(post("/biz/push-token").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"platform\":\"APP_ANDROID\",\"clientId\":\"" + clientId + "\"}"))
                .andExpect(status().isOk());
    }

    private void bindMp(String token, String clientId) throws Exception {
        mvc().perform(post("/mp/push-token").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"platform\":\"APP_ANDROID\",\"clientId\":\"" + clientId + "\"}"))
                .andExpect(status().isOk());
    }

    /**
     * 按手机号取 user_no。
     *
     * <p>A7 之后不能再拿 B 端令牌打 {@code /mp/user/profile} —— 那是 C 端的链路，
     * btk_ 在那边是 401。要 user_no 就得用**那个人的 C 端会话**去问，
     * 而「同一个人有两个端的会话」正是 A7 之后的常态。
     */
    private String userNoOfPhone(String phone) throws Exception {
        return profileUserNo(ai.neargo.shop.support.TestLogin.consumer(mvc(), json, otpStore, phone));
    }

    private String profileUserNo(String token) throws Exception {
        String body = mvc().perform(get("/mp/user/profile").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("userNo").asString();
    }

    private String latestSubOrderNo(String token) throws Exception {
        String body = mvc().perform(get("/mp/order").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("records").get(0).get("orderNo").asString();
    }

    private void buyAndPay(String token, String idemKey) throws Exception {
        mvc().perform(post("/mp/cart/add").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"goodsNo\":\"G0002\",\"skuNo\":\"SK0003\",\"qty\":1}"))
                .andExpect(status().isOk());
        String body = mvc().perform(post("/mp/order").header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", idemKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fulfillment\":\"STORE_PICKUP\",\"pickupNo\":\"PP0001\"}"))
                .andReturn().getResponse().getContentAsString();
        var data = json.readTree(body).get("data");
        assertThat(data == null ? null : data.get("payOrderNo")).as("下单失败：%s", body).isNotNull();
        mvc().perform(post("/callback/pay/stub").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outTradeNo\":\"" + data.get("payOrderNo").asString()
                                + "\",\"transactionId\":\"TX-" + idemKey
                                + "\",\"sign\":\"" + STUB_SECRET + "\"}"))
                .andExpect(status().isOk());
    }

    private String loginAsOwnerOf(String merchantNo, String phone) throws Exception {
        String token = ai.neargo.shop.support.TestLogin.consumer(mvc(), json, otpStore, phone);
        String userNo = profileUserNo(token);
        MchEntity m = merchantMapper.selectOne(Wrappers.<MchEntity>lambdaQuery()
                .eq(MchEntity::getEntityNo, merchantNo).last("limit 1"));
        m.setOwnerUserNo(userNo);
        grantOwner(m.getEntityNo(), userNo);
        merchantMapper.updateById(m);
        // A7：这个令牌是拿去打 /biz/** 的，必须是 btk_
        return ai.neargo.shop.support.TestLogin.merchantOwner(mvc(), json, otpStore, phone);
    }

    private void grantOwner(String merchantNo, String userNo) {
        var existing = merchantStaffMapper.selectOne(Wrappers
                .<ai.neargo.shop.merchant.entity.MchAccount>lambdaQuery()
                .eq(ai.neargo.shop.merchant.entity.MchAccount::getEntityNo, merchantNo)
                .last("limit 1"));
        if (existing != null) {
            existing.setUserNo(userNo);
            existing.setIsOwner(true);
            existing.setIsPrimary(true);
            existing.setStatus(ai.neargo.shop.merchant.entity.MchAccount.ACTIVE);
            merchantStaffMapper.updateById(existing);
            return;
        }
        var st = new ai.neargo.shop.merchant.entity.MchAccount();
        st.setMchAccountNo("SF-T-" + merchantNo);
        st.setEntityNo(merchantNo);
        st.setUserNo(userNo);
        st.setIsOwner(true);
        st.setIsPrimary(true);
        st.setStatus(ai.neargo.shop.merchant.entity.MchAccount.ACTIVE);
        merchantStaffMapper.insert(st);
    }
}
