package ai.neargo.shop.scenario;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.PayChannels;
import ai.neargo.shop.pay.entity.StlPayment;
import ai.neargo.shop.pay.mapper.SettleMappers;
import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.mapper.TradeMappers;
import ai.neargo.shop.trade.service.OrderService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 应付 0 元的订单 —— **下得出来，就要付得掉**。
 *
 * <h2>它是从线上捞出来的，不是推演</h2>
 * 2026-09-20 17:40 真实下单：3 个柠檬 ¥0.30，活动「无门槛立减 ¥10」把优惠
 * 算成 ¥0.30，应付 0。两笔支付流水都是 {@code CLOSED}，原因写着
 * <b>「金额必须大于 0」</b> —— 订单建好了、却永远付不掉，用户什么也做不了。
 *
 * <p>优惠、券、积分任何一种都能把应付打到 0，所以这不是某个活动配错了，
 * 是这条链本来就缺一个出口。详见 TDD-零元订单支付。
 */
@SpringBootTest
@ActiveProfiles("test")
class ZeroAmountPayFlowTest {

    @Autowired
    private OrderService orderService;
    @Autowired
    private TradeMappers.OrderMapper orderMapper;
    @Autowired
    private TradeMappers.SubOrderMapper subOrderMapper;
    @Autowired
    private SettleMappers.PaymentMapper paymentMapper;

    private static int seq = 0;

    @org.junit.jupiter.api.AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    /** 以这个买家登录 —— {@code pay()} 会校验订单归属当前用户 */
    private void asBuyer(String userNo) {
        var u = new ai.neargo.shop.auth.LoginUser(
                ai.neargo.shop.auth.Realm.CONSUMER, ai.neargo.auth.store.SubjectKind.USR,
                userNo, "零元测试买家", List.of(), List.of(), null, null);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(u, null, List.of()));
    }

    /** 造一笔待支付订单并以其买家登录。{@code payable} 是应付（分） */
    private String order(long payable) {
        String orderNo = "OD-ZERO-" + (++seq) + "-" + System.nanoTime() % 1_000_000L;
        String buyer = "U-ZERO-" + seq;
        DataScopeContext.executeWithoutScope(() -> {
            OrdOrder o = new OrdOrder();
            o.setOrderNo(orderNo);
            o.setUserNo(buyer);
            o.setStatus(OrdOrder.WAIT_PAY);
            o.setPayAmount(payable);
            orderMapper.insert(o);
            OrdSubOrder sub = new OrdSubOrder();
            sub.setSubOrderNo("SUB-" + orderNo);
            sub.setOrderNo(orderNo);
            sub.setUserNo(o.getUserNo());
            sub.setEntityNo("E-ZERO-" + seq);
            sub.setStatus(OrdOrder.WAIT_PAY);
            sub.setPayAmount(payable);
            sub.setFulfillment(ai.neargo.shop.common.Fulfillments.EXPRESS);
            subOrderMapper.insert(sub);
            return null;
        });
        asBuyer(buyer);
        return orderNo;
    }

    private OrdOrder reload(String orderNo) {
        return DataScopeContext.executeWithoutScope(() -> orderMapper.selectOne(
                Wrappers.<OrdOrder>lambdaQuery().eq(OrdOrder::getOrderNo, orderNo).last("LIMIT 1")));
    }

    private StlPayment paymentOf(String orderNo) {
        return DataScopeContext.executeWithoutScope(() -> paymentMapper.selectOne(
                Wrappers.<StlPayment>lambdaQuery()
                        .eq(StlPayment::getOrderNo, orderNo)
                        .orderByDesc(StlPayment::getId).last("LIMIT 1")));
    }

    @Test
    @DisplayName("★★★ 应付 0 元：发起支付后订单直接是已支付 —— 此前它永远停在待支付")
    void zeroAmountOrderIsPaidImmediately() {
        String orderNo = order(0L);

        OrderService.PayResult r = orderService.pay(orderNo, null);

        assertThat(r.settled())
                .as("端上靠它决定不唤起收银台；false 的话端上会拿空参数去唤起，通道回参数错")
                .isTrue();
        assertThat(r.payParams())
                .as("0 元没有收银台参数可给")
                .isEmpty();
        assertThat(reload(orderNo).getStatus())
                .as("没推成已支付的话，用户看到的还是「下单成功但付不了」——"
                        + " 线上卡住过一笔（SO202609201740370006341）")
                .isEqualTo(OrdOrder.PAID);
    }

    @Test
    @DisplayName("★★★ 0 元也要留一笔成功的收款流水 —— 绕开支付域的话对账轴看不见这笔单")
    void zeroAmountStillLeavesASuccessfulLedgerRow() {
        String orderNo = order(0L);

        orderService.pay(orderNo, null);

        StlPayment p = paymentOf(orderNo);
        assertThat(p)
                .as("订单说付了、而支付域没有这笔钱 —— 这个方向没有任何东西能发现")
                .isNotNull();
        assertThat(p.getPayChannel()).isEqualTo(PayChannels.FREE);
        assertThat(p.getStatus())
                .as("停在 PENDING 的话，对账轴会每轮回查一次、每轮查不到")
                .isEqualTo(StlPayment.SUCCESS);
        assertThat(p.getAmountMinor()).isZero();
    }

    @Test
    @DisplayName("★★★ 应付 > 0 的单**不许**走免支付 —— 这条防的是「把所有单都变成免费的」")
    void nonZeroOrderMustNotGoFree() {
        String orderNo = order(9_900L);

        OrderService.PayResult r = orderService.pay(orderNo, "TEST");

        assertThat(r.payChannel())
                .as("走成 FREE 就是白送 ¥99")
                .isNotEqualTo(PayChannels.FREE);
        assertThat(r.settled())
                .as("settled=true 会让端上跳过收银台 —— 用户一分没付，订单却成了已支付")
                .isFalse();
        assertThat(reload(orderNo).getStatus())
                .as("付款前不该被推成已支付")
                .isEqualTo(OrdOrder.WAIT_PAY);
    }

    @Test
    @DisplayName("★★ 连点两下：第二次被状态闸拒掉，订单不会被推乱、也不多一笔流水")
    void payingTwiceIsRejectedBySateGuard() {
        String orderNo = order(0L);

        orderService.pay(orderNo, null);
        Long firstPaymentId = paymentOf(orderNo).getId();

        /*
         * **第二次会抛，这是对的。**
         *
         * `pay()` 开头就挡住非 WAIT_PAY 的单（OrderServiceImpl:1150）——
         * 这一道在免支付接进来之前就有，而它恰好把「0 元单被连点两下」也挡住了：
         * 第一次已经把订单推成 PAID，第二次进不来。
         *
         * 写 TDD 时我以为要靠 markPaid 的幂等兜底（§4.3），实际上轮不到它。
         * 断言这个真实语义，而不是断言我原先的设想 —— 后者会把一道有效的闸
         * 记成「可有可无」，下一个人删掉它时不会有任何东西变红。
         */
        assertThat(org.junit.jupiter.api.Assertions.assertThrows(
                        ai.neargo.shop.common.BizException.class,
                        () -> orderService.pay(orderNo, null)))
                .as("已支付的单再发起支付应当被拒")
                .isNotNull();

        assertThat(reload(orderNo).getStatus()).isEqualTo(OrdOrder.PAID);
        assertThat(paymentOf(orderNo).getId())
                .as("不该多出第二笔 0 元流水")
                .isEqualTo(firstPaymentId);
    }
}
