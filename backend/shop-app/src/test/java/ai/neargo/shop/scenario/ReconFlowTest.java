package ai.neargo.shop.scenario;

import ai.neargo.shop.pay.entity.StlPayment;
import ai.neargo.shop.pay.entity.StlReconDiff;
import ai.neargo.shop.pay.service.ReconService;
import ai.neargo.shop.spi.pay.PayQueryPort;
import ai.neargo.shop.support.port.FakePayQueryPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 对账自查（[对账差异-方案] 第一步）。
 *
 * <p><b>这组用例守的是「什么时候可以关单」</b>。三种通道回答对应三种走向，
 * 而搞错其中一种的代价完全不对称：
 * <ul>
 *   <li>把「已支付」当成没付 → 用户付了钱、单被关掉，只能退款道歉</li>
 *   <li>把「查询失败」当成「通道没这笔」→ 同上，而且是系统性的（通道抖动时成批发生）</li>
 *   <li>把「没付」当成已付 → 给一笔没收到钱的单发货</li>
 * </ul>
 * 所以每一条都单独测，不合并。
 */
@SpringBootTest
@ActiveProfiles("test")
class ReconFlowTest {

    @Autowired
    private ReconService reconService;

    @Autowired
    private FakePayQueryPort fakeQuery;

    @Autowired
    private ai.neargo.shop.pay.mapper.SettleMappers.PaymentMapper paymentMapper;

    @Autowired
    private ai.neargo.shop.pay.mapper.SettleMappers.ReconDiffMapper diffMapper;

    private static int seq = 0;

    @BeforeEach
    void reset() {
        fakeQuery.reset();
    }

    /** 造一笔 25 分钟前发起、至今停在 PENDING 的收款 */
    private StlPayment stalePayment() {
        StlPayment p = new StlPayment();
        p.setPaymentNo("PY-RECON-" + (++seq));
        p.setDirection(StlPayment.PAY);
        p.setStatus(StlPayment.PENDING);
        p.setOrderNo("OD-RECON-" + seq);
        p.setOutTradeNo("OUT-RECON-" + seq);
        p.setPayChannel("WECHAT");
        p.setUserNo("U-RECON");
        p.setAmountMinor(9900L);
        p.setCreatedAt(LocalDateTime.now().minusMinutes(25));
        paymentMapper.insert(p);
        return p;
    }

    private List<StlReconDiff> diffsOf(String paymentNo) {
        return diffMapper.selectList(Wrappers.<StlReconDiff>lambdaQuery()
                .eq(StlReconDiff::getPaymentNo, paymentNo));
    }

    @Test
    @DisplayName("★★ 查询失败绝不关单 —— 通道抖一下就把已付的单成批关掉，是资金事故")
    void queryFailureNeverCloses() {
        StlPayment p = stalePayment();
        fakeQuery.answer(new PayQueryPort.Result(false, false, false, 0, null));

        var r = reconService.scan(System.currentTimeMillis());

        assertThat(fakeQuery.asked()).contains(p.getOutTradeNo());
        assertThat(r.closed()).isZero();
        assertThat(r.deferred()).isPositive();
        // 单还在原地等下一轮，且不落差异 —— 查不通不是差异，是我们没查到
        assertThat(paymentMapper.selectById(p.getId()).getStatus()).isEqualTo(StlPayment.PENDING);
        assertThat(diffsOf(p.getPaymentNo())).isEmpty();
    }

    @Test
    @DisplayName("★ 通道说没有这笔 → 可以安全关单")
    void notFoundClosesOrder() {
        StlPayment p = stalePayment();
        fakeQuery.answer(new PayQueryPort.Result(true, false, false, 0, null));

        var r = reconService.scan(System.currentTimeMillis());

        assertThat(r.closed()).isPositive();
        assertThat(fakeQuery.asked()).contains(p.getOutTradeNo());
    }

    @Test
    @DisplayName("★ 通道有这笔但没付 → 交给关单任务，不算差异")
    void unpaidIsNotADiff() {
        StlPayment p = stalePayment();
        fakeQuery.answer(new PayQueryPort.Result(true, false, true, 0, null));

        var r = reconService.scan(System.currentTimeMillis());

        assertThat(r.closed()).isZero();
        assertThat(diffsOf(p.getPaymentNo())).isEmpty();
    }

    @Test
    @DisplayName("★★ 通道说已付 → 落一条差异，且金额不符时再落一条")
    void paidRecordsDiff() {
        StlPayment p = stalePayment();
        // 通道说 100.00，我方记的是 99.00 —— 两条差异：掉单 + 金额不符
        fakeQuery.answer(new PayQueryPort.Result(true, true, true, 10000L, "WX-TX-1"));

        reconService.scan(System.currentTimeMillis());

        assertThat(diffsOf(p.getPaymentNo()))
                .extracting(StlReconDiff::getDiffType)
                .contains(StlReconDiff.PLATFORM_ONLY, StlReconDiff.AMOUNT_DIFF);
        // 差异要能追回通道流水号，否则运营拿什么去通道后台核对
        assertThat(diffsOf(p.getPaymentNo()))
                .allSatisfy(d -> assertThat(d.getChannelTxnNo()).isEqualTo("WX-TX-1"));
    }

    @Test
    @DisplayName("★ 同一笔在同一账期只落一条 —— 每 10 分钟跑一轮，不去重会堆成几十条")
    void diffIsDedupedPerDay() {
        StlPayment p = stalePayment();
        fakeQuery.answer(new PayQueryPort.Result(true, true, true, 9900L, "WX-TX-2"));

        reconService.scan(System.currentTimeMillis());
        reconService.scan(System.currentTimeMillis());
        reconService.scan(System.currentTimeMillis());

        assertThat(diffsOf(p.getPaymentNo())).hasSize(1);
    }

    @Test
    @DisplayName("★★ 裁决必须写结论 —— 没有结论的「已处理」等于没处理")
    void decideRequiresResolution() {
        StlPayment p = stalePayment();
        fakeQuery.answer(new PayQueryPort.Result(true, true, true, 9900L, "WX-TX-3"));
        reconService.scan(System.currentTimeMillis());
        String diffNo = diffsOf(p.getPaymentNo()).getFirst().getDiffNo();

        assertThat(org.junit.jupiter.api.Assertions.assertThrows(
                ai.neargo.shop.common.BizException.class,
                () -> reconService.decide(diffNo, true, "  ", "OPS1")))
                .isNotNull();

        var vo = reconService.decide(diffNo, true, "通道后台核对后确认是重复通知", "OPS1");
        assertThat(vo.status()).isEqualTo(StlReconDiff.IGNORED);

        // 裁完是终态：再裁一次意味着同一条差异有两个结论
        assertThat(org.junit.jupiter.api.Assertions.assertThrows(
                ai.neargo.shop.common.BizException.class,
                () -> reconService.decide(diffNo, false, "改主意了", "OPS1")))
                .isNotNull();
    }

    @Test
    @DisplayName("★★ 覆盖范围必须说「渠道账单没接」—— 空列表不等于账是平的")
    void coverageTellsTheTruth() {
        var c = reconService.coverage();
        assertThat(c.channelBillConnected()).isFalse();
        // 这句话直接显示给运营，不能是空的
        assertThat(c.note()).contains("渠道账单未接入");
    }
}
