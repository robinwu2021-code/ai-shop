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
import ai.neargo.shop.pay.SettleService;
import ai.neargo.shop.pay.entity.StlBill;
import ai.neargo.shop.pay.entity.StlPayment;
import ai.neargo.shop.pay.mapper.SettleMappers;
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

    @Autowired
    private ai.neargo.shop.platform.PayChannelRateService payChannelRateService;

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

    /**
     * 通道手续费的四列：<b>收了多少、按哪一版、什么来源、从谁身上收</b>。
     *
     * <p>这四列建表时就有，而**此前全库零写入** —— 运营端能配的
     * {@code sys_pay_channel_rate} 也没有任何消费者。配了费率不影响任何一笔账，
     * 运营却会以为改了就生效了。
     */
    @Test
    @DisplayName("★★★ 通道手续费按**实付**算，不是按结算基数 —— 用 gross 会让手续费凭空变大")
    void channelFeeIsBasedOnPaidAmountNotGross() throws Exception {
        var rate = addWechatRate(60, 0L);
        try {
            String token = login("13400288011");
            addToCart(token);
            String payOrderNo = create(token, "settle-fee-1", "");
            String subOrderNo = subOrderNoOf(token, payOrderNo);

            /*
             * 直接改子单的积分抵扣，**不跑整条积分链路**：这里要验的是
             * 「基数取哪一个数」，而 gross = 实付 + 平台优惠 + 积分抵扣。
             * 不制造这个差额的话，gross 与实付相等 ——
             * 那样写出来的断言两种实现都能通过，**等于什么也没验**。
             */
            DataScopeContext.executeWithoutScope(() -> jdbc.update(
                    "UPDATE ord_sub_order SET points_deduct_minor = 500 WHERE sub_order_no = ?",
                    subOrderNo));
            long paid = DataScopeContext.executeWithoutScope(() ->
                    subOrderMapper.selectOne(Wrappers.<OrdSubOrder>lambdaQuery()
                            .eq(OrdSubOrder::getSubOrderNo, subOrderNo).last("LIMIT 1"))).getPayAmount();

            DataScopeContext.executeWithoutScope(() -> {
                orderService.markPaid(mainOrderNoOf(subOrderNo), "WECHAT", "TXN-settle-fee-1");
                return null;
            });
            StlBill bill = requireBill(subOrderNo);

            assertThat(bill.getGrossMinor())
                    .as("前提：结算基数必须真的比实付大 500，否则下面那条分辨不出两种实现")
                    .isEqualTo(paid + 500);
            assertThat(bill.getChannelFeeMinor())
                    .as("通道按真正流经它的那笔钱收费")
                    .isEqualTo(paid * 60 / 10000);
            assertThat(bill.getChannelFeeMinor())
                    .as("按 gross 算会多收 —— 这一条就是两种实现的分水岭")
                    .isNotEqualTo(bill.getGrossMinor() * 60 / 10000);
            assertThat(bill.getChannelFeeRate())
                    .as("费率快照：费率会变，历史账不能跟着变")
                    .isEqualTo(60);
            assertThat(bill.getChannelFeeSource()).isEqualTo(StlBill.FEE_STANDARD);
            assertThat(bill.getFeeBearer())
                    .as("只记金额不记承担方，事后答不上「这笔是平台让的还是商家出的」")
                    .isNotBlank();
        } finally {
            dropRate(rate);
        }
    }

    @Test
    @DisplayName("★★★ 没配费率就留空 —— 兜 0 之后，「免手续费」与「没人配过」永远分不开")
    void unconfiguredRateLeavesSourceNull() throws Exception {
        StlBill bill = billOfOnlineOrder("13400288012", "settle-fee-2");

        assertThat(bill.getChannelFeeSource())
                .as("null 才表示「不知道多少」；写成 STANDARD 就是替它认领了一个 0%")
                .isNull();
        assertThat(bill.getChannelFeeMinor())
                .as("金额是建表默认的 0，靠 source 为 null 才知道这个 0 不作数")
                .isZero();
        assertThat(bill.getFeeBearer())
                .as("承担方来自进件档案，与费率配没配无关 —— 它仍然要落")
                .isNotBlank();
    }

    @Test
    @DisplayName("★★ 单笔最低手续费要压得住 —— 漏了它，小额单的手续费系统性偏低且每笔都「算得对」")
    void minFeeFloorApplies() throws Exception {
        var rate = addWechatRate(1, 99L);
        try {
            StlBill bill = billOfOnlineOrder("13400288013", "settle-fee-3");
            assertThat(bill.getChannelFeeMinor())
                    .as("1bp 按率算不足 99 分，该按最低值收")
                    .isEqualTo(99L);
            assertThat(bill.getGrossMinor() * 1 / 10000)
                    .as("前提：按率算必须真的小于 99，否则这条用例什么也没验到")
                    .isLessThan(99L);
        } finally {
            dropRate(rate);
        }
    }

    @Test
    @DisplayName("★★ 线下单不碰手续费四列 —— 钱从没进过通道，谈不上通道手续费")
    void offlineBillHasNoChannelFee() throws Exception {
        var rate = addWechatRate(60, 0L);
        try {
            StlBill bill = billOfOfflineOrder("13400288014", "settle-fee-4");
            assertThat(bill.getChannelFeeMinor()).isZero();
            assertThat(bill.getChannelFeeSource()).isNull();
        } finally {
            dropRate(rate);
        }
    }

    @Test
    @DisplayName("★★★ 佣金基数扣完手续费再抽 —— 按原价抽，每笔多收几分，累积起来对不上账")
    void commissionBaseExcludesChannelFee() throws Exception {
        var rate = addWechatRate(200, 0L);          // 2%，让手续费大到肉眼可辨
        try {
            StlBill bill = billOfOnlineOrder("13400288015", "settle-fee-5");

            assertThat(bill.getChannelFeeMinor())
                    .as("前提：手续费必须真的大于 0，否则下面两条分辨不出基数用了哪个")
                    .isPositive();
            assertThat(bill.getCommissionMinor())
                    .as("基数 = gross - 手续费")
                    .isEqualTo((bill.getGrossMinor() - bill.getChannelFeeMinor()) * SEED_RATE_BP / 10000);
            assertThat(bill.getCommissionMinor())
                    .as("按 gross 原价抽会更多 —— 这一条就是红线 6 改没改的分水岭")
                    .isLessThan(bill.getGrossMinor() * SEED_RATE_BP / 10000);
        } finally {
            dropRate(rate);
        }
    }

    @Test
    @DisplayName("★★★ 商家承担的手续费要从实得里扣 —— 只记不扣，等于账上有一笔谁都不出的费用")
    void merchantBorneFeeIsDeductedFromNet() throws Exception {
        var rate = addWechatRate(200, 0L);
        String payNo = givenApplyment("MERCHANT");
        try {
            StlBill bill = billOfOnlineOrder("13400288016", "settle-fee-6");

            assertThat(bill.getFeeBearer())
                    .as("前提：这一单解析到的承担方确实是商家")
                    .isEqualTo("MERCHANT");
            assertThat(bill.getChannelFeeMinor())
                    .as("前提：手续费必须非 0，否则下一条在「扣与不扣」之间分辨不出来")
                    .isPositive();
            assertThat(bill.getNetMinor())
                    .as("实得 = 基数 - 佣金 - 服务费 - 积分费 - 他自己承担的手续费")
                    .isEqualTo(bill.getGrossMinor() - bill.getCommissionMinor()
                            - bill.getServiceFeeMinor() - bill.getPointsFeeMinor()
                            - bill.getChannelFeeMinor());
        } finally {
            dropApplyment(payNo);
            dropRate(rate);
        }
    }

    /**
     * <b>写这条用例时被建表默认值骗过一次，值得留个记号。</b>
     *
     * <p>{@code stl_bill.fee_bearer} 是 {@code NOT NULL DEFAULT 'MERCHANT'}，
     * 而 MyBatis-Plus 插入时会跳过 null 字段 —— 于是「没解析到承担方」落库之后
     * 长得和「商家承担」一模一样。上一版用例断言「承担方是 MERCHANT」是<b>通过的</b>，
     * 而那时代码一个字都没解析到，实得也根本没扣。
     * 现在解析不到时显式写 {@code UNKNOWN}，两种情况才分得开。
     */
    @Test
    @DisplayName("★★★ 没进件时落 UNKNOWN 且不扣 —— 不知道不等于商家出，猜错就是少付给他钱")
    void unknownBearerIsExplicitAndDeductsNothing() throws Exception {
        var rate = addWechatRate(200, 0L);
        try {
            StlBill bill = billOfOnlineOrder("13400288017", "settle-fee-7");

            assertThat(bill.getFeeBearer())
                    .as("种子商家没有进件档案 —— 落 MERCHANT 就是替他认领了这笔费用")
                    .isEqualTo("UNKNOWN");
            assertThat(bill.getChannelFeeMinor())
                    .as("费用照记：不知道谁出，不代表没这笔")
                    .isPositive();
            assertThat(bill.getNetMinor())
                    .as("承担方未知就不从实得里扣")
                    .isEqualTo(bill.getGrossMinor() - bill.getCommissionMinor()
                            - bill.getServiceFeeMinor() - bill.getPointsFeeMinor());
        } finally {
            dropRate(rate);
        }
    }

    /**
     * 临时给种子商家建一条主体级进件档案。
     *
     * <p>⚠️ <b>用完必须删。</b>它一旦留在库里，此后所有线上单的实得都会少掉一笔手续费，
     * 而别的用例的失败信息里不会有任何一个字提到「承担方」。
     */
    private String givenApplyment(String bearer) {
        String no = "PM-FEE-TEST";
        DataScopeContext.executeWithoutScope(() -> jdbc.update(
                "INSERT INTO mch_payment_merchant (pay_merchant_no, entity_no, store_no, pay_channel,"
                        + " apply_status, fee_bearer, tenant_no, created_at, updated_at, version, deleted)"
                        + " VALUES (?, ?, '', 'WECHAT', 'ACTIVE', ?, 'MAIN', CURRENT_TIMESTAMP,"
                        + " CURRENT_TIMESTAMP, 0, 0)", no, SEED_ENTITY, bearer));
        return no;
    }

    private void dropApplyment(String payMerchantNo) {
        DataScopeContext.executeWithoutScope(() -> jdbc.update(
                "DELETE FROM mch_payment_merchant WHERE pay_merchant_no = ?", payMerchantNo));
    }

    /**
     * ⚠️ <b>费率行必须删干净。</b>{@code sys_pay_channel_rate} 是全局表，
     * 留一行 WECHAT 费率在库里，此后**所有**线上单都会带上手续费 ——
     * 而别的用例的失败信息里不会有任何一个字提到费率。
     */
    private ai.neargo.shop.platform.entity.SysPayChannelRate addWechatRate(int bp, long minFee) {
        var r = new ai.neargo.shop.platform.entity.SysPayChannelRate();
        r.setPayChannel("WECHAT");
        r.setRateBp(bp);
        r.setMinFeeMinor(minFee);
        r.setEffectiveFrom(1L);      // 早于任何一单，取的时候一定命中
        return payChannelRateService.add(r);
    }

    private void dropRate(ai.neargo.shop.platform.entity.SysPayChannelRate r) {
        DataScopeContext.executeWithoutScope(() -> jdbc.update(
                "DELETE FROM sys_pay_channel_rate WHERE rate_no = ?", r.getRateNo()));
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

    @Test
    @DisplayName("★★★ 桩网关推不到「已到账」—— 它模拟的是发指令，不是钱到")
    void stubGatewayNeverConfirms() throws Exception {
        StlBill bill = billOfOnlineOrder("13400288009", "settle-split-1");
        // 自营单不走分账，这里换一张第三方的：直接改经营模式最省事，
        // 而本条要验的是 executeSplit 之后停在哪，与它怎么来的无关
        DataScopeContext.executeWithoutScope(() -> {
            StlBill b = reload(bill.getSettleNo());
            b.setBusinessMode(ai.neargo.shop.spi.user.MerchantQueryPort.MODE_THIRD_PARTY);
            b.setStatus(StlBill.PENDING);
            return billMapper.updateById(b);
        });

        settleService.executeSplit(bill.getSettleNo());

        StlBill after = reload(bill.getSettleNo());
        assertThat(after.getStatus())
                .as("桩返回成功只表示**受理成功**。让它自己推到终态，"
                        + "等于用一个桩把整本账做平 —— 之后再也分不清哪些钱真的到了")
                .isEqualTo(StlBill.SPLIT);
        assertThat(after.getSplitAt()).as("指令发出的时刻要有").isNotNull();
        assertThat(after.getSplitConfirmedAt())
                .as("到账时刻**必须为空** —— 没有任何回执说过钱到了")
                .isNull();
    }

    @Test
    @DisplayName("★★★ 只有回执能进终态，且幂等不改时间戳")
    void confirmSplitIsTheOnlyDoorAndIsIdempotent() throws Exception {
        StlBill bill = billOfOnlineOrder("13400288010", "settle-split-2");
        DataScopeContext.executeWithoutScope(() -> {
            StlBill b = reload(bill.getSettleNo());
            b.setBusinessMode(ai.neargo.shop.spi.user.MerchantQueryPort.MODE_THIRD_PARTY);
            b.setStatus(StlBill.PENDING);
            return billMapper.updateById(b);
        });
        settleService.executeSplit(bill.getSettleNo());

        assertThat(settleService.confirmSplit(bill.getSettleNo(), "CH-001")).isTrue();
        StlBill first = reload(bill.getSettleNo());
        assertThat(first.getStatus()).isEqualTo(StlBill.SPLIT_CONFIRMED);
        assertThat(first.getSplitConfirmedAt()).isNotNull();

        /*
         * 回执会重投。**重复确认不能改时间戳** —— 改晚了会让对账把一条正常单
         * 算成「发出很久才确认」，而那正是分账轴要捞的差异类型（工单第 6 步）。
         */
        assertThat(settleService.confirmSplit(bill.getSettleNo(), "CH-001")).isFalse();
        assertThat(reload(bill.getSettleNo()).getSplitConfirmedAt())
                .isEqualTo(first.getSplitConfirmedAt());
    }

    @Test
    @DisplayName("★★ 没发过指令的单收到确认回执 —— 不迁移状态，也不抛（抛了通道会一直重投）")
    void confirmWithoutInstructionDoesNothing() throws Exception {
        StlBill bill = billOfOfflineOrder("13400288011", "settle-split-3");
        // 线下单是 OFFLINE_SETTLED，从来没发过分账指令
        assertThat(settleService.confirmSplit(bill.getSettleNo(), "CH-STRAY")).isFalse();
        assertThat(reload(bill.getSettleNo()).getStatus()).isEqualTo(StlBill.OFFLINE_SETTLED);
    }

    @Test
    @DisplayName("★★★ 收入四个数加起来 = 全部结算单总额 —— 它们是四种状态，不是四个口袋")
    void incomeSummaryCoversEveryBill() throws Exception {
        // 造齐四种：线下（无需结算）、线上待结算、已发起、已到账
        billOfOfflineOrder("13400288012", "income-offline");
        StlBill pending = billOfOnlineOrder("13400288013", "income-pending");
        StlBill inFlight = billOfOnlineOrder("13400288014", "income-inflight");
        StlBill received = billOfOnlineOrder("13400288015", "income-received");
        for (StlBill b : List.of(inFlight, received)) {
            DataScopeContext.executeWithoutScope(() -> {
                StlBill x = reload(b.getSettleNo());
                x.setBusinessMode(ai.neargo.shop.spi.user.MerchantQueryPort.MODE_THIRD_PARTY);
                x.setStatus(StlBill.PENDING);
                return billMapper.updateById(x);
            });
            settleService.executeSplit(b.getSettleNo());
        }
        settleService.confirmSplit(received.getSettleNo(), "CH-INCOME");

        var sum = settleService.incomeSummary(SEED_ENTITY, List.of());
        long total = DataScopeContext.executeWithoutScope(() ->
                        billMapper.selectList(Wrappers.<StlBill>lambdaQuery()
                                .eq(StlBill::getEntityNo, SEED_ENTITY))).stream()
                .filter(b -> !StlBill.REVERSED.equals(b.getStatus()))
                .mapToLong(b -> b.getNetMinor() == null ? 0L : b.getNetMinor()).sum();

        /*
         * ⚠️ **这一条是这一步的完成判据。** 四个数是同一批单子按状态切开的，
         * 加起来必须等于总额 —— 少一档就意味着有一批钱在总览上凭空消失，
         * 而商家看到的是「我的收入比实际少」，那是最伤信任的一类错。
         */
        assertThat(sum.receivedMinor() + sum.inFlightMinor() + sum.pendingMinor() + sum.offlineMinor())
                .as("四个数是四种状态，不是四个口袋 —— 加起来必须等于全部结算单")
                .isEqualTo(total);

        assertThat(sum.offlineMinor()).as("当面收款那部分他早就拿到了").isPositive();
        assertThat(sum.inFlightMinor()).as("已发起等确认 —— 此前它混在「已到账」里").isPositive();
        assertThat(sum.receivedMinor()).as("只有回执确认过的才算到账").isPositive();
        /*
         * ⚠️ **不断绝对笔数。** 同一个 Spring 上下文里别的用例也会造在途单
         * （M7SettleFlowTest 就会），断 `isEqualTo(1)` 的结果是
         * **单独跑绿、全量跑红**，而报错说的是「期望 1 实际 4」——
         * 与真实原因（别人也造了单）毫不相干。
         *
         * 断「至少有我造的那一笔」才是这条用例真正要证明的东西。
         */
        assertThat(sum.inFlightCount())
                .as("至少要数到我刚造的那一笔")
                .isGreaterThanOrEqualTo(1);
        assertThat(sum.oldestInFlightAt())
                .as("「卡了多久」是商家真正想问的 —— 只给金额他看不出是一笔大的还是很多笔")
                .isNotNull();
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
