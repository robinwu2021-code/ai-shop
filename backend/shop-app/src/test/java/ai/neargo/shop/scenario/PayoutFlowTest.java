package ai.neargo.shop.scenario;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.merchant.entity.MchPayoutAccount;
import ai.neargo.shop.merchant.mapper.MerchantMappers.PayoutAccountMapper;
import ai.neargo.shop.merchant.service.PayoutAccountCipher;
import ai.neargo.shop.pay.PayoutService;
import ai.neargo.shop.pay.SettleBatchService;
import ai.neargo.shop.pay.entity.StlBill;
import ai.neargo.shop.pay.entity.StlPayout;
import ai.neargo.shop.pay.entity.StlSettleBatch;
import ai.neargo.shop.pay.mapper.SettleMappers;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 放款记录（TDD-账期推进与放款记录 批 2 · AC6 AC7）。
 *
 * <p>这是批次链与应付链接起来的那一段：自营单走完 定T2 → 入批 → 截批 → 自查 之后，
 * 运营点放款，生成按收款号分组的放款记录；之后凭证回填挂在放款记录上、结算单跟着翻。
 *
 * <p>判据取「钱有没有按规则动」（状态、金额、分组），不取耗时。
 */
@SpringBootTest
@ActiveProfiles("test")
class PayoutFlowTest {

    private static final String ENTITY = "M-PAYOUT-T";
    private static final long DAY = 86400000L;
    private static final String CARD = "6222020000111122223";

    @Autowired
    private SettleBatchService batchService;
    @Autowired
    private PayoutService payoutService;
    @Autowired
    private SettleMappers.BillMapper billMapper;
    @Autowired
    private SettleMappers.SettleBatchMapper batchMapper;
    @Autowired
    private SettleMappers.PayoutMapper payoutMapper;
    @Autowired
    private PayoutAccountMapper accounts;
    @Autowired
    private PayoutAccountCipher cipher;
    @Autowired
    private JdbcTemplate jdbc;

    private String run;

    @BeforeEach
    void seed() {
        run = Long.toString(System.nanoTime());
    }

    @AfterEach
    void cleanUp() {
        DataScopeContext.executeWithoutScope(() -> {
            jdbc.update("DELETE FROM stl_payout WHERE entity_no = ?", ENTITY);
            jdbc.update("DELETE FROM stl_bill WHERE entity_no = ?", ENTITY);
            jdbc.update("DELETE FROM stl_settle_batch WHERE entity_no = ?", ENTITY);
            jdbc.update("DELETE FROM ord_status_log WHERE sub_order_no LIKE 'SUB-PO-%'");
            jdbc.update("DELETE FROM mch_payout_account WHERE entity_no = ?", ENTITY);
            return null;
        });
    }

    @Test
    @DisplayName("★★★ 放款按收款号分组一组一笔，合计 = 本组结算单 net 之和；结算单 CONFIRMED、批次 RELEASED")
    void releaseGroupsByPayMerchantAndSumsNet() {
        activeAccount();
        selfBill("a", 10_000, StlBill.INV_VERIFIED, "PM-1");
        selfBill("b", 20_000, StlBill.INV_NONE, "PM-1");
        selfBill("c", 5_000, StlBill.INV_VERIFIED, "PM-2");
        String batchNo = runChainToReconciled();

        List<PayoutService.PayoutVO> out = payoutService.releaseBatch(batchNo, "OPS-1");

        // 两个收款号 → 两笔
        assertThat(out).hasSize(2);
        var pm1 = out.stream().filter(p -> "PM-1".equals(p.payMerchantNo())).findFirst().orElseThrow();
        var pm2 = out.stream().filter(p -> "PM-2".equals(p.payMerchantNo())).findFirst().orElseThrow();
        assertThat(pm1.amountMinor()).isEqualTo(30_000);
        assertThat(pm1.billCount()).isEqualTo(2);
        assertThat(pm2.amountMinor()).isEqualTo(5_000);
        // 户名快照来自收款账户：银行要户名，而账号会改
        assertThat(pm1.accountName()).isEqualTo("放款测试主体");
        assertThat(pm1.status()).isEqualTo(StlPayout.PENDING);
        // 结算单：挂上放款号、CONFIRMED（双方认这个数 = 批次自查过了）
        StlBill a = reload("STL-PO-" + run + "-a");
        assertThat(a.getPayoutNo()).isEqualTo(pm1.payoutNo());
        assertThat(a.getStatus()).isEqualTo(StlBill.CONFIRMED);
        // 批次 RELEASED + 放行时刻
        StlSettleBatch batch = reloadBatch(batchNo);
        assertThat(batch.getStatus()).isEqualTo(StlSettleBatch.RELEASED);
        assertThat(batch.getReleasedAt()).isNotNull();
    }

    @Test
    @DisplayName("★★★ 任一单票未了结不放，报出是哪几张；批次不动")
    void invoiceGateNamesTheBills() {
        activeAccount();
        selfBill("ok", 10_000, StlBill.INV_VERIFIED, null);
        selfBill("noinv", 20_000, StlBill.INV_PENDING, null);
        String batchNo = runChainToReconciled();

        assertThatThrownBy(() -> payoutService.releaseBatch(batchNo, "OPS-1"))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).errorCode()).isEqualTo(ErrorCode.PAYOUT_INVOICE_PENDING))
                // 只报第一张的话，运营核完一张再点一次又被下一张挡 —— 单号在 args 里带出去
                .satisfies(e -> assertThat(String.valueOf(((BizException) e).args()[0]))
                        .contains("STL-PO-" + run + "-noinv"));
        assertThat(reloadBatch(batchNo).getStatus()).as("闸不过批次不动").isEqualTo(StlSettleBatch.RECONCILED);
        assertThat(payoutCount()).isZero();
    }

    @Test
    @DisplayName("★★★ 没有生效收款账户不放 —— 钱不知道打给谁；批次不动")
    void accountGateBlocksRelease() {
        selfBill("x", 10_000, StlBill.INV_VERIFIED, null);
        String batchNo = runChainToReconciled();

        assertThatThrownBy(() -> payoutService.releaseBatch(batchNo, "OPS-1"))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).errorCode()).isEqualTo(ErrorCode.PAYOUT_ACCOUNT_MISSING));
        assertThat(reloadBatch(batchNo).getStatus()).isEqualTo(StlSettleBatch.RECONCILED);
    }

    @Test
    @DisplayName("★★★ 只有 RECONCILED 能放：RELEASED 再放是重复打款，截批未自查的也不行")
    void onlyReconciledBatchCanBeReleased() {
        activeAccount();
        selfBill("r", 10_000, StlBill.INV_VERIFIED, null);
        String batchNo = runChainToReconciled();
        payoutService.releaseBatch(batchNo, "OPS-1");

        assertThatThrownBy(() -> payoutService.releaseBatch(batchNo, "OPS-1"))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).errorCode()).isEqualTo(ErrorCode.BATCH_NOT_RELEASABLE));
        assertThat(payoutCount()).as("第二次不许再生成一笔").isEqualTo(1);
    }

    @Test
    @DisplayName("★★★ 回填凭证后结算单跟着 PAID 并带同一个凭证号；凭证号必填")
    void markPaidCascadesToBills() {
        activeAccount();
        selfBill("p1", 10_000, StlBill.INV_VERIFIED, null);
        selfBill("p2", 20_000, StlBill.INV_VERIFIED, null);
        String batchNo = runChainToReconciled();
        String payoutNo = payoutService.releaseBatch(batchNo, "OPS-1").get(0).payoutNo();

        assertThatThrownBy(() -> payoutService.markPaid(payoutNo, " ", "OPS-2"))
                .isInstanceOf(BizException.class);

        var vo = payoutService.markPaid(payoutNo, "BANK-REF-001", "OPS-2");

        assertThat(vo.status()).isEqualTo(StlPayout.PAID);
        assertThat(vo.paidBy()).isEqualTo("OPS-2");
        for (String tag : List.of("p1", "p2")) {
            StlBill b = reload("STL-PO-" + run + "-" + tag);
            assertThat(b.getStatus()).isEqualTo(StlBill.PAID);
            // 同一个凭证号镜像到每张单：存量那条老路的读者还在读它
            assertThat(b.getPaymentRef()).isEqualTo("BANK-REF-001");
            assertThat(b.getPaidAt()).isNotNull();
        }
        // 幂等：再登一次不报错、不改
        assertThat(payoutService.markPaid(payoutNo, "BANK-REF-001", "OPS-2").status()).isEqualTo(StlPayout.PAID);
    }

    @Test
    @DisplayName("★★★ 退回：放款 FAILED、结算单回待对账并摘掉放款号、批次回可放款 —— 退回的钱要能再放一次")
    void markFailedRollsBackSoItCanBeReleasedAgain() {
        activeAccount();
        selfBill("f", 10_000, StlBill.INV_VERIFIED, null);
        String batchNo = runChainToReconciled();
        String payoutNo = payoutService.releaseBatch(batchNo, "OPS-1").get(0).payoutNo();
        payoutService.markPaid(payoutNo, "BANK-REF-BAD", "OPS-2");

        var vo = payoutService.markFailed(payoutNo, "银行退回：户名不符", "OPS-3");

        assertThat(vo.status()).isEqualTo(StlPayout.FAILED);
        assertThat(vo.failReason()).contains("户名不符");
        StlBill b = reload("STL-PO-" + run + "-f");
        assertThat(b.getStatus()).isEqualTo(StlBill.PENDING_RECON);
        // updateById 跳过 null —— 这两列要真的清掉，不是「没改」
        assertThat(b.getPayoutNo()).isNull();
        assertThat(b.getPaymentRef()).isNull();
        StlSettleBatch batch = reloadBatch(batchNo);
        assertThat(batch.getStatus()).isEqualTo(StlSettleBatch.RECONCILED);
        assertThat(batch.getReleasedAt()).isNull();
        // 能再放一次，且是一笔新的
        var again = payoutService.releaseBatch(batchNo, "OPS-1");
        assertThat(again).hasSize(1);
        assertThat(again.get(0).payoutNo()).isNotEqualTo(payoutNo);
    }

    @Test
    @DisplayName("★★ 全是第三方单的批次：置 RELEASED，不生成放款记录（分账轨另接）")
    void thirdPartyBatchReleasesWithoutPayout() {
        StlBill b = selfBill("tp", 10_000, StlBill.INV_NONE, null);
        b.setBusinessMode("THIRD_PARTY");
        b.setStatus(StlBill.PENDING);
        DataScopeContext.executeWithoutScope(() -> billMapper.updateById(b));
        String batchNo = runChainToReconciled();

        var out = payoutService.releaseBatch(batchNo, "OPS-1");

        assertThat(out).isEmpty();
        assertThat(reloadBatch(batchNo).getStatus()).isEqualTo(StlSettleBatch.RELEASED);
        assertThat(payoutCount()).isZero();
    }

    // ------------------------------------------------------------ helpers

    private String runChainToReconciled() {
        batchService.markSettleable();
        batchService.collectIntoBatches();
        batchService.closeDueBatches();
        batchService.reconcileClosedBatches();
        StlSettleBatch batch = DataScopeContext.executeWithoutScope(() ->
                batchMapper.selectOne(Wrappers.<StlSettleBatch>lambdaQuery()
                        .eq(StlSettleBatch::getEntityNo, ENTITY).last("LIMIT 1")));
        assertThat(batch).as("链路要走到批次").isNotNull();
        assertThat(batch.getStatus()).as("自查全过才能放款").isEqualTo(StlSettleBatch.RECONCILED);
        return batch.getBatchNo();
    }

    /** 一张 30 天前成交、已完成、无售后的自营单 */
    private StlBill selfBill(String tag, long net, String invoiceStatus, String payMerchantNo) {
        long completedAt = System.currentTimeMillis() - 30 * DAY;
        String subNo = "SUB-PO-" + run + "-" + tag;
        DataScopeContext.executeWithoutScope(() -> jdbc.update(
                "INSERT INTO ord_status_log (sub_order_no, status, at, tenant_no, created_at)"
                        + " VALUES (?, 'COMPLETED', ?, 'MAIN', CURRENT_TIMESTAMP)", subNo, completedAt));
        StlBill b = new StlBill();
        b.setSettleNo("STL-PO-" + run + "-" + tag);
        b.setSubOrderNo(subNo);
        b.setOrderNo("SO-PO-" + run + "-" + tag);
        b.setEntityNo(ENTITY);
        b.setPayChannel("WECHAT");
        b.setPayMerchantNo(payMerchantNo);
        b.setBusinessMode(MerchantQueryPort.MODE_SELF_OPERATED);
        b.setStatus(StlBill.PENDING_RECON);
        b.setInvoiceStatus(invoiceStatus);
        b.setGrossMinor(net);
        b.setCommissionMinor(0L);
        b.setServiceFeeMinor(0L);
        b.setNetMinor(net);
        b.setAccruedAt(completedAt);
        b.setTenantNo("MAIN");
        b.setCreatedAt(LocalDateTime.now());
        b.setUpdatedAt(LocalDateTime.now());
        DataScopeContext.executeWithoutScope(() -> billMapper.insert(b));
        return b;
    }

    private void activeAccount() {
        MchPayoutAccount a = new MchPayoutAccount();
        a.setAccountNo("PAC-PO-" + run);
        a.setEntityNo(ENTITY);
        a.setAccountType(MchPayoutAccount.CORPORATE);
        a.setAccountName("放款测试主体");
        a.setAccountNumberEnc(cipher.encrypt(CARD));
        a.setAccountMasked("****2223");
        a.setBankName("浦发银行");
        a.setStatus(MchPayoutAccount.ACTIVE);
        a.setTenantNo("MAIN");
        a.setCreatedAt(LocalDateTime.now());
        a.setUpdatedAt(LocalDateTime.now());
        DataScopeContext.executeWithoutScope(() -> accounts.insert(a));
    }

    private StlBill reload(String settleNo) {
        return DataScopeContext.executeWithoutScope(() ->
                billMapper.selectOne(Wrappers.<StlBill>lambdaQuery().eq(StlBill::getSettleNo, settleNo).last("LIMIT 1")));
    }

    private StlSettleBatch reloadBatch(String batchNo) {
        return DataScopeContext.executeWithoutScope(() ->
                batchMapper.selectOne(Wrappers.<StlSettleBatch>lambdaQuery().eq(StlSettleBatch::getBatchNo, batchNo).last("LIMIT 1")));
    }

    private long payoutCount() {
        return DataScopeContext.executeWithoutScope(() ->
                payoutMapper.selectCount(Wrappers.<StlPayout>lambdaQuery().eq(StlPayout::getEntityNo, ENTITY)));
    }
}
