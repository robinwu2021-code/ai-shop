package ai.neargo.shop.scenario;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.Fulfillments;
import ai.neargo.shop.merchant.entity.MchAppointmentSlot;
import ai.neargo.shop.merchant.mapper.MerchantMappers;
import ai.neargo.shop.merchant.service.StoreFulfillmentService;
import ai.neargo.shop.merchant.service.AppointmentSlotService;
import ai.neargo.shop.product.entity.PrdGoods;
import ai.neargo.shop.product.mapper.ProductMappers;
import ai.neargo.shop.spi.user.AppointmentSlotPort;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.mapper.TradeMappers;
import ai.neargo.shop.trade.service.OrderService;
import ai.neargo.shop.user.entity.UsrAddress;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 预约名额：<b>并发下不许超约</b>，停约的档约不上，取消要把名额还回去。
 *
 * <p>此前 {@code ord_sub_order.appointment_at} 收的是买家自己填的**任意时间戳**，
 * 校验只有「非空且不在过去」—— 同一个上门师傅可以被约到十个人手里，
 * 而系统里没有任何地方看得出来，直到当天有九个人白等。
 *
 * <p>占位靠一条<b>带条件的 UPDATE</b>（{@code booked < capacity} 写在 WHERE 里），
 * 与库存锁定同一套手法。
 *
 * <p>⚠️ <b>写这个类时做消融，发现「抢最后一个名额」那条用例分不出两种实现。</b>
 * 因为 {@code BaseEntity} 上有 {@code @Version}，{@code updateById} 自带乐观锁 ——
 * 先查再改的那个版本在这里也能通过：输的那次撞版本号、返回 0 行，
 * 被当成「没抢到」，结果<b>碰巧是对的</b>。
 *
 * <p>真正分得出来的是{@link #concurrentBookingBothSucceedWhenCapacityAllows}：
 * 名额还富余时两个人同时抢，先查再改会让输掉版本号的那个被<b>误判为约满</b>，
 * 而实际上还剩好几个。这才是条件 UPDATE 在这里的价值 ——
 * 它判的是「还有没有名额」，不是「有没有人比我先动过这一行」。
 */
@SpringBootTest
@ActiveProfiles("test")
class AppointmentSlotFlowTest {

    private static final String MERCHANT = "M0001";
    private static final String BUYER = "U0001";
    private static final long HOUR = 3_600_000L;

    @Autowired
    private AppointmentSlotService slotService;
    @Autowired
    private AppointmentSlotPort slotPort;
    @Autowired
    private OrderService orderService;
    @Autowired
    private MerchantMappers.MchStoreMapper storeMapper;
    @Autowired
    private StoreFulfillmentService fulfillmentService;
    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired
    private MerchantMappers.AppointmentSlotMapper slotMapper;
    @Autowired
    private ProductMappers.GoodsMapper goodsMapper;
    @Autowired
    private TradeMappers.SubOrderMapper subOrderMapper;
    @Autowired
    private ai.neargo.shop.user.mapper.UserMappers.AddressMapper addressMapper;

    private String storeNo;
    private String addressId;
    /** G0001 的原始履约集合，@AfterEach 放回去 */
    private String goodsFulfillmentsBackup;

    @BeforeEach
    void setUp() {
        storeNo = DataScopeContext.executeWithoutScope(() ->
                storeMapper.selectOne(Wrappers.<ai.neargo.shop.merchant.entity.MchStore>lambdaQuery()
                        .eq(ai.neargo.shop.merchant.entity.MchStore::getEntityNo, MERCHANT)
                        .orderByDesc(ai.neargo.shop.merchant.entity.MchStore::getIsDefault)
                        .last("LIMIT 1"))).getStoreNo();
        // 商品要支持上门预约，否则先被 FULFILLMENT_NOT_SUPPORTED 拦掉，
        // 被测的那道闸根本不执行 —— 而用例照样是「被拒」
        DataScopeContext.executeWithoutScope(() -> {
            PrdGoods g = goodsMapper.selectOne(Wrappers.<PrdGoods>lambdaQuery()
                    .eq(PrdGoods::getGoodsNo, "G0001").last("LIMIT 1"));
            // 先记下原样 —— 这是全局种子，@AfterEach 要放回去
            if (goodsFulfillmentsBackup == null) {
                goodsFulfillmentsBackup = g.getFulfillments();
            }
            g.setFulfillments("[\"STORE_PICKUP\",\"NEIGHBOR_PICKUP\",\"MERCHANT_DELIVERY\","
                    + "\"EXPRESS\",\"APPOINTMENT\"]");
            return goodsMapper.updateById(g);
        });
        /*
         * 收货地址要**属于买家本人**（requireReceiverWhenShipped 会回查），
         * 种子里的 AD0001 不是 U0001 的 —— 拿它去下单一样被 RECEIVER_REQUIRED 拦掉。
         */
        addressId = "AD-SLOT-" + System.nanoTime();
        UsrAddress a = new UsrAddress();
        a.setAddressId(addressId);
        a.setUserNo(BUYER);
        a.setName("测试收货人");
        a.setPhone("13800000000");
        a.setRegion("广东省深圳市福田区");
        a.setDetail("测试路 1 号");
        a.setIsDefault(false);
        DataScopeContext.executeWithoutScope(() -> addressMapper.insert(a));
    }

    /**
     * ⚠️ <b>必须清干净。</b>「这家店有没有开过时段」是一个全局开关：
     * 留一个 OPEN 的时段在库里，此后所有走 APPOINTMENT 的用例都会被要求带时段号，
     * 而它们不带 —— 于是一批不相干的用例莫名其妙变红，
     * 失败信息里不会有任何一个字提到预约排期。
     */
    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        DataScopeContext.executeWithoutScope(() -> {
            slotMapper.delete(Wrappers.<MchAppointmentSlot>lambdaQuery()
                    .eq(MchAppointmentSlot::getStoreNo, storeNo));
            /*
             * ⚠️ **渠道行必须物理删掉**，而且必须删 —— 本类有一条用例会存渠道
             * （serviceFulfillmentSurvivesChannelConfig，那是它的前提）。
             * 不还原的话，此后所有走快递/邻里自提的用例都会拿到 70013：
             * 集合从空变非空，闸二开始生效，而它们并不知道这件事。
             * OrderReceiverRequiredTest 的 6 条就是这么被我弄红的。
             *
             * 用 SQL 而不是 mapper.delete：MchFulfillmentChannel 带 @TableLogic，
             * 逻辑删只置 deleted=1，而 uk_store_channel 里没有 deleted ——
             * 下一个用例再开同一路会撞唯一键。
             */
            jdbc.update("DELETE FROM mch_fulfillment_channel WHERE entity_no = ?", MERCHANT);
            if (goodsFulfillmentsBackup != null) {
                PrdGoods g = goodsMapper.selectOne(Wrappers.<PrdGoods>lambdaQuery()
                        .eq(PrdGoods::getGoodsNo, "G0001").last("LIMIT 1"));
                if (g != null) {
                    g.setFulfillments(goodsFulfillmentsBackup);
                    goodsMapper.updateById(g);
                }
            }
            return null;
        });
        goodsFulfillmentsBackup = null;
    }

    @Test
    @DisplayName("★★★ 两个买家并发抢最后一个名额 —— 只有一个成功，且 booked 不会超过 capacity")
    void concurrentBookingCannotOversell() throws Exception {
        String slotNo = openSlot(1);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<AppointmentSlotPort.BookOutcome> grab =
                    () -> slotPort.tryBook(slotNo, storeNo).outcome();
            List<Future<AppointmentSlotPort.BookOutcome>> fs =
                    pool.invokeAll(List.of(grab, grab));

            long won = 0;
            for (var f : fs) {
                if (f.get() == AppointmentSlotPort.BookOutcome.BOOKED) {
                    won++;
                }
            }
            assertThat(won)
                    .as("「先查再改」在这里会让两个都成功，而且不会报任何错")
                    .isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(slot(slotNo).getBooked()).isEqualTo(1);
    }

    @Test
    @DisplayName("★★★ 名额还富余时并发抢，【两个都该成功】—— 这条才分得出条件 UPDATE 与先查再改")
    void concurrentBookingBothSucceedWhenCapacityAllows() throws Exception {
        String slotNo = openSlot(2);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<AppointmentSlotPort.BookOutcome> grab =
                    () -> slotPort.tryBook(slotNo, storeNo).outcome();
            long won = pool.invokeAll(List.of(grab, grab)).stream()
                    .map(f -> {
                        try {
                            return f.get();
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    })
                    .filter(o -> o == AppointmentSlotPort.BookOutcome.BOOKED)
                    .count();
            assertThat(won)
                    .as("两个人抢两个名额，两个都该抢到")
                    .isEqualTo(2);
        } finally {
            pool.shutdownNow();
        }
        assertThat(slot(slotNo).getBooked()).isEqualTo(2);
    }

    @Test
    @DisplayName("★★ 约满之后再约报「已约满」，不是「时段不可用」—— 两句话该做的事不一样")
    void fullSlotSaysFull() {
        String slotNo = openSlot(1);
        assertThat(slotPort.tryBook(slotNo, storeNo).booked()).isTrue();

        assertThat(slotPort.tryBook(slotNo, storeNo).outcome())
                .as("满了要让他换个时间；不可用要让他重新挑一个。合成一个码，端上只能说「约不了」")
                .isEqualTo(AppointmentSlotPort.BookOutcome.FULL);
    }

    @Test
    @DisplayName("★★ 停约的时段约不上；但已约进来的单取消时照样还得回名额")
    void closedSlotRejectsButStillReleases() {
        String slotNo = openSlot(2);
        assertThat(slotPort.tryBook(slotNo, storeNo).booked()).isTrue();

        slotService.close(MERCHANT, slotNo);

        assertThat(slotPort.tryBook(slotNo, storeNo).outcome())
                .isEqualTo(AppointmentSlotPort.BookOutcome.UNAVAILABLE);
        assertThat(slot(slotNo).getBooked())
                .as("停约的语义是「别再往里放人」，不是「把约上的赶走」")
                .isEqualTo(1);

        slotPort.release(slotNo);
        assertThat(slot(slotNo).getBooked())
                .as("停掉的时段照样要能还名额，否则那几个已约单一取消，数字就永远对不上")
                .isZero();
    }

    @Test
    @DisplayName("★★★ 别家店的时段号占不到 —— 不比对归属就能占别人的名额")
    void slotOfAnotherStoreCannotBeBooked() {
        String slotNo = openSlot(1);

        assertThat(slotPort.tryBook(slotNo, "ST-SOMEONE-ELSE").outcome())
                .as("那家店的师傅那天根本不知道有这一单")
                .isEqualTo(AppointmentSlotPort.BookOutcome.UNAVAILABLE);
        assertThat(slot(slotNo).getBooked()).isZero();
    }

    @Test
    @DisplayName("★★ 重复释放减不成负数 —— 少了这道闸，这个时段能卖出比 capacity 更多的单")
    void doubleReleaseCannotGoNegative() {
        String slotNo = openSlot(1);
        assertThat(slotPort.tryBook(slotNo, storeNo).booked()).isTrue();

        slotPort.release(slotNo);
        slotPort.release(slotNo);

        assertThat(slot(slotNo).getBooked()).isZero();
    }

    @Test
    @DisplayName("★★★ 开了时段就必须挑一个 —— 光传时间戳会被拒")
    void slotIsRequiredOnceStoreHasSlots() {
        openSlot(1);
        asBuyer();

        assertThatThrownBy(() -> orderService.create(appointmentOrder(null), key("noslot")))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.APPOINTMENT_SLOT_UNAVAILABLE);
    }

    @Test
    @DisplayName("★★★ 没开时段的商家照旧按老路走 —— 否则这批代码一上线，他们一单都接不了")
    void storeWithoutSlotsKeepsOldPath() {
        asBuyer();
        // 库里一个时段都没有（@AfterEach 清过），传时间戳应当不会因为预约这一层被拒
        try {
            orderService.create(appointmentOrder(null), key("legacy"));
        } catch (BizException e) {
            assertThat(e.errorCode())
                    .as("兼容期的约定：一个时段都没开 = 还没迁过来，按旧口径放行")
                    .isNotIn(ErrorCode.APPOINTMENT_SLOT_UNAVAILABLE, ErrorCode.APPOINTMENT_SLOT_FULL);
        }
    }

    @Test
    @DisplayName("★★★ 下单占位、取消释放 —— 走完整条链路，而不是只调 Port")
    void orderBooksAndCancelReleases() {
        String slotNo = openSlot(1);
        asBuyer();

        var vo = orderService.create(appointmentOrder(slotNo), key("book"));
        assertThat(slot(slotNo).getBooked()).isEqualTo(1);

        OrdSubOrder sub = DataScopeContext.executeWithoutScope(() ->
                subOrderMapper.selectOne(Wrappers.<OrdSubOrder>lambdaQuery()
                        .eq(OrdSubOrder::getOrderNo, vo.orderNo()).last("LIMIT 1")));
        assertThat(sub.getAppointmentSlotNo()).isEqualTo(slotNo);
        assertThat(sub.getAppointmentAt())
                .as("appointment_at 要由时段推出，不能信端上传的时间戳 —— "
                        + "否则名额扣在 9 点那一格，而商家的待服务列表按 15 点排")
                .isEqualTo(slot(slotNo).getStartAt());

        orderService.cancel(vo.orderNo(), "测试取消");
        assertThat(slot(slotNo).getBooked())
                .as("名额不还的话，这个档在没人约的情况下永远显示已满")
                .isZero();
    }

    @Test
    @DisplayName("★★★ 取消被重放也只还一次 —— 超时关闭与用户取消可能同时到达")
    void cancelIsIdempotentOnSlot() {
        String slotNo = openSlot(2);
        asBuyer();
        var vo = orderService.create(appointmentOrder(slotNo), key("idem"));
        assertThat(slot(slotNo).getBooked()).isEqualTo(1);

        orderService.cancel(vo.orderNo(), "第一次");
        try {
            orderService.cancel(vo.orderNo(), "重放");
        } catch (BizException ignored) {
            // 状态机可能拒掉第二次取消，这正常 —— 本条要验的是「哪怕它走到了释放，也只还一次」
        }
        assertThat(slot(slotNo).getBooked())
                .as("还两次就会减成负数，此后这个时段能卖出比 capacity 更多的单")
                .isZero();
    }

    @Test
    @DisplayName("★★★ 门店配过送货方式之后，上门预约仍要卖得出去 —— 这是条线上真缺陷")
    void serviceFulfillmentSurvivesChannelConfig() {
        /*
         * mch_fulfillment_channel 只覆盖四条**实体配送**线，服务类（到店核销 / 上门预约）
         * 永远不会有行。而下单那道闸的规则是「集合非空就要求命中」——
         * 于是商家只要保存过一次送货方式配置，集合不再为空，
         * **他的服务类商品从此一单也卖不出去**，而买家看到的是
         * 「所选商品不支持该配送方式」，与真实原因毫无关系。
         *
         * 这条用例把「配过渠道」这个前提摆在明面上：它就是触发条件。
         */
        fulfillmentService.save(MERCHANT, storeNo, List.of(
                new StoreFulfillmentService.ChannelCmd(
                        Fulfillments.MERCHANT_DELIVERY, true, null, null, null, null)));
        asBuyer();

        try {
            orderService.create(appointmentOrder(null), key("svc"));
        } catch (BizException e) {
            assertThat(e.errorCode())
                    .as("服务类不在渠道表里 ≠ 这家店不提供服务 —— 那张表压根不表达它")
                    .isNotEqualTo(ErrorCode.FULFILLMENT_NOT_SUPPORTED);
        }
    }

    // ── helpers ──────────────────────────────────────────────

    private String openSlot(int capacity) {
        long start = System.currentTimeMillis() + 24 * HOUR;
        return slotService.open(MERCHANT, storeNo, start, start + HOUR, capacity).slotNo();
    }

    private MchAppointmentSlot slot(String slotNo) {
        return DataScopeContext.executeWithoutScope(() ->
                slotMapper.selectOne(Wrappers.<MchAppointmentSlot>lambdaQuery()
                        .eq(MchAppointmentSlot::getSlotNo, slotNo).last("LIMIT 1")));
    }

    /**
     * <b>上门预约要带收货地址</b>：师傅得知道上门去哪儿，所以 APPOINTMENT 和
     * 快递、商家自送归在同一组，`requireReceiverWhenShipped` 在预约那道闸<b>之前</b>。
     *
     * <p>第一版没带 addressId，三条用例全被 RECEIVER_REQUIRED 拦在门外 ——
     * 其中一条还断言了「应当被拒」，<b>它是绿的，而被测的闸根本没执行</b>。
     * 这正是本仓库里最常见的假绿形态：串起来的闸门里，前一道先拒。
     */
    private OrderService.CreateOrderCommand appointmentOrder(String slotNo) {
        return new OrderService.CreateOrderCommand(
                List.of(new OrderService.CreateOrderCommand.Item("G0001", "SK0001", 1)),
                Fulfillments.APPOINTMENT, null, addressId, null, 0L, null,
                System.currentTimeMillis() + 48 * HOUR, null, null, slotNo);
    }

    private String key(String suffix) {
        return "test-slot-" + suffix + "-" + System.nanoTime();
    }

    private void asBuyer() {
        var u = new ai.neargo.shop.auth.LoginUser(
                ai.neargo.shop.auth.Realm.CONSUMER, ai.neargo.auth.store.SubjectKind.USR, BUYER, "测试买家",
                List.of(), List.of(), null, null);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(u, null, List.of()));
    }
}
