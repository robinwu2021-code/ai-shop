package ai.neargo.shop.scenario;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.pay.entity.StlBill;
import ai.neargo.shop.pay.entity.StlPayment;
import ai.neargo.shop.pay.mapper.SettleMappers;
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
 * <b>退款要在资金侧留下记录</b>（S8 · 2026-09-02）。
 *
 * <h2>此前一行都没有</h2>
 * {@code stl_payment} 有五个方向，而生产代码里只有 {@code PAY} 被写过。
 * 退款走的是「退积分 + 回退分账 + 记欠款」三条腿 ——
 * <b>三条腿没有身体</b>：「这笔退款在资金上真的发生过吗」没有地方可以问，
 * 而对账只扫 {@code direction = PAY}，也就扫不到它。
 *
 * <p>售后单上的 {@code refund_payment_no} 字段更直白：<b>只声明、从没被赋值</b>。
 */
@SpringBootTest
@ActiveProfiles("test")
class RefundLeavesLedgerTest {

    @Autowired
    private PaymentLedgerService ledger;
    @Autowired
    private SettleMappers.PaymentMapper paymentMapper;
    @Autowired
    private SettleMappers.BillMapper billMapper;

    private static int seq = 0;

    /** 造一笔已成功的收款 —— 退款要挂在它上面 */
    private String paidOrder() {
        String orderNo = "OD-RF-" + (++seq);
        String out = ledger.open(new SettlePort.PaymentOpen(
                orderNo, "U-RF", null, "TEST", 20_000L));
        ledger.settle(new SettlePort.PaymentSettled(
                out, "TEST", "TX-RF-" + seq, 20_000L, System.currentTimeMillis()));
        return orderNo;
    }

    private List<StlPayment> refundsOf(String orderNo) {
        return DataScopeContext.executeWithoutScope(() -> paymentMapper.selectList(
                Wrappers.<StlPayment>lambdaQuery()
                        .eq(StlPayment::getDirection, StlPayment.REFUND)
                        .eq(StlPayment::getOrderNo, orderNo)
                        .orderByAsc(StlPayment::getId)));
    }

    @Test
    @DisplayName("★★★ 退款落一行 REFUND 流水，挂在原收款上 —— 否则资金侧没人记得这笔退款")
    void refundLeavesARow() {
        String orderNo = paidOrder();

        String no = ledger.refund(orderNo, "SUB-" + orderNo, "AS-" + seq, 5_000L, "七天无理由");

        assertThat(no).as("退款流水号是空的 —— 那售后单上的 refund_payment_no 又会是 null").isNotBlank();
        List<StlPayment> rows = refundsOf(orderNo);
        assertThat(rows).hasSize(1);
        StlPayment r = rows.getFirst();
        assertThat(r.getAmountMinor()).isEqualTo(5_000L);
        assertThat(r.getSubOrderNo()).as("要能追回是哪一笔子单").isEqualTo("SUB-" + orderNo);
        assertThat(r.getAfterSaleNo()).as("要能追回是哪一张售后单").isEqualTo("AS-" + seq);
        assertThat(r.getPayChannel())
                .as("通道要跟着原收款 —— 从哪儿收的就从哪儿退，换通道退等于从另一个账户出钱")
                .isEqualTo("TEST");
        assertThat(r.getOutTradeNo())
                .as("商户单号带 -R 后缀，客服按前缀能把一笔单的收与退一次找全")
                .contains("-R");
    }

    @Test
    @DisplayName("★★★ 落成 PENDING 不是 SUCCESS —— 通道退款是异步的，回执到了才算数")
    void refundStartsPending() {
        String orderNo = paidOrder();
        ledger.refund(orderNo, "SUB-" + orderNo, "AS-P" + seq, 1_000L, "少发");

        assertThat(refundsOf(orderNo).getFirst().getStatus())
                .as("直接写成功的话，通道拒单时账上显示退了而钱没退 —— "
                        + "这种差异只有用户来投诉才会被发现")
                .isEqualTo(StlPayment.PENDING);
    }

    @Test
    @DisplayName("★★★ 同一张售后单重复调只落一行 —— 重试是常态，不幂等就会被当成多退")
    void refundIsIdempotentByAfterSaleNo() {
        String orderNo = paidOrder();
        String as = "AS-I" + seq;

        String a = ledger.refund(orderNo, "SUB-" + orderNo, as, 3_000L, "重试测试");
        String b = ledger.refund(orderNo, "SUB-" + orderNo, as, 3_000L, "重试测试");

        assertThat(b).as("重复调应当返回同一个流水号").isEqualTo(a);
        assertThat(refundsOf(orderNo))
                .as("落了两行 —— 分账回退失败会让退款停在 REFUNDING 等续跑，"
                        + "每重试一次多一行的话，对账会把它们当成多退了几笔")
                .hasSize(1);
    }

    @Test
    @DisplayName("★★ 一笔单退多次，序号往下走 —— 退一件、再退一件是常见的")
    void multipleRefundsGetDistinctNumbers() {
        String orderNo = paidOrder();
        ledger.refund(orderNo, "SUB-" + orderNo, "AS-M1-" + seq, 2_000L, "第一件");
        ledger.refund(orderNo, "SUB-" + orderNo, "AS-M2-" + seq, 3_000L, "第二件");

        List<StlPayment> rows = refundsOf(orderNo);
        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(StlPayment::getOutTradeNo)
                .as("两笔退款的商户单号不能相同 —— 通道要求唯一")
                .doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("★★★ 原收款不存在时返回 null，不落无主的退款流水")
    void noOriginMeansNoRow() {
        String no = ledger.refund("OD-NEVER-PAID-" + (++seq), "SUB-X", "AS-X", 1_000L, "试");

        assertThat(no)
                .as("给一笔没收到钱的单落退款流水 —— 那是真通道一定会拒的，"
                        + "而落下来之后对账会去查一笔通道那边不存在的退款")
                .isNull();
        assertThat(refundsOf("OD-NEVER-PAID-" + seq)).isEmpty();
    }
}
