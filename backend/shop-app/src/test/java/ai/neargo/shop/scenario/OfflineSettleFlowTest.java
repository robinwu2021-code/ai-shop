package ai.neargo.shop.scenario;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.Fulfillments;
import ai.neargo.shop.common.PayModes;
import ai.neargo.shop.merchant.entity.MchAccount;
import ai.neargo.shop.merchant.entity.MchQualification;
import ai.neargo.shop.merchant.entity.MchStore;
import ai.neargo.shop.merchant.mapper.MerchantMappers;
import ai.neargo.shop.merchant.service.StoreFulfillmentService;
import ai.neargo.shop.product.entity.PrdGoods;
import ai.neargo.shop.product.mapper.ProductMappers;
import ai.neargo.shop.settle.SettleService;
import ai.neargo.shop.settle.entity.StlBill;
import ai.neargo.shop.settle.entity.StlPayment;
import ai.neargo.shop.settle.mapper.SettleMappers;
import ai.neargo.shop.spi.user.QualificationPort;
import ai.neargo.shop.support.TestLogin;
import ai.neargo.shop.trade.service.OrderService;
import ai.neargo.shop.trade.mapper.TradeMappers;
import ai.neargo.shop.trade.entity.OrdSubOrder;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 线下单的结算账单：终态、零佣金、以及**永远不许发生的那次分账**。
 *
 * <p>线下支付的钱<b>从没进过平台</b>。所以这张账单不是「等着把钱打给商家」，
 * 而是一条记录 —— 记下这单值多少、本该收多少佣金、以及实际收了 0。
 *
 * <p>这里每条用例都对着一个具体的资损：
 * 走进分账 = 从商家**其他**收入里划走一笔说好不收的佣金；
 * 佣金没置 0 = 账面上凭空多出一笔应收；
 * 让掉的数没记 = 「线下这部分生意值多少钱」永远算不出来。
 */
@SpringBootTest
@ActiveProfiles("test")
class OfflineSettleFlowTest {

    private static final String SEED_ENTITY = "M0001";
    private static final String STAFF_PHONE = "13100888021";
    /** 种子费率：THIRD_PARTY × PLATFORM = 500bp（schema-test.sql 的 FR-INIT-TP-PLAT） */
    private static final int SEED_RATE_BP = 500;

    @Autowired
    private ai.neargo.shop.common.OtpStore otpStore;
    @Autowired
    private WebApplicationContext context;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private SettleService settleService;
    @Autowired
    private OrderService orderService;
    @Autowired
    private SettleMappers.BillMapper billMapper;
    @Autowired
    private SettleMappers.PaymentMapper paymentMapper;
    @Autowired
    private TradeMappers.SubOrderMapper subOrderMapper;
    @Autowired
    private ProductMappers.GoodsMapper goodsMapper;
    @Autowired
    private MerchantMappers.MchStoreMapper storeMapper;
    @Autowired
    private MerchantMappers.QualificationMapper qualMapper;
    @Autowired
    private MerchantMappers.MchAccountMapper staffMapper;
    @Autowired
    private StoreFulfillmentService fulfillmentService;
    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
                        .springSecurity())
                .build();
    }

    /** 与 OfflinePayFlowTest 同一套前置：有证 + 门店开关 + 商品支持线下付。 */
    @BeforeEach
    void openOfflinePay() {
        DataScopeContext.executeWithoutScope(() -> {
            if (qualMapper.selectCount(Wrappers.<MchQualification>lambdaQuery()
                    .eq(MchQualification::getEntityNo, SEED_ENTITY)) == 0) {
                MchQualification q = new MchQualification();
                q.setQualNo("QUAL_OFFLINE_S1");
                q.setEntityNo(SEED_ENTITY);
                q.setQualType(QualificationPort.BUSINESS_LICENSE);
                q.setQualName("营业执照");
                q.setExpireAt(System.currentTimeMillis() + 10L * 365 * 24 * 3600 * 1000);
                q.setStatus(MchQualification.VALID);
                qualMapper.insert(q);
            }
            for (MchStore st : storeMapper.selectList(Wrappers.<MchStore>lambdaQuery()
                    .eq(MchStore::getEntityNo, SEED_ENTITY))) {
                st.setOfflinePayEnabled(1);
                // 门店自取的取货地址就是门店地址（服务端刻意不另存一份）——
                // 种子店没地址，开自取会被 STORE_ADDRESS_REQUIRED 拦住
                if (st.getAddress() == null || st.getAddress().isBlank()) {
                    st.setAddress("测试路 1 号");
                }
                storeMapper.updateById(st);
            }
            for (PrdGoods g : goodsMapper.selectList(Wrappers.<PrdGoods>lambdaQuery()
                    .eq(PrdGoods::getEntityNo, SEED_ENTITY))) {
                g.setPayModes("[\"ONLINE\",\"OFFLINE\"]");
                // 开成全集而不是只留自取：**把共享种子改窄，等于把这个坑挪给别人**
                g.setFulfillments("[\"STORE_PICKUP\",\"NEIGHBOR_PICKUP\",\"MERCHANT_DELIVERY\",\"EXPRESS\"]");
                goodsMapper.updateById(g);
            }
            if (staffMapper.selectCount(Wrappers.<MchAccount>lambdaQuery()
                    .eq(MchAccount::getLoginPhone, STAFF_PHONE)) == 0) {
                MchAccount staff = new MchAccount();
                staff.setMchAccountNo("SF-OFFLINE-S1");
                staff.setEntityNo(SEED_ENTITY);
                staff.setLoginPhone(STAFF_PHONE);
                staff.setIsOwner(true);
                staff.setIsPrimary(true);
                staff.setStatus(MchAccount.ACTIVE);
                staffMapper.insert(staff);
            }
            return null;
        });
        /*
         * ⚠️ **门店的自取通道要自己开，不能靠种子。**
         *
         * 下单那道闸的规则是「渠道行为空 = 该店还没迁到 channel 模型，按旧口径放行」。
         * 种子店一行都没有，所以单独跑时怎么下都通 —— 而只要**任何**别的用例
         * 给这家店存过一次渠道（DeliveryRadiusFlowTest 开自送就会建出第一行），
         * 集合就不再是空的，自取立刻变成「本店不支持」，本类五条全红。
         *
         * 这就是那种**单独跑绿、全量跑红**的形态：最容易被当成「我这儿明明是好的」
         * 而放过去。所以前提要自己建，不管别人先跑了什么。
         */
        fulfillmentService.save(SEED_ENTITY, null, List.of(
                new StoreFulfillmentService.ChannelCmd(
                        Fulfillments.STORE_PICKUP, true, null, null, null, null)));
    }

    /**
     * ⚠️ **渠道行要还原。** 本类在 @BeforeEach 里给门店存了渠道，而
     * 「一行都没有 = 按旧口径全放行」是别的用例赖以成立的前提 ——
     * 留着的话，此后走别的履约方式的用例会拿到 70013，
     * 而它们的失败信息里不会有任何一个字提到线下支付。
     *
     * 用 SQL 而不是 mapper.delete：MchFulfillmentChannel 带 @TableLogic，
     * 逻辑删只置 deleted=1，而唯一键里没有 deleted —— 下一个用例再开同一路会撞键。
     */
    @org.junit.jupiter.api.AfterEach
    void restoreChannels() {
        DataScopeContext.executeWithoutScope(() -> jdbc.update(
                "DELETE FROM mch_fulfillment_channel WHERE entity_no = ?", SEED_ENTITY));
    }

    @Test
    @DisplayName("★★ 线下单账单落 OFFLINE_SETTLED、佣金 0、让掉的数记在 waived 上")
    void offlineBillIsTerminalAndCommissionFree() throws Exception {
        StlBill bill = billOfOfflineOrder("13400288001", "settle-offline-1");

        assertThat(bill.getStatus())
                .as("PENDING 是「等着分账」的意思，而这笔钱从没进过平台")
                .isEqualTo(StlBill.OFFLINE_SETTLED);
        assertThat(bill.getCommissionMinor())
                .as("线下不抽佣是拍过板的：这里非 0 就是账面上凭空多一笔应收")
                .isZero();
        assertThat(bill.getWaivedCommissionMinor())
                .as("只记不扣。缺了它，「线下这部分生意值多少钱」永远算不出来")
                .isEqualTo(bill.getGrossMinor() * SEED_RATE_BP / 10000);
        assertThat(bill.getWaivedCommissionMinor())
                .as("种子费率 500bp，让掉的数必须真的大于 0 —— 否则这条用例什么也没验到")
                .isPositive();
        /*
         * 进项票状态**不跟着线下走**：收不收票是税务的事，与钱怎么收无关。
         * 钉住它是因为「终态单还挂着待收票」看着像 bug，容易被后来的人顺手改掉 ——
         * 而它卡不住财务：billsAwaitingInvoice 筛的是 status=CONFIRMED，这张单进不去。
         */
        assertThat(bill.getInvoiceStatus())
                .as("自营就该收票，与线下线上无关")
                .isEqualTo(StlBill.INV_PENDING);
    }

    /**
     * <b>种子商家 M0001 是自营</b>，所以线上单走的是对账链路
     * （{@code PENDING_RECON → CONFIRMED → PAID}），不是分账那条。
     *
     * <p>这让对照组比原本设想的更有力：上一条用例里那张线下单<b>也是自营的</b>，
     * 却落了 OFFLINE_SETTLED —— 于是这两条一起证明了
     * 「线下压过经营模式决定状态」，而不只是「线下不是 PENDING」。
     */
    @Test
    @DisplayName("★★ 对照组：线上单照旧走对账链路 + 照收佣金 —— 否则上一条可能是「谁都不抽佣」")
    void onlineBillStillChargesCommission() throws Exception {
        StlBill bill = billOfOnlineOrder("13400288002", "settle-online-1");

        assertThat(bill.getStatus()).isEqualTo(StlBill.PENDING_RECON);
        assertThat(bill.getCommissionMinor())
                .isEqualTo(bill.getGrossMinor() * SEED_RATE_BP / 10000);
        assertThat(bill.getCommissionMinor()).isPositive();
        assertThat(bill.getWaivedCommissionMinor())
                .as("线上什么都没让，这一列必须是 0 而不是 null —— 报表会直接 SUM 它")
                .isZero();
    }

    @Test
    @DisplayName("★★★ 分账动不了线下单 —— 真发起就是从商家其他收入里划走一笔说好不收的钱")
    void splitLeavesOfflineBillUntouched() throws Exception {
        StlBill bill = billOfOfflineOrder("13400288003", "settle-offline-2");

        settleService.executeSplit(bill.getSettleNo());

        StlBill after = reload(bill.getSettleNo());
        assertThat(after.getStatus())
                .as("状态被改成 SPLITTING/SPLIT，就说明分账指令已经发出去了")
                .isEqualTo(StlBill.OFFLINE_SETTLED);
        assertThat(settleService.splitLogCount(bill.getSettleNo(), "SPLIT"))
                .as("一条分账日志都不该有")
                .isZero();
    }

    @Test
    @DisplayName("★ 通道与下单端落到账单上 —— pay_channel 此前全库为 null")
    void channelAndSceneAreSnapshotted() throws Exception {
        StlBill bill = billOfOfflineOrder("13400288004", "settle-offline-3");

        assertThat(bill.getPayChannel()).isEqualTo(PayModes.OFFLINE);
        assertThat(bill.getPayScene())
                .as("按端切分的报表读它")
                .isEqualTo("MP_WECHAT");
    }

    @Test
    @DisplayName("★★ 线下单不产生 stl_payment —— 通道对账取的正是那张表")
    void offlineOrderCreatesNoChannelPayment() throws Exception {
        StlBill bill = billOfOfflineOrder("13400288005", "settle-offline-4");

        List<StlPayment> payments = DataScopeContext.executeWithoutScope(() ->
                paymentMapper.selectList(Wrappers.<StlPayment>lambdaQuery()
                        .eq(StlPayment::getOrderNo, bill.getOrderNo())));
        assertThat(payments)
                .as("有这条记录，对账任务就会拿它去问通道要一笔从来不存在的流水")
                .isEmpty();
    }

    // ── helpers ──────────────────────────────────────────────

    /** 走完整条线下链路（下单 → 商家确认收款），返回生成出来的那张账单。 */
    private StlBill billOfOfflineOrder(String phone, String idem) throws Exception {
        String token = login(phone);
        addToCart(token);
        String payOrderNo = create(token, idem, "\"payMode\":\"OFFLINE\",");
        String subOrderNo = subOrderNoOf(token, payOrderNo);

        mvc().perform(post("/biz/order/" + subOrderNo + "/confirm-offline-pay")
                        .header("Authorization", "Bearer " + bizToken()))
                .andExpect(jsonPath("$.code").value(0));
        return requireBill(subOrderNo);
    }

    /**
     * 线上单的对照组。
     *
     * <p>直接调 {@code markPaid} 而不是走支付回调：这里要比的是**账单长什么样**，
     * 而不是回调链路 —— 把通道回调也拉进来，这条用例会变成整条支付链路的镜子。
     */
    private StlBill billOfOnlineOrder(String phone, String idem) throws Exception {
        String token = login(phone);
        addToCart(token);
        String payOrderNo = create(token, idem, "");
        String subOrderNo = subOrderNoOf(token, payOrderNo);

        DataScopeContext.executeWithoutScope(() -> {
            orderService.markPaid(mainOrderNoOf(subOrderNo), "WECHAT", "TXN-" + idem);
            return null;
        });
        return requireBill(subOrderNo);
    }

    private String mainOrderNoOf(String subOrderNo) {
        return DataScopeContext.executeWithoutScope(() ->
                subOrderMapper.selectOne(Wrappers.<OrdSubOrder>lambdaQuery()
                        .eq(OrdSubOrder::getSubOrderNo, subOrderNo).last("LIMIT 1"))).getOrderNo();
    }

    private StlBill requireBill(String subOrderNo) {
        StlBill b = DataScopeContext.executeWithoutScope(() ->
                billMapper.selectOne(Wrappers.<StlBill>lambdaQuery()
                        .eq(StlBill::getSubOrderNo, subOrderNo).last("LIMIT 1")));
        assertThat(b).as("子单 %s 没生成结算单", subOrderNo).isNotNull();
        return b;
    }

    private StlBill reload(String settleNo) {
        return DataScopeContext.executeWithoutScope(() ->
                billMapper.selectOne(Wrappers.<StlBill>lambdaQuery()
                        .eq(StlBill::getSettleNo, settleNo).last("LIMIT 1")));
    }

    private String create(String token, String idem, String payModeFragment) throws Exception {
        String body = mvc().perform(post("/mp/order").header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", idem)
                        .header("X-Client", "MP_WECHAT")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{" + payModeFragment
                                + "\"fulfillment\":\"STORE_PICKUP\",\"pickupNo\":\"PP0001\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("payOrderNo").asString();
    }

    /** 订单视角的 orderNo <b>就是子单号</b>（VO 类注释写着），没有 subOrderNo 字段。 */
    private String subOrderNoOf(String token, String payOrderNo) throws Exception {
        String body = mvc().perform(org.springframework.test.web.servlet.request
                        .MockMvcRequestBuilders.get("/mp/order")
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        for (JsonNode r : json.readTree(body).get("data").get("records")) {
            if (payOrderNo.equals(r.path("payOrderNo").asString())) {
                return r.path("orderNo").asString();
            }
        }
        throw new AssertionError("没找到 payOrderNo=" + payOrderNo + " 的子单：" + body);
    }

    private String bizToken() throws Exception {
        return TestLogin.merchantStaff(mvc(), json, otpStore, STAFF_PHONE);
    }

    private String login(String phone) throws Exception {
        return TestLogin.consumer(mvc(), json, otpStore, phone);
    }

    private void addToCart(String token) throws Exception {
        mvc().perform(post("/mp/cart/add").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"goodsNo\":\"G0001\",\"skuNo\":\"SK0001\",\"qty\":1}"))
                .andExpect(status().isOk());
    }
}
