package ai.neargo.shop.scenario;

import ai.neargo.shop.pay.channel.PayGateway;
import ai.neargo.shop.pay.channel.TestPayGateway;
import ai.neargo.shop.pay.entity.StlPayment;
import ai.neargo.shop.pay.mapper.SettleMappers;
import ai.neargo.shop.spi.settle.SettlePort;
import ai.neargo.common.data.scope.DataScopeContext;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>发起支付真的走了网关</b>（S4 · 2026-09-02）。
 *
 * <h2>为什么现有的场景测试盖不住这件事</h2>
 * 那些用例走「下单 → 支付 → 回调 → 结算单」，断言的是<b>结果</b>：
 * 订单变没变成已支付、结算单生成没有。
 * 而支付参数从哪来 —— 网关给的，还是代码里编的一组假值 —— <b>它们看不出区别</b>。
 *
 * <p>实测：把 {@code initPayment} 里调网关那一行换成一组写死的假参数，
 * 32 个场景用例<b>全绿</b>。S4 之前那三年的「支付链路有测试」就是这个成色。
 *
 * <p>所以这一组直接对着<b>通道那一侧</b>断言：通道有没有收到这笔单、
 * 金额对不对、下单失败时我方流水有没有被关掉。
 */
@SpringBootTest
@ActiveProfiles("test")
class PayGoesThroughGatewayTest {

    @Autowired
    private SettlePort settlePort;
    @Autowired
    private TestPayGateway testGateway;
    @Autowired
    private SettleMappers.PaymentMapper paymentMapper;
    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    private static int seq = 0;

    private StlPayment paymentOf(String orderNo) {
        return DataScopeContext.executeWithoutScope(() -> paymentMapper.selectOne(
                Wrappers.<StlPayment>lambdaQuery()
                        .eq(StlPayment::getOrderNo, orderNo)
                        .orderByDesc(StlPayment::getId).last("LIMIT 1")));
    }


    @Test
    @DisplayName("★★★ 发起支付要记下付款人 —— 这两列从建表起就没被写过，客诉时要的正是它")
    void payerIsRecordedOnTheLedgerRow() {
        String orderNo = "OD-PAYER-" + (++seq);
        String userNo = "U-PAYER-" + seq;
        String openid = "oPROBE-" + seq;
        // 这个用户在小程序里登录过 —— 没有这一行，取 openid 拿到的是空，
        // 而「空」与「没写」在库里长得一模一样
        jdbc.update("INSERT INTO usr_identity (user_no, identity_type, identity_value, channel,"
                        + " tenant_no, created_at, updated_at, deleted)"
                        + " VALUES (?,?,?,?,?,?,?,0)",
                userNo, "WX_OPENID_MP", openid, "MP", "MAIN",
                java.time.LocalDateTime.now(), java.time.LocalDateTime.now());

        var r = settlePort.initPayment(new SettlePort.PaymentOpen(
                orderNo, userNo, null, "TEST", 1_000L));
        assertThat(r.success()).isTrue();

        assertThat(paymentOf(orderNo).getPayerOpenid())
                .as("payer_openid 还是 null —— 用户报「我付了钱」时，客服手上只有订单号，"
                        + "而要去微信商户平台按付款人对上那一笔靠的就是这一列")
                .isEqualTo(openid);
    }
    @Test
    @DisplayName("★★★ 发起支付后，通道那边真的有这笔单 —— 编一组假参数返回也能让场景测试全绿")
    void gatewayActuallyReceivesTheOrder() {
        String orderNo = "OD-GW-" + (++seq);

        var r = settlePort.initPayment(new SettlePort.PaymentOpen(
                orderNo, "U-GW", null, "TEST", 8_800L));

        assertThat(r.success()).as("下单失败了？TEST 通道总是装配的").isTrue();

        // ① 通道那一侧要有这笔 —— 这是「真的走了网关」的唯一证据
        PayGateway.QueryResult q = testGateway.query(r.outTradeNo());
        assertThat(q.found())
                .as("通道那边没有这笔单 —— 说明支付参数是代码编的，没有真的下单。"
                        + "而所有走「下单→支付→回调」的场景用例在这种情况下依然全绿")
                .isTrue();
        assertThat(q.amountMinor())
                .as("通道记的金额与我方不一致 —— 对账时会报「金额不符」")
                .isEqualTo(8_800L);
        assertThat(q.paid()).as("还没回调就已支付？那这条链是假的").isFalse();

        // ② 端上拿到的参数要来自通道
        assertThat(r.payParams()).containsKey("prepayId");
        assertThat(r.payParams().get("outTradeNo")).isEqualTo(r.outTradeNo());
        assertThat(r.payParams())
                .as("测试通道要让端上认得出自己 —— 不能渲染成和真收银台一模一样")
                .containsEntry("testChannel", "true");

        // ③ 我方流水停在未终态，等回调
        assertThat(paymentOf(orderNo).getStatus()).isEqualTo(StlPayment.PENDING);
    }

    @Test
    @DisplayName("★★★ 通道没接入时，刚落的流水要被关掉 —— 留着的话对账会永远回查一笔不存在的单")
    void unreachableChannelClosesTheLedgerRow() {
        String orderNo = "OD-GW-" + (++seq);

        var r = settlePort.initPayment(new SettlePort.PaymentOpen(
                orderNo, "U-GW", null, "NO_SUCH_CHANNEL", 5_000L));

        assertThat(r.success()).isFalse();
        assertThat(r.message()).contains("未接入");

        StlPayment p = paymentOf(orderNo);
        assertThat(p).as("流水该落还是要落 —— 没有起点行的话「用户点过支付」这件事无迹可寻").isNotNull();
        assertThat(p.getStatus())
                .as("停在 PENDING 的话，对账轴每轮都会去回查一笔通道那边压根不存在的单 —— "
                        + "而「查询失败绝不关单」那条规则会让它永远留在那里")
                .isEqualTo(StlPayment.CLOSED);
        assertThat(p.getErrMsg()).isNotBlank();
    }

    @Test
    @DisplayName("★★ 通道拒单时同样要关掉流水 —— 0 元单是真通道一定会拒的那种")
    void gatewayRejectionClosesTheLedgerRow() {
        String orderNo = "OD-GW-" + (++seq);

        var r = settlePort.initPayment(new SettlePort.PaymentOpen(
                orderNo, "U-GW", null, "TEST", 0L));

        assertThat(r.success()).as("0 元单下单成功了？真通道一定会拒").isFalse();
        assertThat(paymentOf(orderNo).getStatus()).isEqualTo(StlPayment.CLOSED);
        // 通道那边不该有这笔
        assertThat(testGateway.query(r.outTradeNo()).found()).isFalse();
    }
}
