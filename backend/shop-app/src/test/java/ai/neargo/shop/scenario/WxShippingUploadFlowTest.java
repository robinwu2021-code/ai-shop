package ai.neargo.shop.scenario;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.paybridge.WxShippingUploadService;
import ai.neargo.shop.spi.trade.WxShippingPort;
import ai.neargo.shop.trade.entity.TrdShippingUpload;
import ai.neargo.shop.trade.mapper.TradeMappers;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 发货信息录入的上报台账 —— <b>入队与幂等</b>这一半。
 *
 * <p>「上报一次之后落成什么状态」那一半在 {@code WxShippingOutcomeTest}（纯函数）：
 * 为它起一个带假通道的新上下文，会把测试上下文缓存挤掉重建，
 * 而重建会让 H2 的初始化脚本再跑一遍、种子撞主键 —— 实测踩过。
 *
 * <p><b>不报的后果不是「少个功能」，是这笔钱结不出来</b>，
 * 而它在用户端毫无感知、商家几天后才发现。所以这里守的是
 * 「该报的一件不少、报过的不重复报、认不出来的绝不猜」。
 */
@SpringBootTest
@ActiveProfiles("test")
class WxShippingUploadFlowTest {

    @Autowired
    private WxShippingUploadService service;
    @Autowired
    private WxShippingPort channel;
    @Autowired
    private TradeMappers.ShippingUploadMapper mapper;
    @Autowired
    private TradeMappers.SubOrderMapper subOrderMapper;
    @Autowired
    private ai.neargo.shop.trade.service.MerchantOrderService merchantOrderService;
    @Autowired
    private ai.neargo.shop.pay.service.PaymentLedgerService ledger;

    private static int seq = 0;

    private TrdShippingUpload row(String orderNo) {
        return DataScopeContext.executeWithoutScope(() -> mapper.selectOne(
                Wrappers.<TrdShippingUpload>lambdaQuery()
                        .eq(TrdShippingUpload::getOrderNo, orderNo).last("LIMIT 1")));
    }

    @Test
    @DisplayName("★★★ 测试世界里装的是桩，且它明说自己没接通 —— 否则「一直没结到钱」会被查成通道故障")
    void stubSaysItIsNotConnected() {
        assertThat(channel.enabled())
                .as("桩把 enabled 报成 true 的话，运营看不出「这个号根本没开通」")
                .isFalse();
    }

    @Test
    @DisplayName("★★★ 入队只落库不调用 —— 一次网络抖动不该让用户那个动作失败")
    void enqueueOnlyWritesTheLedger() {
        String o = "OD-SHIP-" + (++seq);
        service.enqueue(o, "PY-" + seq, 4);

        TrdShippingUpload r = row(o);
        assertThat(r).isNotNull();
        assertThat(r.getStatus()).isEqualTo(TrdShippingUpload.PENDING);
        assertThat(r.getOutTradeNo()).isEqualTo("PY-" + seq);
        assertThat(r.getLogisticsType()).isEqualTo(4);
        assertThat(r.getAttempts()).isZero();
    }

    @Test
    @DisplayName("★★★ 认不出履约方式时不落库、不上报 —— 绝不兜一个默认值")
    void unknownFulfillmentIsNotGuessed() {
        String o = "OD-SHIP-U" + (++seq);
        service.enqueue(o, "PY-U" + seq, 0);

        assertThat(row(o))
                .as("兜 3（虚拟）的话微信不会拒 —— 报上去但语义是错的，没有任何地方会说一句")
                .isNull();
    }

    @Test
    @DisplayName("★★★ 没有支付单号时不落库 —— 上报靠它定位微信那笔单")
    void missingOutTradeNoIsNotQueued() {
        String o = "OD-SHIP-N" + (++seq);
        service.enqueue(o, "  ", 1);
        assertThat(row(o)).isNull();
    }

    @Test
    @DisplayName("★★ 一笔订单一行；上报成功后重复入队不会把它改回待上报")
    void enqueueIsIdempotentAndDoesNotResurrect() {
        String o = "OD-SHIP-I" + (++seq);
        service.enqueue(o, "PY-I" + seq, 1);
        assertThat(service.upload(row(o), "五常大米 10斤装", "SF7788", "SF", "oPAYER-1")).isTrue();
        assertThat(row(o).getStatus()).isEqualTo(TrdShippingUpload.SUCCESS);
        assertThat(row(o).getUploadedAt()).isNotNull();

        service.enqueue(o, "PY-I" + seq, 1);
        assertThat(row(o).getStatus())
                .as("已成功的被改回待上报 —— 补报任务会一直重复报同一笔")
                .isEqualTo(TrdShippingUpload.SUCCESS);
    }

    @Test
    @DisplayName("★★ 待上报的能被补报任务捞到，已成功的不再出现")
    void pendingListIsWhatTheJobWillPickUp() {
        String queued = "OD-SHIP-Q" + (++seq);
        String done = "OD-SHIP-D" + (++seq);
        service.enqueue(queued, "PY-Q" + seq, 4);
        service.enqueue(done, "PY-D" + seq, 4);
        service.upload(row(done), "商品", null, null, "oX");

        assertThat(service.pending(100)).extracting(TrdShippingUpload::getOrderNo)
                .contains(queued)
                .doesNotContain(done);
    }

    // ------------------------------------------------------------------ 接线

    /**
     * 造一笔「已支付、待发货」的快递单，并把微信收款流水补成付成功。
     *
     * @return 主订单号
     */
    private String aPaidExpressOrder(String entityNo, String subOrderNo) {
        String orderNo = "ORDWS" + System.nanoTime() % 100_000_000L;
        ai.neargo.shop.trade.entity.OrdSubOrder sub =
                new ai.neargo.shop.trade.entity.OrdSubOrder();
        sub.setSubOrderNo(subOrderNo);
        sub.setOrderNo(orderNo);
        sub.setUserNo("UWS" + System.nanoTime() % 100_000_000L);
        sub.setEntityNo(entityNo);
        sub.setStatus(ai.neargo.shop.trade.entity.OrdSubOrder.WAIT_FULFILL);
        sub.setFulfillment(ai.neargo.shop.common.Fulfillments.EXPRESS);
        sub.setPayAmount(1_000L);
        DataScopeContext.executeWithoutScope(() -> subOrderMapper.insert(sub));

        String out = ledger.open(new ai.neargo.shop.spi.settle.SettlePort.PaymentOpen(
                orderNo, sub.getUserNo(), entityNo, "TEST", 1_000L, "柠檬"));
        ledger.settle(new ai.neargo.shop.spi.settle.SettlePort.PaymentSettled(
                out, "TEST", "TX-" + out, 1_000L, System.currentTimeMillis()));
        ledger.recordPayer(out, "oPAYER-WS", "wxAPPID");
        return orderNo;
    }

    @Test
    @DisplayName("★★★ 商家点发货 → 自动进上报队列。**这根线断了不会报错**，只是那些单的钱结不出来")
    void shipEnqueuesTheUpload() {
        String entityNo = "EWS" + (++seq);
        String subNo = "SUBWS" + System.nanoTime() % 100_000_000L;
        String orderNo = aPaidExpressOrder(entityNo, subNo);

        assertThat(row(orderNo))
                .as("发货之前不该有台账行 —— 否则下面那条断言证明不了是 ship() 放进去的")
                .isNull();

        merchantOrderService.ship(entityNo, null, subNo, "SF7788", "SF");

        TrdShippingUpload r = row(orderNo);
        assertThat(r)
                .as("ship() 里那句 notifyShipping 掉了的话，这里就是 null —— "
                        + "而界面、状态机、b 端全都正常，没有任何地方会说一句")
                .isNotNull();
        assertThat(r.getLogisticsType())
                .as("快递要映射成微信的 1；映射错微信不拒，只是语义错")
                .isEqualTo(ai.neargo.shop.common.WxLogisticsTypes.EXPRESS);
        assertThat(r.getStatus()).isEqualTo(TrdShippingUpload.PENDING);
        assertThat(r.getOutTradeNo())
                .as("要拿付成功那一笔的商户单号 —— 拿错了微信查无此单（268485226）")
                .isNotBlank();
    }

    @Test
    @DisplayName("★★★ 发货缺快递公司 / 编码认不得 → 当场拒，不放行到上报（微信要求成对）")
    void shipRejectsWhenCarrierMissingOrUnknown() {
        String entityNo = "EWSC" + (++seq);
        String subNo = "SUBWSC" + System.nanoTime() % 100_000_000L;
        aPaidExpressOrder(entityNo, subNo);

        // 缺快递公司
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> merchantOrderService.ship(entityNo, null, subNo, "SF7788", null));
        // 编码微信不认（YUNDA 不是有效 delivery_id）
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> merchantOrderService.ship(entityNo, null, subNo, "SF7788", "YUNDA"));
    }

    @Test
    @DisplayName("★★★ 没有微信收款流水的单（线下付款）不入队，也不炸 —— 它本来就不需要向微信报")
    void offlinePaidOrderIsSkippedQuietly() {
        String entityNo = "EWSO" + (++seq);
        String subNo = "SUBWSO" + System.nanoTime() % 100_000_000L;
        String orderNo = "ORDWSO" + System.nanoTime() % 100_000_000L;
        ai.neargo.shop.trade.entity.OrdSubOrder sub =
                new ai.neargo.shop.trade.entity.OrdSubOrder();
        sub.setSubOrderNo(subNo);
        sub.setOrderNo(orderNo);
        sub.setUserNo("UWSO" + System.nanoTime() % 100_000_000L);
        sub.setEntityNo(entityNo);
        sub.setStatus(ai.neargo.shop.trade.entity.OrdSubOrder.WAIT_FULFILL);
        sub.setFulfillment(ai.neargo.shop.common.Fulfillments.EXPRESS);
        sub.setPayAmount(1_000L);
        DataScopeContext.executeWithoutScope(() -> subOrderMapper.insert(sub));

        merchantOrderService.ship(entityNo, null, subNo, "SF9900", "SF");

        assertThat(row(orderNo))
                .as("没有微信支付流水还入队的话，补报任务会永远报不出去并占着队列")
                .isNull();
    }
}
