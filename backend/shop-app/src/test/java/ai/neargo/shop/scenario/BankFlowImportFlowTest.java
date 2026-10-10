package ai.neargo.shop.scenario;

import ai.neargo.shop.payclient.OpsBankFlowAppService;
import ai.neargo.shop.payclient.OpsBankFlowAppService.ImportResultVO;
import ai.neargo.shop.pay.entity.StlBankFlow;
import ai.neargo.shop.pay.entity.StlBill;
import ai.neargo.shop.pay.entity.StlReconDiff;
import ai.neargo.shop.pay.mapper.SettleMappers.BankFlowMapper;
import ai.neargo.shop.pay.mapper.SettleMappers.BillMapper;
import ai.neargo.shop.pay.mapper.SettleMappers.ReconDiffMapper;
import ai.neargo.shop.pay.service.recon.PayoutReconAxis;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 银行流水导入（TDD §10）—— 出款对账 B 侧的<b>数据入口</b>。
 *
 * <p>这个类存在的理由，是 {@code PayoutBankReconFlowTest} 里所有用例都靠
 * 直接 insert 造流水：比对逻辑因此被验得很透，而<b>「流水怎么进来的」一条都没验</b>。
 * 少了导入这一段，那张表永远是空的，B 侧比对就是一块看起来在工作的死代码 ——
 * 闸门全绿、页面正常、差异恒为 0。
 *
 * <p>所以最后一个用例是<b>闭环</b>：不 insert，走导入服务，再跑对账轴。
 * 撤掉导入实现，它必须变红。
 *
 * <p>只要 test profile：服务层没挂 @Profile（只有 Controller 挂）。
 * 多一个 context key 会让 sql-init 重放种子撞主键（见 schema-test.sql 头部）。
 */
@SpringBootTest
@ActiveProfiles("test")
class BankFlowImportFlowTest {

    private static final String ENT = "E-IMP-1";

    @Autowired
    private OpsBankFlowAppService app;
    @Autowired
    private BankFlowMapper flows;
    @Autowired
    private BillMapper bills;
    @Autowired
    private ReconDiffMapper diffs;
    @Autowired
    private PayoutReconAxis axis;

    private String run;
    private final String today = LocalDate.now().toString();
    private final long todayMillis = LocalDate.now()
            .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();

    @BeforeEach
    void seed() {
        run = Long.toString(System.nanoTime());
    }

    @AfterEach
    void cleanup() {
        flows.delete(Wrappers.<StlBankFlow>lambdaQuery().likeRight(StlBankFlow::getFlowNo, "IMP" + run));
        bills.delete(Wrappers.<StlBill>lambdaQuery().eq(StlBill::getEntityNo, ENT));
        diffs.delete(Wrappers.<StlReconDiff>lambdaQuery()
                .eq(StlReconDiff::getAxis, PayoutReconAxis.CODE)
                .likeRight(StlReconDiff::getPaymentNo, "SB-IMP-" + run));
        diffs.delete(Wrappers.<StlReconDiff>lambdaQuery()
                .eq(StlReconDiff::getAxis, PayoutReconAxis.CODE)
                .likeRight(StlReconDiff::getChannelTxnNo, "IMP" + run));
    }

    @Test
    @DisplayName("导入一份流水：字段落到位，操作人与时刻有留痕")
    void importsRowsWithProvenance() {
        ImportResultVO r = app.importCsv("9月.csv", csv("""
                {D},IMP{R}-a,借,1234.50,深圳虹选,6222021234567890123,货款-E001
                """));

        assertThat(r.imported()).isEqualTo(1);
        assertThat(r.failed()).isZero();

        StlBankFlow f = one("IMP" + run + "-a");
        assertThat(f.getTradeDate()).isEqualTo(today);
        assertThat(f.getDirection()).isEqualTo(StlBankFlow.OUT);
        assertThat(f.getAmountMinor()).isEqualTo(123450L);
        // 判据从哪来要留痕
        assertThat(f.getImportedAt()).isNotNull().isPositive();
        // 全号不入库 —— 只存掩码
        assertThat(f.getCounterpartyAccountMasked()).isEqualTo("****0123");
    }

    @Test
    @DisplayName("★ 同一份重复上传是常态：第二次全部跳过，库里不翻倍，也不报错")
    void reImportIsIdempotent() {
        String body = csv("""
                {D},IMP{R}-a,借,10.00,甲,6222000000001111,x
                {D},IMP{R}-b,借,20.00,乙,6222000000002222,y
                """);

        ImportResultVO first = app.importCsv("w1.csv", body);
        assertThat(first.imported()).isEqualTo(2);
        assertThat(first.skipped()).isZero();

        ImportResultVO again = app.importCsv("w1.csv", body);
        assertThat(again.imported()).isZero();
        assertThat(again.skipped()).isEqualTo(2);
        assertThat(again.failed()).isZero();

        assertThat(countMine()).isEqualTo(2);
    }

    @Test
    @DisplayName("同一份文件里出现两次同号：入一条，另一条算跳过")
    void duplicateWithinOneFile() {
        ImportResultVO r = app.importCsv("dup.csv", csv("""
                {D},IMP{R}-a,借,10.00,甲,6222000000001111,x
                {D},IMP{R}-a,借,10.00,甲,6222000000001111,x
                """));

        assertThat(r.imported()).isEqualTo(1);
        assertThat(r.skipped()).isEqualTo(1);
        assertThat(countMine()).isEqualTo(1);
    }

    @Test
    @DisplayName("★ 坏行不回滚好行：失败的报行号，成功的照常入库")
    void badLinesDoNotRollBackGoodOnes() {
        ImportResultVO r = app.importCsv("mixed.csv", csv("""
                {D},IMP{R}-a,借,10.00,甲,6222000000001111,x
                哪天,IMP{R}-bad,借,10.00,乙,6222000000002222,y
                {D},IMP{R}-c,借,30.00,丙,6222000000003333,z
                """));

        assertThat(r.imported()).isEqualTo(2);
        assertThat(r.failed()).isEqualTo(1);
        assertThat(r.failures().get(0).line()).isEqualTo(3);
        // **这一条是重点**：靠唯一键抛异常判重会让整个事务回滚，好行一起没
        assertThat(countMine()).isEqualTo(2);
    }

    @Test
    @DisplayName("★ 闭环：导入之后对账轴才判得了，之前只能 deferred")
    void importedFlowsMakeTheAxisDecide() {
        String ref = "IMP" + run + "-hit";
        paidBill("h", ref, 30_000);

        // 导入之前：没有外部判据，一条差异都不记
        var before = axis.scan(System.currentTimeMillis());
        assertThat(mine()).isEmpty();
        assertThat(before.deferred()).isGreaterThanOrEqualTo(1);

        // 走导入服务，不是直接 insert —— 撤掉导入实现这里必须变红
        ImportResultVO r = app.importCsv("close.csv", csv("""
                {D},IMP{R}-hit,借,300.00,深圳虹选,6222000000009999,货款-E-IMP-1
                """));
        assertThat(r.imported()).isEqualTo(1);

        axis.scan(System.currentTimeMillis());

        // 勾上了：关系落到流水上，且不报任何差异
        assertThat(one(ref).getMatchedSettleNo()).isEqualTo("SB-IMP-" + run + "-h");
        assertThat(mine()).isEmpty();
    }

    /** 表头固定；行里 {@code {D}} 是今天、{@code {R}} 是本次运行的前缀（并行跑不互撞） */
    private String csv(String rows) {
        return "交易日期,交易流水号,借贷标志,发生额,对方户名,对方账号,摘要\n"
                + rows.replace("{D}", today).replace("{R}", run);
    }

    private StlBankFlow one(String flowNo) {
        return flows.selectOne(Wrappers.<StlBankFlow>lambdaQuery()
                .eq(StlBankFlow::getFlowNo, flowNo));
    }

    private long countMine() {
        return flows.selectCount(Wrappers.<StlBankFlow>lambdaQuery()
                .likeRight(StlBankFlow::getFlowNo, "IMP" + run));
    }

    private java.util.List<StlReconDiff> mine() {
        return diffs.selectList(Wrappers.<StlReconDiff>lambdaQuery()
                .eq(StlReconDiff::getAxis, PayoutReconAxis.CODE)
                .and(w -> w.likeRight(StlReconDiff::getPaymentNo, "SB-IMP-" + run)
                        .or().likeRight(StlReconDiff::getChannelTxnNo, "IMP" + run)));
    }

    private void paidBill(String tag, String paymentRef, long net) {
        StlBill b = new StlBill();
        String no = "SB-IMP-" + run + "-" + tag;
        b.setSettleNo(no);
        b.setSubOrderNo("SUB-" + no);
        b.setOrderNo("SO-" + no);
        b.setEntityNo(ENT);
        b.setGrossMinor(net);
        b.setCommissionMinor(0L);
        b.setServiceFeeMinor(0L);
        b.setNetMinor(net);
        b.setStatus(StlBill.PAID);
        b.setPaymentRef(paymentRef);
        b.setPaidAt(todayMillis);
        b.setBusinessMode(MerchantQueryPort.MODE_SELF_OPERATED);
        b.setTenantNo("MAIN");
        b.setCreatedAt(LocalDateTime.now());
        b.setUpdatedAt(LocalDateTime.now());
        bills.insert(b);
    }
}
