package ai.neargo.shop.scenario;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.pay.SettleService;
import ai.neargo.shop.pay.entity.PtsUserAccount;
import ai.neargo.shop.pay.entity.PtsUserLedger;
import ai.neargo.shop.pay.entity.StlBill;
import ai.neargo.shop.pay.mapper.SettleMappers.BillMapper;
import ai.neargo.shop.pay.mapper.SettleMappers.PointsAccountMapper;
import ai.neargo.shop.pay.mapper.SettleMappers.PointsLedgerMapper;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 兑付**接线**：两条资金路径各自的真实入口都要真的触发确认。
 *
 * <p><b>为什么单独一个类</b>：{@code PointsConfirmFlowTest} 测的是
 * {@code confirmDeduction} 这个方法本身对不对；本类测的是
 * <b>有没有人调它</b>。这是两件事，而本仓反复栽在后一件上 ——
 * 「闸门写好了但数据源没接」本轮已经出现五次，共同点是**方法是对的、没人调**，
 * 全程不报错。
 *
 * <p>两条路径的入口<b>不是同一个</b>，这是最容易漏的地方：
 * <ul>
 *   <li>直连 → {@code executeSplit} 分账成功后</li>
 *   <li>归集 → {@code markPaid}。<b>自营单根本不走分账</b>，
 *       只挂在分账上的话，归集路径的 USE 永远停在 PENDING</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("兑付接线：两条路径的入口都要真的调到")
class PointsConfirmWiringFlowTest {

    @Autowired
    private SettleService settleService;

    @Autowired
    private BillMapper billMapper;

    @Autowired
    private PointsAccountMapper accountMapper;

    @Autowired
    private PointsLedgerMapper ledgerMapper;

    @Test
    @DisplayName("★★★ 直连：分账成功 → 该子单的 USE 转 CONFIRMED")
    void directConfirmsOnSplit() {
        StlBill bill = aBill(MerchantQueryPort.FUNDS_DIRECT, StlBill.PENDING);
        aPendingUse(bill.getSubOrderNo());

        settleService.executeSplit(bill.getSettleNo());

        assertThat(reload(bill.getSettleNo()).getStatus()).isEqualTo(StlBill.SPLIT);
        assertThat(useStatus(bill.getSubOrderNo())).isEqualTo("CONFIRMED");
    }

    @Test
    @DisplayName("★★★ 归集：账单登记已付 → USE 转 CONFIRMED（这条走的不是分账）")
    void aggregatedConfirmsOnMarkPaid() {
        StlBill bill = aBill(MerchantQueryPort.FUNDS_AGGREGATED, StlBill.CONFIRMED);
        // 票到付款：无票供应商要显式标记，否则 markPaid 会被 INVOICE_REQUIRED 挡下
        bill.setInvoiceStatus(StlBill.INV_NONE);
        DataScopeContext.executeWithoutScope(() -> billMapper.updateById(bill));
        aPendingUse(bill.getSubOrderNo());

        settleService.markPaid(bill.getSettleNo(), "PAY-REF-001", "OPS001");

        assertThat(useStatus(bill.getSubOrderNo())).isEqualTo("CONFIRMED");
    }

    @Test
    @DisplayName("★★ 分账没成功就不确认 —— 钱没动，不能记成已付给商家")
    void noConfirmWhenSplitNotDone() {
        StlBill bill = aBill(MerchantQueryPort.FUNDS_DIRECT, StlBill.PENDING);
        // 归集 + 有补差 → executeSplit 断言冲突转 CONFLICT/MANUAL，不会走到 SPLIT
        bill.setFundsMode(MerchantQueryPort.FUNDS_AGGREGATED);
        bill.setSubsidyMinor(300L);
        DataScopeContext.executeWithoutScope(() -> billMapper.updateById(bill));
        aPendingUse(bill.getSubOrderNo());

        try {
            settleService.executeSplit(bill.getSettleNo());
        } catch (RuntimeException ignored) {
            // 断言失败会抛 —— 这里关心的是「抛没抛」之外的事：分账没成立时不许确认
        }

        assertThat(useStatus(bill.getSubOrderNo()))
                .as("分账没成立却确认了兑付 = 平台记了一笔没发生的付款")
                .isEqualTo("PENDING");
    }

    // ---------------------------------------------------------------- fixtures

    private StlBill aBill(String fundsMode, String status) {
        StlBill b = new StlBill();
        b.setSettleNo(BizKey.next(BizKey.SETTLE_BILL));
        b.setSubOrderNo("SUBCW" + System.nanoTime() % 100_000_000L);
        b.setOrderNo("ORDCW" + System.nanoTime() % 100_000_000L);
        b.setEntityNo("M0001");
        b.setStoreNo("ST001");
        b.setPayMerchantNo("PM-TEST");
        b.setGrossMinor(11_000L);
        b.setCommissionMinor(550L);
        b.setServiceFeeMinor(0L);
        b.setNetMinor(10_450L);
        b.setCommissionRate(500);
        b.setFundsMode(fundsMode);
        b.setPayChannel("WECHAT");
        b.setSubsidyMinor(0L);
        b.setStatus(status);
        b.setRetryCount(0);
        billMapper.insert(b);
        return b;
    }

    private void aPendingUse(String subOrderNo) {
        String user = "CW" + System.nanoTime() % 100_000_000L;
        PtsUserAccount a = new PtsUserAccount();
        a.setUserNo(user);
        a.setMarket("CN");
        a.setBalance(0L);
        a.setPendingBalance(0L);
        a.setTotalEarn(0L);
        a.setLastActiveAt(System.currentTimeMillis());
        a.setExpireAt(System.currentTimeMillis() + 86_400_000L);
        accountMapper.insert(a);

        PtsUserLedger use = new PtsUserLedger();
        use.setLedgerNo("PLCW" + System.nanoTime() % 100_000_000L);
        use.setUserNo(user);
        use.setBizType("USE");
        use.setPoints(-300L);
        use.setBalanceAfter(0L);
        use.setAmountMinor(300L);
        use.setAcceptorMerchantNo("M0001");
        use.setSubOrderNo(subOrderNo);
        use.setStatus("PENDING");
        use.setMarket("CN");
        ledgerMapper.insert(use);
    }

    private String useStatus(String subOrderNo) {
        return DataScopeContext.executeWithoutScope(() ->
                ledgerMapper.selectOne(Wrappers.<PtsUserLedger>lambdaQuery()
                        .eq(PtsUserLedger::getSubOrderNo, subOrderNo)
                        .eq(PtsUserLedger::getBizType, "USE")
                        .last("LIMIT 1"))).getStatus();
    }

    private StlBill reload(String settleNo) {
        return DataScopeContext.executeWithoutScope(() ->
                billMapper.selectOne(Wrappers.<StlBill>lambdaQuery()
                        .eq(StlBill::getSettleNo, settleNo).last("LIMIT 1")));
    }
}
