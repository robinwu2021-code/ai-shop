package ai.neargo.shop.scenario;

import ai.neargo.shop.pay.entity.StlPayment;
import ai.neargo.shop.pay.service.PaymentLedgerService;
import ai.neargo.shop.spi.settle.SettlePort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 商户单号（out_trade_no）的生成规则。
 *
 * <p><b>这组守的是「一笔订单能不能付第二次」</b>。通道要求商户订单号唯一，
 * 且<b>关闭过的号不能复用</b> —— 所以「订单号 == 商户单号且永不变」这个看起来
 * 最自然的做法，会让任何一笔支付失败的订单再也下不了单，
 * 而症状是「点了没反应」：订单状态正常、日志无异常、通道那边什么都没收到。
 *
 * <p>另一半同样要守：<b>收银台点两次不能变成两笔</b>。
 * 多出来的那笔停在 PENDING，对账会把它当掉单查一遍通道。
 */
@SpringBootTest
@ActiveProfiles("test")
class PayRetryOutTradeNoTest {

    @Autowired
    private PaymentLedgerService ledger;

    @Autowired
    private ai.neargo.shop.pay.mapper.SettleMappers.PaymentMapper paymentMapper;

    private static int seq = 0;

    private SettlePort.PaymentOpen cmd(String orderNo) {
        return new SettlePort.PaymentOpen(orderNo, "U-RETRY", null, "STUB", 8800L);
    }

    private List<StlPayment> paymentsOf(String orderNo) {
        return paymentMapper.selectList(Wrappers.<StlPayment>lambdaQuery()
                .eq(StlPayment::getDirection, StlPayment.PAY)
                .eq(StlPayment::getOrderNo, orderNo)
                .orderByAsc(StlPayment::getId));
    }

    @Test
    @DisplayName("★ 第一次发起：商户单号就是订单号 —— 客服按订单号能直接在通道后台找到")
    void firstAttemptUsesOrderNo() {
        String orderNo = "OD-RETRY-" + (++seq);

        assertThat(ledger.open(cmd(orderNo))).isEqualTo(orderNo);
    }

    @Test
    @DisplayName("★★ 反复点「去支付」复用同一笔 —— 多一行就多一笔要去通道查的「掉单」")
    void repeatedOpenReusesTheSamePayment() {
        String orderNo = "OD-RETRY-" + (++seq);

        String first = ledger.open(cmd(orderNo));
        String second = ledger.open(cmd(orderNo));

        assertThat(second).isEqualTo(first);
        assertThat(paymentsOf(orderNo)).hasSize(1);
    }

    @Test
    @DisplayName("★★ 前一笔关掉之后重试，必须换新号 —— 复用关掉的号会被通道直接拒")
    void retryAfterCloseGetsANewNumber() {
        String orderNo = "OD-RETRY-" + (++seq);
        String first = ledger.open(cmd(orderNo));

        // 关掉第一笔（超时关单 / 用户放弃）
        StlPayment close = new StlPayment();
        close.setId(paymentsOf(orderNo).getFirst().getId());
        close.setStatus(StlPayment.CLOSED);
        paymentMapper.updateById(close);

        String retry = ledger.open(cmd(orderNo));

        assertThat(retry).isNotEqualTo(first);
        // 后缀而不是完全独立的流水号：报障时用户报的仍是自己看得到的订单号，
        // 客服按前缀就能把这几次尝试一次找全
        assertThat(retry).isEqualTo(orderNo + "-2");
        assertThat(paymentsOf(orderNo)).hasSize(2);

        // 再关一次再来一次：序号继续往下走，不会回到 -2 撞上已经用过的号
        StlPayment close2 = new StlPayment();
        close2.setId(paymentsOf(orderNo).get(1).getId());
        close2.setStatus(StlPayment.CLOSED);
        paymentMapper.updateById(close2);

        assertThat(ledger.open(cmd(orderNo))).isEqualTo(orderNo + "-3");
    }

    @Test
    @DisplayName("★★ 重试单的回调认领到的是重试那一笔，不是第一笔")
    void callbackClaimsTheRetryAttempt() {
        String orderNo = "OD-RETRY-" + (++seq);
        ledger.open(cmd(orderNo));
        StlPayment firstRow = paymentsOf(orderNo).getFirst();
        StlPayment close = new StlPayment();
        close.setId(firstRow.getId());
        close.setStatus(StlPayment.CLOSED);
        paymentMapper.updateById(close);
        String retry = ledger.open(cmd(orderNo));

        String claimed = ledger.settle(new SettlePort.PaymentSettled(
                retry, "STUB", "TX-RETRY-" + seq, 8800L, System.currentTimeMillis()));

        // 回调必须能说出「这是哪个订单」—— 带后缀的号直接拿去查订单会查不到
        assertThat(claimed).isEqualTo(orderNo);

        List<StlPayment> rows = paymentsOf(orderNo);
        assertThat(rows.get(0).getStatus()).isEqualTo(StlPayment.CLOSED);   // 关掉的那笔不动
        assertThat(rows.get(1).getStatus()).isEqualTo(StlPayment.SUCCESS);
        assertThat(rows.get(1).getTradeNo()).isEqualTo("TX-RETRY-" + seq);
    }
}
